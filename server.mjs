import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { networkInterfaces } from 'node:os';
import { randomBytes } from 'node:crypto';
import QRCode from 'qrcode';
import { WebSocket, WebSocketServer } from 'ws';
import { createTransfers } from './transfers.mjs';

const lifetime = 20 * 60_000;
const rooms = new Map();
const loopbackHosts = new Set(['localhost', '127.0.0.1', '[::1]']);
let lanAddress;
try {
  lanAddress = Object.values(networkInterfaces()).flat()
    .find(address => address?.family === 'IPv4' && !address.internal)?.address;
} catch {
  // Some environments restrict interface enumeration. Their public URL still works.
}
const paths = [
  ['/', 'index.html', 'text/html'],
  ['/app.js', 'app.js', 'text/javascript'],
  ['/style.css', 'style.css', 'text/css'],
  ['/favicon.ico', 'favicon.ico', 'image/x-icon'],
  ['/apple-touch-icon.png', 'apple-touch-icon.png', 'image/png'],
  ['/site.webmanifest', 'site.webmanifest', 'application/manifest+json'],
  ['/brand/quickdrop-wordmark.png', 'brand/quickdrop-wordmark.png', 'image/png'],
  ['/brand/icon-192.png', 'brand/icon-192.png', 'image/png'],
  ['/brand/icon-512.png', 'brand/icon-512.png', 'image/png'],
];
const assets = new Map(await Promise.all(paths.map(async ([path, file, type]) =>
  [path, [await readFile(new URL(`./public/${file}`, import.meta.url)), type]])));

const send = (socket, message) => {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
};
const error = (socket, message) => send(socket, { type: 'error', message });
const touch = room => { room.expires = Date.now() + lifetime; };
const transfers = await createTransfers({ send, touch });

const server = createServer((req, res) => {
  const path = req.url?.split('?')[0];
  if (req.method === 'PUT' && path === '/upload') {
    void transfers.upload(req, res);
    return;
  }
  const download = path?.match(/^\/download\/([a-zA-Z0-9_-]{32})$/);
  if (req.method === 'GET' && download) {
    void transfers.download(req, res, download[1]);
    return;
  }
  const asset = assets.get(path);
  if (req.method !== 'GET' || !asset) {
    res.writeHead(404).end();
    return;
  }
  const [content, type] = asset;
  res.writeHead(200, {
    'Content-Type': type.startsWith('image/') ? type : `${type}; charset=utf-8`,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'",
  }).end(content);
});
server.requestTimeout = 0;
server.timeout = 120_000;
const sockets = new WebSocketServer({ noServer: true, maxPayload: 32_768 });

server.on('upgrade', (req, connection, head) => {
  let origin;
  try {
    origin = new URL(req.headers.origin);
  } catch {
    connection.destroy();
    return;
  }
  if (req.url !== '/connect' || origin.host !== req.headers.host || !['https:', 'http:'].includes(origin.protocol)) {
    connection.destroy();
    return;
  }
  sockets.handleUpgrade(req, connection, head, socket => sockets.emit('connection', socket, origin));
});

sockets.on('connection', (socket, origin) => {
  let room;
  let creating = false;
  socket.alive = true;
  socket.on('pong', () => { socket.alive = true; });
  socket.on('error', () => socket.terminate());

  socket.on('message', async (data, binary) => {
    if (binary) return error(socket, 'Use the file upload button to send files.');

    let packet;
    try { packet = JSON.parse(data); } catch { return error(socket, 'Could not read that message.'); }
    if (!packet || typeof packet !== 'object') return error(socket, 'Could not read that message.');

    if (packet.type === 'create' && !room && !creating) {
      if (rooms.size >= 200) return error(socket, 'QuickDrop is busy. Try again shortly.');
      creating = true;
      const token = randomBytes(18).toString('base64url');
      const link = new URL(origin);
      if (loopbackHosts.has(link.hostname) && lanAddress) link.hostname = lanAddress;
      link.hash = `join=${token}`;
      try {
        const qr = await QRCode.toDataURL(link.href, { width: 384, margin: 2, errorCorrectionLevel: 'M' });
        if (socket.readyState !== WebSocket.OPEN) return;
        room = { token, host: socket, peer: null, expires: Date.now() + lifetime };
        rooms.set(token, room);
        send(socket, { type: 'created', link: link.href, qr, localOnly: loopbackHosts.has(link.hostname) });
      } catch {
        error(socket, 'Could not generate a pairing code. Try again.');
      } finally {
        creating = false;
      }
      return;
    }

    if (packet.type === 'join' && !room && !creating) {
      const candidate = typeof packet.token === 'string' && rooms.get(packet.token);
      if (!candidate || candidate.expires <= Date.now()) return error(socket, 'This pairing link expired. Start a new session.');
      if (candidate.peer) return error(socket, 'This session already has two devices.');
      room = candidate;
      room.peer = socket;
      touch(room);
      send(room.host, { type: 'paired', uploadKey: transfers.authorize(room, room.host, socket) });
      send(socket, { type: 'paired', uploadKey: transfers.authorize(room, socket, room.host) });
      return;
    }

    if (packet.type === 'text') {
      if (!room?.peer) return error(socket, 'Pair another device first.');
      if (typeof packet.text !== 'string' || !packet.text.trim() || packet.text.length > 5000) {
        return error(socket, 'Enter up to 5,000 characters.');
      }
      const target = room.host === socket ? room.peer : room.host;
      if (target.readyState !== WebSocket.OPEN) return error(socket, 'The other device disconnected.');
      touch(room);
      send(target, { type: 'text', text: packet.text });
      send(socket, { type: 'sent', kind: 'text' });
    }
  });

  socket.on('close', () => {
    if (!room || rooms.get(room.token) !== room) return;
    rooms.delete(room.token);
    transfers.closeRoom(room);
    const other = room.host === socket ? room.peer : room.host;
    send(other, { type: 'ended', message: 'The other device disconnected.' });
    other?.close();
  });
});

const heartbeat = setInterval(() => {
  for (const socket of sockets.clients) {
    if (!socket.alive) socket.terminate();
    else {
      socket.alive = false;
      socket.ping();
    }
  }
  for (const room of rooms.values()) {
    if (room.upload || room.file?.download || room.expires > Date.now()) continue;
    rooms.delete(room.token);
    transfers.closeRoom(room);
    for (const socket of [room.host, room.peer]) {
      send(socket, { type: 'ended', message: 'This session expired.' });
      socket?.close();
    }
  }
}, 20_000);
heartbeat.unref();
const port = Number(process.env.PORT || 3000);
server.listen(port, '0.0.0.0', () => console.log(`QuickDrop is ready at http://localhost:${server.address().port}`));

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, async () => {
    clearInterval(heartbeat);
    for (const room of rooms.values()) transfers.closeRoom(room);
    rooms.clear();
    server.closeAllConnections();
    for (const socket of sockets.clients) socket.terminate();
    server.close();
    await transfers.cleanup();
  });
}
