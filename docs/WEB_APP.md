> These notes describe the original browser edition. Run its commands from the repository root. For the native Android/Linux preview, see [the offline project](../offline/README.md).

# QuickDrop

Send files, links, and text between a phone and a laptop through a QR pairing link.

Live preview: https://quickdrop-production-c094212e.up.railway.app

## Run the app

Install Node.js 22 or newer. Open a terminal in this folder and run:

```sh
npm ci
npm start
```

Open http://localhost:3000 on the laptop. Keep your phone on the same Wi-Fi and scan the QR code. After pairing, choose a file on either device and select **Send file**. The other device can download it from **Received**. Send a link or short note back to try the other direction.

Your phone must be able to reach the laptop over Wi-Fi. If a firewall prompt appears, allow the connection on your trusted private network. If the QR chooses the wrong network adapter, open the app on the laptop using its Wi-Fi IPv4 address in place of localhost and scan the new code.

## Large files

- Each file can be up to 10 GB (10,000,000,000 bytes). The sender sees upload progress and can cancel. The receiver downloads the file after the upload finishes.
- Keep both tabs open and connected until the download finishes. An interrupted upload starts over when retried.
- QuickDrop streams file data through temporary disk storage. The machine running QuickDrop needs enough free space to hold the upload, and the receiving device needs enough free space to save it.
- Use `QUICKDROP_TEMP_DIR` to point QuickDrop at an existing directory on a drive with enough free space. By default it uses the operating system's temporary directory. Available downloads are cleared when replaced by a new completed file or when the pairing session ends. A failed or canceled upload is discarded.

## How it works

A single Node process serves the app. WebSockets handle pairing, status, and text. HTTP requests stream uploads and downloads without building a full file in browser JavaScript or server memory. A randomly generated QR link pairs two devices. Idle sessions expire after 20 minutes. The app serves one upload or download at a time per room, with a 20 GB process-wide temporary storage budget. Use the local HTTP demo on a trusted Wi-Fi network.

A hosted deployment needs an HTTPS Node host with persistent WebSocket connections, enough temporary disk space, and a proxy that supports uploads and downloads of the planned size. Run a single instance because pairing state lives in memory. Preserve the original Host header and pass through WebSocket upgrades at the proxy. Set its request size and connection time limits to support your largest transfer. The default port is 3000, and the host can override it through `PORT`.

## Automatic deployment

The included Dockerfile packages QuickDrop for a Node container host. The Railway configuration selects that Dockerfile, one replica, a startup healthcheck, and up to three restarts after a crash.

Connect the GitHub repository to a Railway service and enable autodeploys on main. Configure storage and review the hosting price before activating a service. The current version needs enough writable temporary storage for 10 GB uploads and uses a 20 GB process-wide storage budget. It also needs the host's HTTP request duration and upload limits to accommodate the planned file size and connection speed. Review Railway's current network limits before promising 10 GB transfers on a slow connection.

The GitHub main branch is connected to Railway. The first container build and deployment succeeded, and HTTPS pairing, text, and a sample file round trip passed. A full 10 GB transfer was verified locally. Hosted 10 GB storage capacity and performance are still unverified. The current transfer uses temporary server storage. Direct encrypted peer-to-peer transfers and connection verification codes remain planned.


