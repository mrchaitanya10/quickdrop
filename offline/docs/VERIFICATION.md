# Verification record for the preserved v0.5.2 snapshot

No new application build was produced to create this showcase package. The APK and DEB were copied byte for byte from the preserved outputs. The manifest and SHA256SUMS identify those exact files.

## Completed software checks

| Check | Recorded result |
| --- | --- |
| Android compilation | Passed with SDK 35; deprecation/resource warnings remain |
| APK signature | Verified; update signing identity matches earlier previews |
| APK metadata | Version 0.5.2, versionCode 7, minimum API 26 |
| DEB metadata | quickdrop-receiver 0.5.2, architecture all |
| Streamed transfer | 32 MiB synthetic payload with matching SHA-256 |
| Resume and filenames | Partial resume, changed-offer restart, duplicate preservation and pause passed |
| Saved pairing | Credentials survive a server restart in the integration fixture |
| Continuous receiving | Multiple offers received without repeatedly saving one polled offer |
| QR authentication | Invalid proof, stale/closed ticket and replay rejection passed |
| QR transfer | 3 MiB checksummed transfer and saved-file receipt passed |
| QR decoding | Receiver-generated QR decoded with the scanner's ZXing/YUV pipeline |
| Discovery | Loopback multicast DNS fixture passed |
| Slow address | Valid address paired without waiting for a simulated three-second dead address |
| Blocked address | Explicit bounded failure; existing credentials retained |

The supplied tests and run instructions are in `tests/README.txt`. Earlier desktop layout inspections covered the 620x650 receiver and QR screen. These observations do not substitute for a complete physical-device matrix for this exact snapshot.

## Limits of the evidence

The user reported a working flow during development. Physical Android camera/NSD behavior, Chromebook multicast forwarding, every hotspot/router combination, Android background behavior and a full 10 GB native transfer have not been independently verified end to end. Windows support is not packaged.

Fixture timings are not a promise of a particular connection or transfer speed on real devices. Do not describe this preview as production hardened or end-to-end encrypted.
