# Install and send your first file

## Phone

Download `releases/v0.5.2/QuickDrop_Offline_0.5.2.apk` onto the Android phone. Open it and choose Install or Update. Android may ask you to permit installation from the app used to open the APK.

On a fresh launcher start, QuickDrop opens the scanner and asks for Camera access when needed. Back returns to file sending. The Scan laptop QR button remains visible after pairing.

## Chromebook or Linux laptop

Enable Chromebook Linux if needed. Close any previous receiver before updating. Install the DEB through the package installer or run this from the root of this project folder:

```bash
sudo apt install ./releases/v0.5.2/QuickDrop_Receiver_0.5.2_all.deb
```

Keep internet available for installing Python/Tk, QR and discovery dependencies. Open QuickDrop Receiver from the launcher, or run:

```bash
python3 /usr/share/quickdrop/receiver.py
```

## Pair and send

1. Connect the laptop and phone to the same private Wi-Fi, or connect the laptop to the phone's password-protected hotspot.
2. On the laptop, choose Connect phone to display a fresh QR.
3. Open QuickDrop on the phone, allow Camera access and scan the QR.
4. Wait for the verified connection, choose a local file and tap Send to Chromebook.
5. Check the last saved filename on the laptop. Open save folder shows its destination.

Mobile data can stay off during local transfers. QR scanning does not connect either device to Wi-Fi. Keep both apps open; the receiver can be minimized.

If ChromeOS Downloads is shared with Linux, the receiver uses a QuickDrop subfolder there by default. Otherwise it uses Linux files / Downloads / QuickDrop. Existing saved folder choices are retained.

## If connection fails

The screen distinguishes searching, phone found and verification. A discovered-but-unreachable phone produces an error after about 12 seconds; the phone ends an unsuccessful attempt after 20 seconds. Refresh and scan a current QR after checking the shared network. Guest network isolation or routing policies can prevent local discovery.

Settings on both devices contains a manual address/code fallback. The Android and Linux READMEs describe further behavior and limitations.
