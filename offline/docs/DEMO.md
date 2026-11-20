# A short project demonstration

Use a small, non-sensitive file created for the demo, such as a photo or a PDF titled QuickDrop Demo. Clear unrelated notifications and keep both devices on a trusted private network.

1. Show the laptop receiver and click Connect phone.
2. Open the Android app and scan the laptop QR.
3. Show the connection status on both devices.
4. Choose the demo file and tap Send to Chromebook.
5. Show the last saved filename and open the save folder.
6. Explain that the receiver confirms saving before the phone displays Saved.

For a technical discussion, explain why the phone hosts the server, how QR proofs and mDNS play different roles, how range requests resume a matching offer, and why a saved-file receipt matters. Refer to the code when discussing implementation details.

Accurate portfolio description:

> QuickDrop is a C Labs Android-to-Linux file-sharing project with QR pairing, local streaming, resumable downloads and automatic receiver saving. I developed it through an AI-assisted workflow and iterated on connection usability and verification.

Describe the 10 GB value as the enforced file-size cap, and describe security as QR-authenticated pairing over unencrypted local HTTP. The verification record lists what was exercised.
