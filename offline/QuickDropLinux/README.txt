QuickDrop 0.5.2 — C Labs QR preview

INSTALL / UPDATE
Close QuickDrop Receiver before updating. Install QuickDrop_Receiver_0.5.2_all.deb
in Chromebook Linux, and update the phone with the matching 0.5.2 Android APK.
The receiver now depends on python3-qrcode and python3-zeroconf as well as
Python/Tk and xdg-utils. Internet may be needed once for these packages during
installation. The QR pairing and local transfer protocol use no cloud service.
Saved connections and folder preferences from previous versions are retained.

SCAN TO CONNECT
1. Put both devices on the same private Wi-Fi or the phone's hotspot.
   Mobile data can remain off. QR scanning does not join a Wi-Fi network.
2. Open QuickDrop on the laptop. New installations show the QR automatically.
   If a connection is already saved, use Connect phone or Settings -> Show
   connection QR to display a new code.
3. On the phone, open QuickDrop -> Scan laptop QR. Allow Camera access and
   point the camera at the laptop's QR.
4. Keep both apps open. They discover each other locally, verify the scanned
   ticket and establish a remembered connection without typing an address.
5. Choose a local phone file -> Send to Chromebook. The receiver saves it
   automatically and shows the last saved filename and the save folder.
You can also use Android Share -> QuickDrop -> Send to Chromebook.

IF DISCOVERY DOES NOT CONNECT
QR pairing uses local multicast DNS discovery. Guest Wi-Fi, client isolation,
hotspot implementations or system policies may block it. Check that both
devices share the same private network, then refresh and scan the current QR.
Camera access is only requested when you choose Scan laptop QR. Frames are
processed locally and are never uploaded or stored by QuickDrop.

A manual fallback remains in Settings on both devices. On the phone choose
Manual connection; on Linux choose Manual connection / update address. Enter
its short address, compare the six-digit code and approve on the phone.
No internet signaling server or laptop inbound port forwarding is introduced.
The laptop still initiates the HTTP connection to the phone after discovery.
Actual discovery through the user's Chromebook/hotspot must be device-tested.

WHAT THE QR DOES
The QR contains a random 256-bit, ephemeral ticket. The phone advertises only
a SHA-256-derived identifier via DNS-SD. The raw ticket is not broadcast or sent
in the HTTP proof exchange. Both sides check HMAC-SHA256 proofs with fresh
nonces. A successful exchange consumes the phone ticket and returns a saved
receiver credential. Invalid, stale and replayed proofs are rejected. The
phone's QR ticket expires after two minutes; the laptop refreshes its code.
Only scan a QR displayed in QuickDrop on the laptop you want to connect.
Treat the displayed code as a temporary pairing credential; do not share it.
QR proof exchange is authentication; it does not encrypt later file traffic.

DAILY USE / CONTROLS
Open both apps on the same network. Saved pairing reconnects when the phone
address stays unchanged. If it changes, show a fresh QR and scan again.
The phone enables Send after it has seen the approved receiver recently.
Selecting a file alone never sends it. The phone shows Saved after receiving
confirmation that the Linux app has written and renamed the file on disk.
Pause/Resume preserves partial data and verifies the offer before continuing.
Cancel transfer on phone ends the offer. A new offer may restart a partial
file. Existing completed files are never overwritten; duplicates get suffixes.
Files up to 10,000,000,000 bytes with a known positive size are accepted.
Choose files already saved on the phone and ensure enough destination space.
Keep the phone app open and the Linux receiver running; it can be minimized.
No Android background service, automatic Linux startup, automatic hotspot
connection or guarantee of a particular large-file transfer time is provided.

SAVE LOCATION
If ChromeOS Downloads is shared with Linux, the default is Downloads/QuickDrop
under /mnt/chromeos or /mnt/shared. Otherwise it is Linux files / Downloads /
QuickDrop. The app shows the exact destination. Use Open save folder or change
the destination in Settings. Previously saved folder choices are retained.

SECURITY / PRIVACY
Files stream locally from the phone with bounded buffers and no cloud copy.
This remains an unencrypted HTTP preview, without TLS or end-to-end encryption.
Use a trusted password-protected private network. Network observers may inspect
file data and bearer credentials. A remembered credential is stored in the
phone's private preferences and ~/.config/quickdrop/receiver.json on Linux
with owner-only file permissions. Forget Chromebook on phone revokes access;
Forget phone on Linux removes the local setting. Approving/scanning for a new
remembered receiver replaces the previous receiver on that phone. The APK is
debug-signed with the previous preview's identity, not a hardened store release.

VERIFICATION
Software tests cover the shared Python/Java QR proof exchange, invalid proof,
replay and stale-ticket rejection, expiry, remembered access, a checksummed
3 MiB transfer after QR pairing, receipts and loopback multicast DNS discovery.
A QR generated by the receiver's library was decoded using the same ZXing/YUV
pipeline as the phone camera activity, including strict payload validation.
Existing 32 MiB streaming, resume, duplicate filename, multiple download,
revocation, manual pairing and receipt checks also pass. The desktop QR screen
was visually inspected at 620x650. Android compilation and APK signature were
verified. Physical phone camera/permission behavior, Android NSD on the user's
network, Chromebook multicast forwarding, and a full 10 GB phone-to-Chromebook
transfer remain device checks. Software tests do not prove these hardware paths.

0.5.2 CONNECTION CHANGES
The receiver resolves matching services in parallel and probes up to four local
phone addresses at once. Only a verified, reachable candidate performs the
single-use pairing request. An unreachable first address no longer holds up a
working one. Discovery events now show Searching, Phone found and Verifying.
The phone advertises its filtered local IPv4 hints alongside the service.

If a phone is discovered but cannot connect, the laptop reports a clear failure
after about 12 seconds rather than silently waiting for the full QR lifetime.
A phone QR attempt ends after 20 seconds if it has not connected, with a message
that distinguishes no laptop contact from unfinished verification. The phone's
progress message sits directly below the scan button. These diagnostics do not
remove guest Wi-Fi isolation, VPN routing restrictions or blocked multicast.
The QR authentication limit remains two minutes, with shorter UI attempt limits.

The latency regression test advertises a simulated three-second dead address
before a working address and checks pairing completes without waiting for it.
It also checks a discovered-but-blocked phone produces an explicit bounded
failure without consuming existing credentials. Results are software fixture
measurements, not a guaranteed time on the user's phone/Chromebook network.
