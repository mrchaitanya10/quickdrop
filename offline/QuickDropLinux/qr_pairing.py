"""Offline QR pairing: optical ticket, mDNS discovery, mutually checked proofs."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import ipaddress
import queue
import re
import secrets
import time
import urllib.request

SERVICE_TYPE = "_quickdrop._tcp.local."


def new_ticket():
    return secrets.token_hex(32)


def validate_ticket(ticket):
    if not isinstance(ticket, str) or not re.fullmatch(r"[a-f0-9]{64}", ticket):
        raise ValueError("Invalid QuickDrop pairing ticket.")
    return ticket


def ticket_id(ticket):
    return hashlib.sha256(validate_ticket(ticket).encode("ascii")).hexdigest()[:16]


def payload(ticket):
    return "quickdrop://pair?v=1&t=" + validate_ticket(ticket)


def proof(ticket, message):
    return hmac.new(bytes.fromhex(validate_ticket(ticket)), message.encode("ascii"), hashlib.sha256).hexdigest()


def checked_challenge(receiver, ticket, timeout=1.2):
    identity = ticket_id(ticket)
    with receiver.opener.open(receiver.origin + "/qr-challenge/" + identity, timeout=timeout) as response:
        fields = response.read(1024).decode("ascii").splitlines()
    if len(fields) != 4 or fields[:2] != ["1", identity] or not re.fullmatch(r"[a-f0-9]{32}", fields[2]):
        raise ValueError("Unexpected QR challenge.")
    if not hmac.compare_digest(fields[3], proof(ticket, "server:" + fields[2])):
        raise ValueError("The phone did not scan this QR ticket.")
    return fields[2]


def pair_receiver(receiver, ticket, timeout=2, challenge=None):
    """Only the selected reachable address performs the single-use pairing POST."""
    identity = ticket_id(ticket)
    challenge = challenge or checked_challenge(receiver, ticket, timeout=timeout)
    nonce = secrets.token_hex(16)
    data = "\n".join([identity, nonce, proof(ticket, "client:" + challenge + ":" + nonce)]).encode("ascii")
    request = urllib.request.Request(receiver.origin + "/qr-pair", data=data,
        headers={"Origin": receiver.origin, "Content-Type": "text/plain"})
    with receiver.opener.open(request, timeout=timeout) as response:
        confirmation = response.read(1024).decode("ascii").splitlines()
    token = receiver.token()
    if (not token or not re.fullmatch(r"[a-f0-9]{48}", token) or len(confirmation) != 2
            or confirmation[0] != identity
            or not hmac.compare_digest(confirmation[1], proof(ticket, "paired:" + nonce + ":" + token))):
        raise ValueError("Couldn’t verify the paired phone.")
    return receiver


def probe_origin(origin, ticket, receiver_class):
    client = receiver_class(origin)
    try:
        return client, checked_challenge(client, ticket)
    except (OSError, ValueError):
        return None


def service_origins(info):
    from zeroconf import IPVersion
    if not info or not info.port or not 1 <= info.port <= 65535:
        return []
    advertised = info.properties.get(b"hosts", b"")
    if isinstance(advertised, bytes):
        advertised = advertised.decode("ascii", errors="ignore")
    candidates = advertised.split(",")[:4] + info.parsed_addresses(IPVersion.V4Only)[:8]
    origins = []
    for host in candidates:
        try:
            ip = ipaddress.ip_address(host)
            if not isinstance(ip, ipaddress.IPv4Address) or not ip.is_private or ip.is_unspecified or ip.is_multicast:
                continue
        except ValueError:
            continue
        origin = f"http://{ip}:{info.port}"
        if origin not in origins:
            origins.append(origin)
    return origins


def discover_receiver(ticket, receiver_class, stop, timeout=120, interfaces=None, on_progress=None, connect_timeout=12):
    from zeroconf import IPVersion, ServiceBrowser, ServiceStateChange, Zeroconf
    names = queue.Queue(maxsize=32)
    events = queue.Queue()
    identity = "quickdrop-" + ticket_id(ticket)
    def progress(stage, text):
        if on_progress:
            on_progress(stage, text)
    def found(zeroconf, service_type, name, state_change):
        if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated) and name.lower().startswith(identity):
            try:
                names.put_nowait(name)
            except queue.Full:
                pass
    options = {"ip_version": IPVersion.V4Only}
    if interfaces is not None:
        options["interfaces"] = interfaces
    zc = Zeroconf(**options)
    resolvers = ThreadPoolExecutor(max_workers=2)
    probes = ThreadPoolExecutor(max_workers=4)
    browser = None
    started = time.monotonic()
    deadline = started + timeout
    first_seen = None
    warned = False
    candidates, pending_names, attempts, pending_origins = {}, set(), {}, set()
    def resolve(name):
        try:
            info = zc.get_service_info(SERVICE_TYPE, name, timeout=700)
            events.put(("resolved", name, service_origins(info)))
        except Exception:
            events.put(("resolved", name, []))
    def probe(origin):
        result = probe_origin(origin, ticket, receiver_class)
        events.put(("probed", origin, result))
    try:
        progress("searching", "Waiting for your phone to scan this QR.")
        browser = ServiceBrowser(zc, SERVICE_TYPE, handlers=[found])
        while not stop.is_set() and time.monotonic() < deadline:
            now = time.monotonic()
            while True:
                try:
                    name = names.get_nowait()
                except queue.Empty:
                    break
                if name not in candidates and len(candidates) < 16:
                    candidates[name] = 0
                    if first_seen is None:
                        first_seen = now
                        progress("found", "Phone found. Checking the local connection…")
            for name, attempted in list(candidates.items()):
                if name not in pending_names and now-attempted >= .8:
                    candidates[name] = now
                    pending_names.add(name)
                    resolvers.submit(resolve, name)
            while True:
                try:
                    kind, target, value = events.get_nowait()
                except queue.Empty:
                    break
                if kind == "resolved":
                    pending_names.discard(target)
                    for origin in value:
                        if origin not in pending_origins and now-attempts.get(origin, 0) >= .8:
                            if len(pending_origins) >= 8:
                                break
                            attempts[origin] = now
                            pending_origins.add(origin)
                            probes.submit(probe, origin)
                else:
                    pending_origins.discard(target)
                    if value is not None:
                        client, challenge = value
                        progress("verifying", "Phone reached. Verifying the scanned QR…")
                        try:
                            pair_receiver(client, ticket, challenge=challenge)
                            progress("connected", "Connected. Ready to receive.")
                            return client
                        except (OSError, ValueError):
                            progress("retrying", "QR verification didn’t finish. Retrying the local connection…")
            if first_seen is not None and now-first_seen >= min(connect_timeout, timeout):
                raise ValueError("Phone found, but the local connection did not finish. Check that both devices use the same private Wi-Fi or hotspot, then refresh the QR. Guest-network isolation or a VPN may block it.")
            if first_seen is None and now-started > 15 and not warned:
                warned = True
                progress("not_found", "Already scanned? Your phone has not appeared on this network. Check the shared Wi-Fi/hotspot and keep both apps open.")
            stop.wait(.04)
        return None
    finally:
        if browser:
            browser.cancel()
        # Probe sockets have bounded timeouts; do not wait for a dead address
        # after another address has already paired successfully.
        probes.shutdown(wait=False, cancel_futures=True)
        resolvers.shutdown(wait=True, cancel_futures=True)
        zc.close()


def qr_matrix(ticket):
    import qrcode
    code = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    code.add_data(payload(ticket), optimize=0)
    code.make(fit=True)
    return code.get_matrix()


def draw_qr(canvas, ticket, maximum=250):
    matrix = qr_matrix(ticket)
    scale = max(1, maximum // len(matrix))
    side = len(matrix) * scale
    canvas.configure(width=side, height=side, background="white")
    canvas.delete("all")
    for y, row in enumerate(matrix):
        for x, black in enumerate(row):
            if black:
                canvas.create_rectangle(x*scale, y*scale, (x+1)*scale, (y+1)*scale, fill="#111827", outline="")
