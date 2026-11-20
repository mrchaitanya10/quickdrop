# QuickDrop Android 0.5.2

Use with Linux receiver 0.5.2 for parallel connection checks and visible pairing
stages. A fresh app-launcher start opens the camera scanner, asking Camera
permission first when needed. The Scan laptop QR button is always visible.
Back returns to the sending screen; scanner/file-picker returns do not loop.
Android Share entry retains the file-sending flow.

The QR ticket, authentication and file format remain compatible with 0.5.
This update adds local IPv4 hints to the Android NSD advertisement, shows when
the laptop reaches the phone, and ends an unsuccessful connection attempt
with a clear message after 20 seconds. Progress is directly under the Scan
button. The Linux update checks addresses concurrently and reports a blocked
connection after about 12 seconds from discovering the phone. These changes
cannot remove blocked multicast, network isolation or routing restrictions.

Camera frames are decoded on-device with ZXing and are not saved or uploaded.
Only QuickDrop pairing payloads are accepted; scanned web URLs are not opened.
The phone advertises the ticket's derived identifier, not the raw ticket.
Files remain local HTTP without TLS or end-to-end encryption. Use a trusted
private Wi-Fi or hotspot. Keep both apps open. See ../QuickDropLinux/README.txt
for protocol, usage, privacy, limits and validation details.

## Build and verification

Package in.theclabs.quickdrop; Android 8+ (minimum API 26), compile/target SDK
35, versionCode 7, versionName 0.5.2. Permissions: CAMERA, INTERNET and
ACCESS_NETWORK_STATE. The camera is optional for the manual connection path.

Use Android Studio, SDK 35, JDK 17+ and Gradle 8.9 with AGP 8.7.3. Run
`gradle :app:assembleDebug`. Runtime dependency: ZXing core 3.5.3.
The archive excludes signing keys, downloaded build tools and local settings.
The delivered APK was assembled with AAPT2, ECJ, D8 and APKSigner and retains
the previous preview's debug signing identity.

Software tests cover authentication, replay rejection, checksummed streaming,
resume, receipts, multicast discovery and a simulated slow/blocked address.
The APK compiles and its signature is verified. Physical Android NSD/camera,
Chromebook networking and the user's actual connection time need device checks.
