import { randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';

const fileLimit = 10_000_000_000;
const storageLimit = fileLimit * 2;
const idleTimeout = 120_000;

function respond(res, status, message) {
  if (!res.destroyed) {
    res.writeHead(status, {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'no-store',
      ...(status >= 400 && { Connection: 'close' }),
    }).end(message);
  }
}

function readName(header) {
  try {
    const name = decodeURIComponent(header).replace(/[\\/\u0000-\u001f\u007f]/g, '_').trim();
    return name.length && name.length <= 255 ? name : null;
  } catch {
    return null;
  }
}

export async function createTransfers({ send, touch }) {
  const directory = await mkdtemp(join(process.env.QUICKDROP_TEMP_DIR || tmpdir(), 'quickdrop-'));
  const devices = new Map();
  const downloads = new Map();
  let reserved = 0;

  function authorize(room, socket, other) {
    const key = randomBytes(24).toString('base64url');
    devices.set(key, { room, role: room.host === socket ? 'host' : 'peer' });
    socket.uploadKey = key;
    return key;
  }

  function resume(key, socket) {
    const device = devices.get(key);
    if (!device || device.room.closed || device.room.expires <= Date.now()) return null;
    const { room, role } = device;
    const old = room[role];
    room[role] = socket;
    socket.uploadKey = key;
    old?.close();
    return room;
  }

  async function removeFile(file) {
    downloads.delete(file.id);
    file.download?.abort();
    try {
      await rm(file.path, { force: true });
      reserved -= file.size;
    } catch (error) {
      console.error('Could not clean up a temporary QuickDrop file:', error.code);
    }
  }

  function closeRoom(room) {
    room.closed = true;
    devices.delete(room.host.uploadKey);
    if (room.peer) devices.delete(room.peer.uploadKey);
    room.upload?.abort();
    if (room.file) void removeFile(room.file);
  }

  async function upload(req, res) {
    const device = devices.get(req.headers['x-quickdrop-key']);
    if (!device) return respond(res, 403, 'Pair another device before sending a file.');
    const { room, role } = device;
    const size = Number(req.headers['content-length']);
    if (!Number.isSafeInteger(size)) return respond(res, 411, 'QuickDrop needs the file size before the upload can start.');
    if (size < 1 || size > fileLimit) return respond(res, 413, 'Choose a file between 1 byte and 10 GB.');
    const name = readName(req.headers['x-quickdrop-name'] ?? '');
    if (!name) return respond(res, 400, 'This filename could not be read.');
    if (room.upload || room.file?.download) return respond(res, 409, 'A file is already transferring. Try again when it finishes.');
    if (reserved + size > storageLimit) return respond(res, 507, 'Temporary storage is busy. Try again when another session ends.');

    const id = randomBytes(24).toString('base64url');
    const file = { id, name, size, path: join(directory, id), room };
    const controller = new AbortController();
    room.upload = controller;
    reserved += size;
    req.setTimeout(idleTimeout, () => controller.abort());
    try {
      await pipeline(req, createWriteStream(file.path, { flags: 'wx', mode: 0o600 }), { signal: controller.signal });
      if (room.closed) throw new Error('Pairing session closed');
      const previous = room.file;
      room.file = file;
      downloads.set(id, file);
      if (previous) {
        for (const socket of [room.host, room.peer]) send(socket, { type: 'file-removed', id: previous.id });
        void removeFile(previous);
      }
      file.recipient = role === 'host' ? 'peer' : 'host';
      send(room[file.recipient], { type: 'file', id, name });
      respond(res, 201, 'File ready to download.');
    } catch {
      await removeFile(file);
      respond(res, 500, 'Upload failed. Check the network connection and available disk space, then try again.');
    } finally {
      room.upload = null;
      req.setTimeout(0);
      touch(room);
    }
  }

  async function download(req, res, id) {
    const file = downloads.get(id);
    if (!file) return respond(res, 404, 'This file is no longer available.');
    if (file.room.upload || file.download) return respond(res, 409, 'A file is already transferring. Try again when it finishes.');
    const controller = new AbortController();
    file.download = controller;
    res.setTimeout(idleTimeout, () => controller.abort());
    const encoded = encodeURIComponent(file.name).replace(/['()*]/g, char =>
      `%${char.charCodeAt(0).toString(16).toUpperCase()}`);
    res.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Length': file.size,
      'Content-Disposition': `attachment; filename="quickdrop-file"; filename*=UTF-8''${encoded}`,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    try {
      await pipeline(createReadStream(file.path), res, { signal: controller.signal });
    } catch {
      res.destroy();
    } finally {
      file.download = null;
      touch(file.room);
    }
  }

  return { authorize, resume, upload, download, closeRoom, cleanup: () => rm(directory, { recursive: true, force: true }) };
}
