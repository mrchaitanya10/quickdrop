#!/usr/bin/env python3
"""QuickDrop local receiver. Uses Python's standard library and Tk."""
import errno
import fcntl
import hashlib
import http.cookiejar
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import urllib.error
import urllib.parse
import urllib.request
from qr_pairing import new_ticket, draw_qr, discover_receiver


def pairing_address(text):
    parsed = urllib.parse.urlsplit(text.strip())
    if parsed.scheme != "http" or parsed.username or parsed.password:
        raise ValueError("Paste the local http:// pairing address shown on your phone.")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError:
        raise ValueError("Use the numeric Wi-Fi or hotspot address from the phone.")
    if not address.is_private or parsed.path not in ("", "/") or parsed.query:
        raise ValueError("Use your phone's private local pairing address.")
    if not re.fullmatch(r"[a-f0-9]{48}", parsed.fragment):
        raise ValueError("Include the complete address, including # and the pairing key.")
    origin = f"http://{parsed.hostname}:{parsed.port or 80}"
    return origin, parsed.fragment


def connection_address(text):
    """Accept a short local IPv4 address, or a legacy complete pairing link."""
    text = text.strip()
    if not text:
        raise ValueError("Enter the phone address shown in QuickDrop on your phone.")
    if "#" in text:
        return pairing_address(text)
    parsed = urllib.parse.urlsplit(text if "://" in text else "http://" + text)
    try:
        ip = ipaddress.ip_address(parsed.hostname)
        port = parsed.port or 45085
    except (ValueError, TypeError):
        raise ValueError("Enter a phone address like 192.168.43.1, without spaces.")
    if (parsed.scheme != "http" or not isinstance(ip, ipaddress.IPv4Address)
            or not ip.is_private or ip.is_unspecified or ip.is_multicast
            or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query):
        raise ValueError("Use the local phone address shown in QuickDrop, on your private Wi-Fi or hotspot.")
    return f"http://{ip}:{port}", None


def filename(value):
    cleaned = re.sub(r'[\\/\x00-\x1f\x7f]', "_", value).strip(" .")
    return cleaned[:180] or "quickdrop-file"


def default_folder():
    for path in (Path("/mnt/chromeos/MyFiles/Downloads"), Path("/mnt/shared/MyFiles/Downloads")):
        if path.is_dir() and os.access(path, os.W_OK):
            return path / "QuickDrop"
    return Path.home() / "Downloads" / "QuickDrop"



def settings_path():
    return Path.home() / ".config" / "quickdrop" / "receiver.json"


def load_settings():
    try:
        path = settings_path()
        if path.is_symlink():
            return {}
        value = json.loads(path.read_text())
        # Validate saved origins just as strictly as new pairing links.
        pairing_address(value["origin"] + "/#" + value["token"])
        return value
    except (OSError, ValueError, KeyError, TypeError):
        return {}


_SETTINGS_LOCK = threading.RLock()


def save_settings(value):
    with _SETTINGS_LOCK:
        _save_settings(dict(value))


def _save_settings(value):
    path = settings_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Remove the linked QuickDrop settings file before pairing.")
    temporary = path.with_suffix(".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


class Receiver:
    def __init__(self, origin):
        self.origin = origin
        self.cookies = http.cookiejar.CookieJar()
        self.offer = None
        self.expected_offer = None
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(self.cookies),
        )

    def pair(self, key):
        request = urllib.request.Request(
            self.origin + ("/pair" if key else "/connect"), data=(key or "receiver").encode("ascii"),
            headers={"Origin": self.origin, "Content-Type": "text/plain", "X-QuickDrop-Remember": "1"},
        )
        with self.opener.open(request, timeout=15) as response:
            return response.read(4096).decode()

    def token(self):
        return next((cookie.value for cookie in self.cookies if cookie.name == "qd"), None)

    def restore(self, token):
        self.cookies.set_cookie(http.cookiejar.Cookie(0, "qd", token, None, False,
            urllib.parse.urlsplit(self.origin).hostname, False, False, "/", True,
            False, None, True, None, None, {}, False))

    def status(self):
        with self.opener.open(self.origin + "/status", timeout=4) as response:
            lines = response.read(8192).decode().splitlines()
        if not lines or lines[0] not in ("waiting", "empty", "ready"):
            raise ValueError("The phone returned an unexpected pairing status.")
        if lines[0] != "ready":
            return lines[0], None, None
        if len(lines) < 3:
            raise ValueError("Could not read the selected file.")
        size = int(lines[2])
        if not 0 < size <= 10_000_000_000:
            raise ValueError("Choose a file up to 10 GB.")
        self.offer = lines[3] if len(lines) > 3 else None
        return "ready", filename(lines[1]), size

    def acknowledge(self, offer):
        request = urllib.request.Request(self.origin + "/received", data=offer.encode("ascii"),
            headers={"Origin": self.origin, "Content-Type": "text/plain"})
        try:
            with self.opener.open(request, timeout=4) as response:
                response.read(128)
        except urllib.error.HTTPError as error:
            if error.code != 404:  # Compatibility with the 0.3 phone server.
                raise

    def download(self, name, size, folder, stop, progress):
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(name.encode()).hexdigest()[:16]
        partial = folder / f".quickdrop-{digest}.part"
        metadata = partial.with_suffix(".json")
        if partial.is_symlink() or metadata.is_symlink():
            raise ValueError("Choose another destination folder.")
        saved = {}
        try:
            saved = json.loads(metadata.read_text())
        except (OSError, ValueError):
            pass
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {}
        if 0 < offset < size and saved.get("size") == size and saved.get("etag"):
            headers = {"Range": f"bytes={offset}-", "If-Range": saved["etag"]}
        elif offset == size and saved.get("size") == size and saved.get("etag"):
            # Ask for the final byte so the server verifies the selection's identity.
            offset = size - 1
            headers = {"Range": f"bytes={offset}-", "If-Range": saved["etag"]}
        else:
            offset = 0
        if self.expected_offer:
            headers["If-Match"] = self.expected_offer
        request = urllib.request.Request(self.origin + "/file", headers=headers)
        with self.opener.open(request, timeout=15) as response:
            resumed = response.status == 206
            etag = response.headers.get("ETag")
            if resumed:
                expected = f"bytes {offset}-{size-1}/{size}"
                if not headers or etag != saved.get("etag") or response.headers.get("Content-Range") != expected:
                    raise ValueError("The phone could not safely resume this download.")
            else:
                if response.status != 200:
                    raise ValueError("The file is unavailable.")
                offset = 0
            remaining = int(response.headers.get("Content-Length", "-1"))
            if remaining != size - offset:
                raise ValueError("The selected file changed. Pair again and retry.")
            if shutil.disk_usage(folder).free < remaining:
                raise ValueError("There is not enough free space in the destination folder.")
            metadata.write_text(json.dumps({"size": size, "etag": etag}))
            mode = "r+b" if resumed else "wb"
            started, initial = time.monotonic(), offset
            with partial.open(mode) as output:
                if resumed:
                    output.seek(offset)
                    output.truncate()
                last_update = 0
                while offset < size:
                    if stop.is_set():
                        return None
                    chunk = response.read(min(256 * 1024, size - offset))
                    if not chunk:
                        raise ConnectionError("Transfer interrupted. Press Resume to continue.")
                    output.write(chunk)
                    offset += len(chunk)
                    now = time.monotonic()
                    if now - last_update > .2 or offset == size:
                        speed = (offset - initial) / max(now - started, .01)
                        progress(offset, size, speed)
                        last_update = now
                output.flush()
                os.fsync(output.fileno())
        target = folder / name
        number = 1
        while True:
            try:
                descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                os.close(descriptor)
                break
            except FileExistsError:
                original = Path(name)
                target = folder / f"{original.stem} ({number}){original.suffix}"
                number += 1
        try:
            os.replace(partial, target)
        except OSError:
            target.unlink(missing_ok=True)
            raise
        metadata.unlink(missing_ok=True)
        return target


class App:
    def __init__(self, root):
        self.root = root
        self.messages = queue.Queue()
        self.stop = threading.Event()
        self.busy = False
        self.receiver = None
        self.selected = None
        self.closing = False
        self.next_action = None
        self.saved = load_settings()
        self.last_path = self.saved.get("last_file")
        self.setup_mode = not bool(self.saved)
        self.manual_mode = False
        self.qr_ticket = None
        self.folder_value = self.saved.get("folder", str(default_folder()))
        self.folder = tk.StringVar(value=self.folder_value)
        root.title("QuickDrop")
        root.geometry("620x650")
        root.minsize(540, 610)
        root.configure(background="#f4f7f2")
        root.option_add("*Font", "{DejaVu Sans} 10")
        style = ttk.Style()
        style.theme_use("clam")
        for name, color in (("TFrame", "#f4f7f2"), ("Card.TFrame", "#ffffff")):
            style.configure(name, background=color)
        style.configure("TLabel", background="#f4f7f2", foreground="#111827")
        style.configure("Card.TLabel", background="#ffffff", foreground="#111827")
        style.configure("Muted.TLabel", background="#ffffff", foreground="#637168")
        style.configure("TButton", padding=(12, 10), borderwidth=0, background="#eaf1e5", foreground="#166534")
        style.map("TButton", background=[("active", "#dce9d5"), ("disabled", "#edf0eb")], foreground=[("disabled", "#879187")])
        style.configure("Primary.TButton", background="#166534", foreground="#ffffff")
        style.map("Primary.TButton", background=[("active", "#12522b"), ("disabled", "#bccdbc")], foreground=[("disabled", "#ffffff")])
        style.configure("Horizontal.TProgressbar", background="#166534", troughcolor="#eaf1e5", borderwidth=0, thickness=8)
        self.body = ttk.Frame(root, padding=(24, 18))
        self.body.pack(fill="both", expand=True)
        header = ttk.Frame(self.body)
        self.header = header
        header.pack(fill="x", pady=(0, 16))
        try:
            self.logo = tk.PhotoImage(file=str(Path(__file__).with_name("wordmark.png")))
            factor = max(1, (self.logo.width()+219)//220, (self.logo.height()+59)//60)
            self.logo = self.logo.subsample(factor)
            ttk.Label(header, image=self.logo).pack(side="left")
            self.icon = tk.PhotoImage(file=str(Path(__file__).with_name("quickdrop.png")))
            root.iconphoto(True, self.icon)
        except tk.TclError:
            ttk.Label(header, text="QuickDrop", font=("DejaVu Sans", 23, "bold")).pack(side="left")
        ttk.Button(header, text="Settings", command=self.settings).pack(side="right")
        ttk.Button(header, text="Connect phone", command=self.begin_setup).pack(side="right", padx=(0, 8))
        self.hero = self.card()
        self.badge = tk.StringVar(value="LET’S GET CONNECTED" if not self.saved else "RECONNECTING")
        ttk.Label(self.hero, textvariable=self.badge, style="Muted.TLabel", font=("DejaVu Sans", 9, "bold")).pack(anchor="w")
        self.heading = tk.StringVar(value="Your phone. Your files." if not self.saved else "Looking for your phone…")
        ttk.Label(self.hero, textvariable=self.heading, style="Card.TLabel", font=("DejaVu Sans", 23, "bold"), wraplength=480).pack(anchor="w", pady=(10, 7))
        self.status_text = tk.StringVar(value="Connect once. Then send files from your phone with a tap.")
        ttk.Label(self.hero, textvariable=self.status_text, style="Muted.TLabel", wraplength=480).pack(anchor="w")
        self.setup_panel = ttk.Frame(self.body, style="Card.TFrame", padding=18)
        ttk.Label(self.setup_panel, text="Scan to connect.", style="Card.TLabel", font=("DejaVu Sans", 22, "bold")).pack(anchor="center")
        ttk.Label(self.setup_panel, text="On your phone, open QuickDrop → Scan laptop QR.", style="Muted.TLabel", wraplength=480).pack(anchor="center", pady=(8, 6))
        self.qr_canvas = tk.Canvas(self.setup_panel, highlightthickness=0, background="white")
        self.qr_canvas.pack(anchor="center", pady=6)
        self.qr_status = tk.StringVar(value="Preparing your connection code…")
        ttk.Label(self.setup_panel, textvariable=self.qr_status, style="Muted.TLabel", wraplength=470).pack(anchor="center", pady=7)
        self.qr_button = ttk.Button(self.setup_panel, text="Refresh QR", command=self.start_qr)
        self.qr_button.pack(anchor="center")
        self.manual_panel = ttk.Frame(self.body, style="Card.TFrame", padding=18)
        ttk.Label(self.manual_panel, text="Connect your phone", style="Card.TLabel", font=("DejaVu Sans", 14, "bold")).pack(anchor="w")
        ttk.Label(self.manual_panel, text="1. Use the same private Wi-Fi or phone hotspot.\n2. On your phone, open Settings → Manual connection.\n3. Enter the phone address shown there.", style="Muted.TLabel", wraplength=480, justify="left").pack(anchor="w", pady=(8, 12))
        self.address = tk.StringVar()
        self.address_entry = ttk.Entry(self.manual_panel, textvariable=self.address, font=("DejaVu Sans", 14))
        self.address_entry.pack(fill="x", ipady=5)
        self.address_entry.bind("<Return>", lambda event: self.pair())
        self.address_entry.bind("<Control-a>", self.select_address)
        self.address_entry.bind("<Control-A>", self.select_address)
        ttk.Label(self.manual_panel, text="Example: 192.168.43.1 · Your old full pairing link also works.", style="Muted.TLabel", wraplength=480).pack(anchor="w", pady=7)
        self.pair_button = ttk.Button(self.manual_panel, text="Connect phone", style="Primary.TButton", command=self.pair)
        self.pair_button.pack(fill="x")
        self.transfer_panel = ttk.Frame(self.body, style="Card.TFrame", padding=18)
        self.current_name = tk.StringVar()
        ttk.Label(self.transfer_panel, textvariable=self.current_name, style="Card.TLabel", wraplength=480, font=("DejaVu Sans", 11, "bold")).pack(anchor="w", pady=(0, 8))
        self.bar = ttk.Progressbar(self.transfer_panel, maximum=100)
        self.bar.pack(fill="x")
        self.progress_text = tk.StringVar()
        ttk.Label(self.transfer_panel, textvariable=self.progress_text, style="Muted.TLabel").pack(anchor="w", pady=(8, 0))
        self.receipt_panel = ttk.Frame(self.body, style="Card.TFrame", padding=18)
        ttk.Label(self.receipt_panel, text="LAST FILE SAVED", style="Muted.TLabel", font=("DejaVu Sans", 9, "bold")).pack(anchor="w")
        self.receipt_name = tk.StringVar(value=Path(self.last_path).name[:110] if self.last_path else "")
        ttk.Label(self.receipt_panel, textvariable=self.receipt_name, style="Card.TLabel", wraplength=480).pack(anchor="w", pady=(8, 0))
        self.destination = self.card()
        ttk.Label(self.destination, text="Files save here automatically", style="Muted.TLabel").pack(anchor="w")
        self.folder_label = ttk.Label(self.destination, textvariable=self.folder, style="Card.TLabel", wraplength=480)
        self.folder_label.pack(anchor="w", pady=(7, 0))
        actions = ttk.Frame(self.body)
        self.actions = actions
        actions.pack(fill="x", pady=(15, 10))
        ttk.Button(actions, text="Open save folder", command=self.open_folder).pack(side="left")
        self.transfer = ttk.Button(actions, text="Pause receiving", command=self.pause, state="disabled")
        self.transfer.pack(side="right")
        self.note = ttk.Label(self.body, text="Keep this window open or minimized. No mobile data needed.\nPrivate network only · Unencrypted local preview", foreground="#637168", wraplength=510)
        self.note.pack(anchor="w")
        self.show_setup(self.setup_mode)
        if self.last_path:
            self.receipt_panel.pack(fill="x", pady=(0, 12), before=self.destination)
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self.poll)
        if self.saved:
            root.after(200, self.reconnect)
        else:
            root.after(200, self.start_qr)

    def card(self):
        box = ttk.Frame(self.body, style="Card.TFrame", padding=18)
        box.pack(fill="x", pady=(0, 12))
        return box

    def show_setup(self, show):
        self.setup_mode = show
        for panel in (self.hero, self.setup_panel, self.manual_panel, self.transfer_panel, self.receipt_panel, self.destination, self.actions):
            panel.pack_forget()
        if show and not self.manual_mode:
            self.setup_panel.pack(fill="x", pady=(0, 12), before=self.note)
        elif show:
            self.hero.pack(fill="x", pady=(0, 12), before=self.note)
            self.manual_panel.pack(fill="x", pady=(0, 12), before=self.note)
        else:
            self.hero.pack(fill="x", pady=(0, 12), before=self.note)
        if not show and self.saved:
            if self.last_path:
                self.receipt_panel.pack(fill="x", pady=(0, 12), before=self.note)
            self.destination.pack(fill="x", pady=(0, 12), before=self.note)
            self.actions.pack(fill="x", pady=(3, 10), before=self.note)

    def select_address(self, event=None):
        self.address_entry.select_range(0, "end")
        self.address_entry.icursor("end")
        return "break"

    def settings(self):
        dialog = tk.Toplevel(self.root)
        dialog.title("QuickDrop settings")
        dialog.transient(self.root)
        dialog.resizable(False, False)
        frame = ttk.Frame(dialog, padding=24)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Make yourself at home.", font=("DejaVu Sans", 18, "bold")).pack(anchor="w", pady=(0, 14))
        ttk.Button(frame, text="Change save folder", command=self.choose_folder).pack(fill="x", pady=4)
        ttk.Button(frame, text="Show connection QR", command=lambda: (dialog.destroy(), self.begin_setup())).pack(fill="x", pady=4)
        ttk.Button(frame, text="Manual connection / update address", command=lambda: (dialog.destroy(), self.begin_setup(manual=True))).pack(fill="x", pady=4)
        ttk.Button(frame, text="Forget this phone", command=lambda: self.forget(dialog)).pack(fill="x", pady=4)
        ttk.Label(frame, text="QuickDrop 0.5.2 · C Labs\n\nTransfers stay on your local network. This preview uses\nHTTP without encryption. Use a trusted password-protected\nWi-Fi or hotspot. Pairing approval controls access.\n\nTo connect another phone, show a fresh QR and scan it.\nSome Wi-Fi networks block local discovery; manual pairing\nis available as a fallback.", foreground="#637168").pack(anchor="w", pady=16)
        ttk.Button(frame, text="Done", command=dialog.destroy).pack(fill="x")
        dialog.grab_set()

    def choose_folder(self):
        if self.selected:
            messagebox.showinfo("A file is arriving", "Pause receiving before changing the save folder.", parent=self.root)
            return
        value = filedialog.askdirectory(parent=self.root, initialdir=str(Path(self.folder.get()).parent))
        if value and self.selected:
            messagebox.showinfo("A file is arriving", "Pause receiving before changing the save folder.", parent=self.root)
            return
        if value:
            self.folder_value = value
            self.folder.set(value)
            if self.saved:
                self.saved["folder"] = value
                save_settings(self.saved)

    def open_folder(self):
        try:
            folder = Path(self.folder.get())
            folder.mkdir(parents=True, exist_ok=True)
            subprocess.Popen(["xdg-open", str(folder)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            messagebox.showinfo("Save folder", "Your files save to:\n" + self.folder.get(), parent=self.root)

    def begin_setup(self, manual=False):
        if self.busy:
            self.next_action = lambda: self.begin_setup(manual)
            self.stop.set()
            self.status_text.set("Pausing the current connection safely.")
            return
        self.manual_mode = manual
        if manual:
            self.show_setup(True)
            self.badge.set("MANUAL CONNECTION")
            self.heading.set("Connect with an address.")
            self.status_text.set("On your phone, open Settings → Manual connection.")
            self.address_entry.focus_set()
        else:
            self.start_qr()

    def start_qr(self):
        if self.busy:
            self.next_action = self.start_qr
            self.stop.set()
            self.qr_status.set("Preparing a fresh QR…")
            return
        self.manual_mode = False
        self.show_setup(True)
        self.qr_ticket = new_ticket()
        try:
            draw_qr(self.qr_canvas, self.qr_ticket)
        except ImportError:
            self.qr_status.set("QR dependencies are missing. Reinstall the 0.5 Linux package, or use Manual connection in Settings.")
            return
        self.qr_status.set("Use the same private Wi-Fi or hotspot. This code refreshes automatically.")
        ticket = self.qr_ticket
        def work():
            client = discover_receiver(ticket, Receiver, self.stop,
                on_progress=lambda stage, text: self.messages.put(("qr_status", text)))
            if client is None:
                if not self.stop.is_set():
                    self.messages.put(("qr_refresh",))
                return
            if self.stop.is_set():
                return
            self.receiver = client
            self.saved = {"origin": client.origin, "token": client.token(),
                "folder": self.folder_value, "last_offer": None}
            save_settings(self.saved)
            self.messages.put(("paired",))
            self.listen()
        self.folder_value = self.folder.get()
        self.background(work)

    def background(self, operation):
        self.busy = True
        self.stop.clear()
        self.pair_button.configure(state="disabled")
        self.transfer.configure(text="Pause receiving", state="normal", command=self.pause)
        def work():
            try:
                operation()
            except Exception as error:
                if not self.stop.is_set():
                    if isinstance(error, urllib.error.HTTPError):
                        text = "Open Settings → Manual connection on your phone, then try again." if error.code == 403 else "The phone is busy. Wait a moment, then try connecting again."
                    elif isinstance(error, (OSError, ConnectionError)):
                        text = "Couldn’t reach your phone. Check its address and put both devices on the same Wi-Fi or hotspot."
                    else:
                        text = str(error)
                    self.messages.put(("error", text))
            finally:
                self.messages.put(("idle",))
        threading.Thread(target=work, daemon=True).start()

    def pair(self):
        if self.busy:
            return
        try:
            origin, key = connection_address(self.address.get())
        except ValueError as error:
            self.status_text.set(str(error))
            return
        self.heading.set("Connecting to your phone…")
        self.badge.set("CONNECTING")
        self.status_text.set("Keep QuickDrop open on your phone.")
        self.selected = None
        self.receiver = Receiver(origin)
        def work():
            code = self.receiver.pair(key)
            self.messages.put(("pairing", code))
            deadline = time.monotonic() + 120
            while not self.stop.is_set():
                state, _, _ = self.receiver.status()
                if state != "waiting":
                    self.saved = {"origin": origin, "token": self.receiver.token(),
                        "folder": self.folder_value, "last_offer": None}
                    save_settings(self.saved)
                    self.messages.put(("paired",))
                    self.listen()
                    return
                if time.monotonic() > deadline:
                    raise TimeoutError("Connection timed out. Open Manual connection on your phone and try again.")
                self.stop.wait(.6)
        self.folder_value = self.folder.get()
        self.background(work)

    def reconnect(self):
        if self.busy or not self.saved:
            return
        self.show_setup(False)
        self.folder_value = self.folder.get()
        self.receiver = Receiver(self.saved["origin"])
        self.receiver.restore(self.saved["token"])
        self.background(self.listen)

    def forget(self, dialog=None):
        if not self.saved:
            return
        if not messagebox.askyesno("Forget phone?", "You will need to connect again. Also use Forget Chromebook on your phone to revoke its saved access.", parent=dialog or self.root):
            return
        if dialog:
            dialog.destroy()
        def clear():
            self.saved = {}
            self.selected = None
            settings_path().unlink(missing_ok=True)
            self.begin_setup()
        if self.busy:
            self.next_action = clear
            self.stop.set()
        else:
            clear()

    def listen(self):
        while not self.stop.is_set():
            try:
                state, name, size = self.receiver.status()
                if state == "ready" and self.receiver.offer != self.saved.get("last_offer"):
                    if not self.receiver.offer:
                        raise ValueError("Update the phone app to use automatic receiving.")
                    self.receiver.expected_offer = self.receiver.offer
                    self.selected = (name, size, self.folder_value)
                    self.messages.put(("receiving", name))
                    self.download()
                elif state == "ready":
                    self.receiver.acknowledge(self.receiver.offer)
                    self.messages.put(("connection", "connected"))
                elif state == "waiting":
                    self.messages.put(("status", "Approve this receiver on your phone."))
                else:
                    self.messages.put(("connection", "connected"))
            except urllib.error.HTTPError as error:
                if error.code == 403:
                    raise ValueError("Phone access was removed. Open Settings → Connect another phone to reconnect.") from error
                if error.code == 412:
                    self.selected = None
                    self.messages.put(("connection", "connected"))
                else:
                    self.messages.put(("connection", "waiting"))
            except (OSError, ConnectionError) as error:
                if getattr(error, "errno", None) in (errno.ENOSPC, errno.EACCES, errno.EROFS):
                    raise ValueError("Couldn’t save this file. Free some space or choose a writable save folder in Settings.") from error
                self.messages.put(("connection", "waiting"))
            self.stop.wait(1)

    def download(self):
        name, size, folder = self.selected
        result = self.receiver.download(name, size, folder, self.stop,
            lambda done, total, speed: self.messages.put(("progress", done, total, speed)))
        if result:
            self.selected = None
            self.saved["last_offer"] = self.receiver.expected_offer
            self.saved["folder"] = folder
            self.saved["last_file"] = str(result)
            save_settings(self.saved)
            self.messages.put(("received", str(result)))
            self.receiver.acknowledge(self.receiver.expected_offer)

    def resume(self):
        if not self.busy and self.saved:
            self.reconnect()

    def pause(self):
        self.stop.set()
        self.heading.set("Pausing…")
        self.status_text.set("Your partial download is kept for Resume.")

    def close(self):
        self.closing = True
        self.stop.set()
        if self.busy:
            self.heading.set("Closing safely…")
            self.status_text.set("Keeping any partial download so you can resume later.")
        else:
            self.root.destroy()

    def poll(self):
        try:
            while True:
                message = self.messages.get_nowait()
                kind = message[0]
                if kind == "status":
                    self.status_text.set(message[1])
                elif kind == "connection":
                    connected = message[1] == "connected"
                    self.badge.set("CONNECTED" if connected else "RECONNECTING")
                    self.heading.set("Ready to receive." if connected else "Waiting for your phone…")
                    self.status_text.set("Choose a file on your phone, then tap Send to Chromebook." if connected else "Open QuickDrop on your phone and use the same Wi-Fi or hotspot. Address changed? Update it in Settings.")
                    if connected:
                        self.transfer_panel.pack_forget()
                elif kind == "pairing":
                    self.show_setup(False)
                    self.badge.set("CHECK BOTH SCREENS")
                    self.heading.set(message[1])
                    self.status_text.set("Does your phone show this code? Tap Yes, connect on your phone.")
                elif kind == "paired":
                    self.qr_ticket = None
                    self.show_setup(False)
                    self.address.set("")
                elif kind == "receiving":
                    self.badge.set("RECEIVING")
                    self.heading.set("On its way.")
                    self.status_text.set("Keep QuickDrop open on your phone.")
                    self.receipt_panel.pack_forget()
                    self.current_name.set(message[1][:110] + ("…" if len(message[1]) > 110 else ""))
                    self.bar["value"] = 0
                    self.transfer_panel.pack(fill="x", pady=(0, 12), before=self.destination)
                elif kind == "received":
                    self.last_path = message[1]
                    self.receipt_name.set(Path(self.last_path).name[:110])
                    self.receipt_panel.pack(fill="x", pady=(0, 12), before=self.destination)
                    self.transfer_panel.pack_forget()
                    self.badge.set("SAVED")
                    self.heading.set("Your file is here.")
                    self.status_text.set("Saved automatically. You’re ready for the next one.")
                elif kind == "qr_status":
                    self.qr_status.set(message[1])
                elif kind == "qr_refresh":
                    if not self.next_action and not self.closing:
                        self.next_action = self.start_qr
                elif kind == "error":
                    self.badge.set("LET’S FIX THIS")
                    self.heading.set("A little help needed.")
                    self.status_text.set(message[1])
                    self.qr_status.set(message[1])
                    if self.setup_mode and not self.manual_mode:
                        self.qr_canvas.delete("all")
                        side = int(self.qr_canvas.cget("width"))
                        self.qr_canvas.create_text(side/2, side/2, text="Connection not completed\nRefresh QR to retry", justify="center", fill="#637168", font=("DejaVu Sans", 12))
                elif kind == "progress":
                    _, done, total, speed = message
                    self.bar["value"] = done * 100 / total
                    seconds = int((total-done)/speed) if speed > 0 else 0
                    self.progress_text.set(f"{done * 100 / total:.0f}% · {speed/1e6:.1f} MB/s · {seconds}s left")
                elif kind == "idle":
                    self.busy = False
                    self.pair_button.configure(state="normal")
                    self.transfer.configure(text="Resume receiving", command=self.resume,
                        state="normal" if self.saved else "disabled")
                    if self.stop.is_set() and not self.closing:
                        self.badge.set("PAUSED")
                        self.heading.set("Receiving paused.")
                        self.status_text.set("Press Resume receiving when you’re ready.")
                    if not self.saved:
                        self.show_setup(True)
                    if self.next_action:
                        action, self.next_action = self.next_action, None
                        self.root.after(0, action)
        except queue.Empty:
            pass
        if self.closing and not self.busy:
            self.root.destroy()
            return
        self.root.after(100, self.poll)


if __name__ == "__main__":
    settings_path().parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = (settings_path().parent / "receiver.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit("QuickDrop is already running. Open its existing window.")
    App(tk.Tk(className="QuickDrop")).root.mainloop()
