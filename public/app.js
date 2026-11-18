const element = id => document.getElementById(id);
const connection = element('connection');
const controls = element('send-controls');
const fileInput = element('file');
const message = element('message');
const sendFile = element('send-file');
const sendText = element('send-text');
const feedback = element('feedback');
const uploadProgress = element('upload-progress');
const cancelUpload = element('cancel-upload');
const fileLimit = 10_000_000_000;
const qr = element('qr');
const placeholder = element('qr-placeholder');
const copyLink = element('copy-link');
const restart = element('restart');
const inbox = element('inbox');
const token = new URLSearchParams(location.hash.slice(1)).get('join');
const local = location.hostname === 'localhost' || /^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(location.hostname);
let link;
let paired = false;
let sending = false;
let ended = false;
let uploadKey;
let socket;
let reconnectTimer;
let reconnectDeadline;
let activeUpload;
let receivedFile;
let receivedFileId;

function status(label, ready = false) {
  connection.lastChild.textContent = ` ${label}`;
  connection.dataset.ready = String(ready);
}

function notice(text, isError = false) {
  feedback.textContent = text;
  feedback.dataset.error = String(isError);
}

function refreshControls() {
  controls.disabled = !paired;
  sendFile.disabled = sending || !fileInput.files.length;
  sendText.disabled = sending || !message.value.trim();
}

function finish(text) {
  ended = true;
  clearTimeout(reconnectTimer);
  uploadKey = undefined;
  activeUpload?.abort();
  removeFile(receivedFileId);
  paired = false;
  sending = false;
  status('Disconnected');
  refreshControls();
  qr.hidden = true;
  copyLink.hidden = true;
  restart.hidden = false;
  placeholder.hidden = false;
  placeholder.textContent = 'Session closed';
  element('pair-help').textContent = 'Start a new session to reconnect.';
  notice(text, true);
}

function receive(row) {
  element('empty').hidden = true;
  inbox.prepend(row);
  if (inbox.children.length > 12) {
    const last = inbox.lastElementChild;
    const oldestText = last === receivedFile ? last.previousElementSibling : last;
    oldestText.remove();
  }
}

function removeFile(id) {
  if (!receivedFile || id !== receivedFileId) return;
  receivedFile.remove();
  receivedFile = undefined;
  receivedFileId = undefined;
  element('empty').hidden = inbox.children.length > 0;
}

function receiveFile({ id, name }) {
  removeFile(receivedFileId);
  const row = document.createElement('li');
  const label = document.createElement('strong');
  label.textContent = name;
  const download = document.createElement('a');
  download.textContent = 'Download';
  download.className = 'download';
  download.href = `/download/${id}`;
  download.download = name;
  row.append(label, download);
  receivedFile = row;
  receivedFileId = id;
  receive(row);
}

async function copy(value) {
  if (navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(value);
      return true;
    } catch { /* Offer a manual way to copy if clipboard permission is denied. */ }
  }
  window.prompt('Select and copy', value);
  return false;
}

const url = new URL('/connect', location.href);
url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
function connect() {
  if (ended) return;
  socket = new WebSocket(url);

socket.addEventListener('open', () => {
  if (uploadKey) {
    status('Reconnecting');
    socket.send(JSON.stringify({ type: 'resume', key: uploadKey }));
    return;
  }
  if (token) {
    status('Joining');
    placeholder.textContent = 'Connecting to the other device…';
    history.replaceState(null, '', location.pathname + location.search);
  } else {
    status('Waiting to pair');
    if (!local) element('pair-help').textContent = 'Scan this code or open the pairing link on your other device.';
  }
  socket.send(JSON.stringify(token ? { type: 'join', token } : { type: 'create' }));
});

socket.addEventListener('close', () => {
  if (ended) return;
  if (!uploadKey) return finish('Connection closed. Start a new session.');
  paired = false;
  refreshControls();
  status('Reconnecting');
  notice('Connection interrupted. Reconnecting to your paired device…');
  reconnectDeadline ||= Date.now() + 90_000;
  if (Date.now() >= reconnectDeadline) return finish('Could not reconnect. Start a new session and scan its QR code.');
  reconnectTimer = setTimeout(connect, 1500);
});
socket.addEventListener('message', ({ data }) => {
  const packet = JSON.parse(data);
  switch (packet.type) {
    case 'created':
      link = packet.link;
      if (packet.localOnly) {
        element('pair-help').textContent = "For a phone connection, open QuickDrop using your laptop’s Wi-Fi IP address, then scan the new code.";
      }
      qr.src = packet.qr;
      qr.hidden = false;
      placeholder.hidden = true;
      copyLink.hidden = false;
      break;
    case 'peer-away':
      paired = false;
      refreshControls();
      status('Waiting for device');
      notice('Your other device disconnected. Keep this tab open while it reconnects.');
      break;
    case 'paired':
      reconnectDeadline = undefined;
      uploadKey = packet.uploadKey;
      paired = true;
      refreshControls();
      status('Paired', true);
      element('pair-help').textContent = 'Your other device is connected.';
      qr.hidden = true;
      placeholder.hidden = false;
      placeholder.textContent = 'Ready to transfer';
      copyLink.hidden = true;
      restart.hidden = false;
      notice('Choose a file or enter text to send.');
      break;
    case 'text': {
      const row = document.createElement('li');
      const content = document.createElement('p');
      content.textContent = packet.text;
      const button = document.createElement('button');
      button.className = 'secondary';
      button.textContent = 'Copy text';
      button.addEventListener('click', async () => {
        if (await copy(packet.text)) button.textContent = 'Copied';
      });
      row.append(content, button);
      receive(row);
      break;
    }
    case 'file':
      receiveFile(packet);
      break;
    case 'file-removed':
      removeFile(packet.id);
      break;
    case 'sent':
      sending = false;
      message.value = '';
      refreshControls();
      notice('Text sent.');
      break;
    case 'error':
      if (!activeUpload) sending = false;
      refreshControls();
      if (!paired) finish(packet.message);
      else notice(packet.message, true);
      break;
    case 'ended':
      finish(packet.message);
      break;
  }
});

}
connect();
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !ended && socket.readyState === WebSocket.CLOSED) {
    clearTimeout(reconnectTimer);
    connect();
  }
});

fileInput.addEventListener('change', () => {
  element('file-name').textContent = fileInput.files[0]?.name || 'Photos, PDFs, and other files';
  refreshControls();
});
message.addEventListener('input', refreshControls);
sendText.addEventListener('click', () => {
  if (!paired || sending || socket.readyState !== WebSocket.OPEN) return;
  sending = true;
  refreshControls();
  socket.send(JSON.stringify({ type: 'text', text: message.value }));
});
sendFile.addEventListener('click', () => {
  const file = fileInput.files[0];
  if (!paired || sending || !file || socket.readyState !== WebSocket.OPEN) return;
  if (file.size < 1 || file.size > fileLimit) return notice('Choose a file between 1 byte and 10 GB.', true);

  const upload = new XMLHttpRequest();
  upload.open('PUT', '/upload');
  upload.setRequestHeader('X-QuickDrop-Key', uploadKey);
  upload.setRequestHeader('X-QuickDrop-Name', encodeURIComponent(file.name));
  upload.upload.addEventListener('progress', ({ loaded, total, lengthComputable }) => {
    if (lengthComputable && total > 0) uploadProgress.value = loaded / total;
  });
  upload.addEventListener('loadend', () => {
    activeUpload = undefined;
    sending = false;
    uploadProgress.hidden = true;
    cancelUpload.hidden = true;
    if (upload.status === 201) {
      fileInput.value = '';
      element('file-name').textContent = 'Photos, PDFs, and other files';
      notice('File ready on the other device.');
    } else if (!ended && upload.status === 403) {
      finish('Pairing expired. Start a new session on your laptop and scan the new QR code.');
    } else if (!ended && upload.status > 0) {
      notice(upload.responseText || 'Upload failed. Try again.', true);
    }
    refreshControls();
  });
  upload.addEventListener('error', () => {
    if (!ended) notice('Upload interrupted. Check both connections and try again.', true);
  });
  upload.addEventListener('abort', () => {
    if (!ended) notice('Upload canceled.');
  });

  activeUpload = upload;
  sending = true;
  uploadProgress.value = 0;
  uploadProgress.hidden = false;
  cancelUpload.hidden = false;
  refreshControls();
  notice('Uploading. Keep both devices connected.');
  upload.send(file);
});
cancelUpload.addEventListener('click', () => activeUpload?.abort());
copyLink.addEventListener('click', async () => {
  if (await copy(link)) notice('Pairing link copied.');
});
restart.addEventListener('click', () => location.replace(location.pathname));
