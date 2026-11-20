package in.theclabs.quickdrop;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class LocalTransferServer implements Closeable {
    public static final long MAX_SIZE = 10_000_000_000L;
    public interface Source {
        String name();
        long size();
        InputStream open() throws IOException;
    }
    public interface Listener {
        void approve(String session, String code);
        void message(String message);
        default void progress(long sent, long total) {}
        default void received(String name) {}
        default void paired(String token) {}
        default void qrContact() {}
    }
    private final ServerSocket server;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8));
    private final String secret = random(24);
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final byte[] page;
    private final Listener listener;
    private volatile Selection file;
    private volatile long pairingUntil;
    private volatile QrHandshake qrHandshake;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private static final class Selection {
        final Source source;
        final String recipient;
        final String etag = "\"" + random(16) + "\"";
        Selection(Source source, String recipient) { this.source = source; this.recipient = recipient; }
    }
    private volatile boolean running = true;
    private static final class Session {
        volatile int state;
        long expires = System.currentTimeMillis() + 30 * 60_000L;
        boolean remembered;
        String id;
    }
    public LocalTransferServer(byte[] page, Listener listener) throws IOException {
        this(page, listener, 0, null);
    }
    public LocalTransferServer(byte[] page, Listener listener, int port, String trusted) throws IOException {
        this.page = page;
        this.listener = listener;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        if (trusted != null && trusted.matches("[a-f0-9]{48}")) {
            Session saved = new Session(); saved.id = trusted; saved.state = 1;
            saved.remembered = true; saved.expires = Long.MAX_VALUE; sessions.put(trusted, saved);
        }
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    socket.setSoTimeout(15_000);
                    connections.add(socket);
                    try { workers.execute(() -> handle(socket)); }
                    catch (RejectedExecutionException busy) { connections.remove(socket); socket.close(); }
                } catch (IOException error) {
                    if (running) listener.message("Local connection interrupted. Stop and start sharing again.");
                }
            }
        }, "QuickDrop-listener").start();
    }
    public int port() { return server.getLocalPort(); }
    public String secret() { return secret; }
    public String beginQr(String ticket) { QrHandshake qr=new QrHandshake(ticket);qrHandshake=qr;return qr.id; }
    public void closeQr(){qrHandshake=null;}
    public void openPairing() {
        pairingUntil = System.currentTimeMillis() + 120_000L;
        sessions.entrySet().removeIf(entry -> entry.getValue().state != 1);
    }
    public void closePairing() { pairingUntil = 0; }
    public boolean online(String id) {
        Session session = id == null ? null : sessions.get(id);
        Long last = id == null ? null : seen.get(id);
        return session != null && session.state == 1 && last != null && System.currentTimeMillis() - last < 8_000L;
    }
    public boolean pending() { return file != null; }
    public void cancelOffer() { file = null; }
    public void select(Source source) { file = new Selection(source, null); }
    public void selectFor(Source source, String recipient) { file = new Selection(source, recipient); }
    public boolean remembered(String id) { Session s = sessions.get(id); return s != null && s.remembered; }
    public void approve(String id, boolean allowed) {
        Session session = sessions.get(id);
        if (session != null) {
            session.state = allowed ? 1 : -1;
            if (allowed && session.remembered) session.expires = Long.MAX_VALUE;
        }
    }
    private static String random(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int value = in.read();
            if (value < 0) throw new EOFException();
            if (value == 10) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            bytes.write(value);
        }
        throw new IOException("Request line too long");
    }
    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return bytes;
    }
    private static void response(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        header(out, code, type, body.length, extra);
        out.write(body);
        out.flush();
    }
    private static void header(OutputStream out, int code, String type, long length, String extra) throws IOException {
        String header = "HTTP/1.1 " + code + " Result\r\nContent-Type: " + type +
                "\r\nContent-Length: " + length + "\r\nConnection: close\r\nCache-Control: no-store" +
                "\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + extra + "\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
    }
    private static void text(OutputStream out, int code, String text) throws IOException {
        response(out, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), "");
    }
    private Session session(Map<String,String> headers) {
        for (String cookie : headers.getOrDefault("cookie", "").split(";")) {
            String[] pieces = cookie.trim().split("=", 2);
            if (pieces.length == 2 && pieces[0].equals("qd")) {
                Session session = sessions.get(pieces[1]);
                if (session != null && session.expires > System.currentTimeMillis()) return session;
            }
        }
        return null;
    }
    private void handle(Socket socket) {
        try (Socket client = socket) {
            InputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = new BufferedOutputStream(client.getOutputStream());
            String[] request = line(input).split(" ");
            if (request.length != 3) { text(output,400,"Invalid request"); return; }
            Map<String,String> headers = new HashMap<>();
            for (int count = 0; count < 40; count++) {
                String header = line(input);
                if (header.isEmpty()) break;
                int split = header.indexOf(':');
                if (split > 0) headers.put(header.substring(0,split).toLowerCase(Locale.ROOT),header.substring(split+1).trim());
                if (count == 39) throw new IOException("Too many headers");
            }
            String method = request[0], path = request[1];
            if (method.equals("GET") && path.equals("/")) {
                response(output,200,"text/html; charset=utf-8",page,
                    "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'\r\n");
                return;
            }
            if (method.equals("GET") && path.startsWith("/qr-challenge/")) {
                QrHandshake qr=qrHandshake;
                if(qr==null || !qr.active() || !path.equals("/qr-challenge/"+qr.id)) {text(output,403,"Scan a fresh laptop QR code.");return;}
                listener.qrContact();
                text(output,200,qr.challengeResponse());return;
            }
            if (method.equals("POST") && path.equals("/qr-pair")) {
                if (!("http://"+headers.get("host")).equals(headers.get("origin"))) {text(output,403,"Invalid origin");return;}
                int length;
                try{length=Integer.parseInt(headers.getOrDefault("content-length","0"));}catch(NumberFormatException error){length=0;}
                if(length<1 || length>256){text(output,400,"Invalid QR proof");return;}
                String[] fields=new String(readBytes(input,length),StandardCharsets.US_ASCII).split("\n",-1);
                String id=random(24);QrHandshake qr;
                synchronized(this) {
                    qr=qrHandshake;
                    if(fields.length!=3 || qr==null || !qr.id.equals(fields[0]) || !qr.claim(fields[1],fields[2])) {text(output,403,"QR pairing expired or invalid.");return;}
                    sessions.entrySet().removeIf(entry -> entry.getValue().remembered);
                    Session approved=new Session();approved.id=id;approved.state=1;approved.remembered=true;approved.expires=Long.MAX_VALUE;
                    sessions.put(id,approved);qrHandshake=null;
                }
                response(output,200,"text/plain",(qr.id+"\n"+qr.pairedProof(fields[1],id)).getBytes(StandardCharsets.US_ASCII),
                    "Set-Cookie: qd="+id+"; HttpOnly; SameSite=Strict; Path=/\r\n");
                listener.paired(id);return;
            }
            if (method.equals("POST") && (path.equals("/pair") || path.equals("/connect"))) {
                String origin = headers.get("origin");
                if (origin == null || !origin.equals("http://" + headers.get("host"))) {
                    text(output,403,"Open the local QuickDrop page."); return;
                }
                int size;
                try { size = Integer.parseInt(headers.getOrDefault("content-length","0")); }
                catch (NumberFormatException error) { size = 0; }
                if (size < 1 || size > 128) { text(output,403,"Invalid pairing link."); return; }
                byte[] key = readBytes(input,size);
                boolean valid = path.equals("/pair") ? MessageDigest.isEqual(key,secret.getBytes(StandardCharsets.US_ASCII))
                    : pairingUntil > System.currentTimeMillis() && MessageDigest.isEqual(key,"receiver".getBytes(StandardCharsets.US_ASCII));
                if (!valid) {
                    text(output,403,"Invalid pairing link."); return;
                }
                sessions.entrySet().removeIf(entry -> entry.getValue().expires < System.currentTimeMillis());
                if (sessions.size() >= 8) { text(output,429,"Too many pairing requests. Restart sharing."); return; }
                String id = random(24), code = String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1_000_000));
                Session pending = new Session(); pending.id = id;
                pending.remembered = "1".equals(headers.get("x-quickdrop-remember"));
                sessions.put(id,pending);
                response(output,200,"text/plain",code.getBytes(StandardCharsets.US_ASCII),
                        "Set-Cookie: qd="+id+"; HttpOnly; SameSite=Strict; Path=/\r\n");
                listener.approve(id,code);
                return;
            }
            Session access = session(headers);
            if (access == null || access.state < 0) { text(output,403,"Pairing ended. Open a fresh link from the phone."); return; }
            if (access.state == 1) seen.put(access.id, System.currentTimeMillis());
            if (method.equals("POST") && path.equals("/received") && access.state == 1) {
                if (!("http://" + headers.get("host")).equals(headers.get("origin"))) { text(output,403,"Invalid origin"); return; }
                int length;
                try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
                catch (NumberFormatException error) { length = 0; }
                if (length < 1 || length > 128) { text(output,400,"Invalid receipt"); return; }
                String offer = new String(readBytes(input,length), StandardCharsets.US_ASCII);
                Selection selected = file;
                if (selected == null || !selected.etag.equals(offer) || (selected.recipient != null && !selected.recipient.equals(access.id))) {
                    text(output,412,"Offer changed"); return;
                }
                synchronized (this) { if (file == selected) file = null; }
                listener.received(selected.source.name());
                text(output,200,"saved"); return;
            }
            if (method.equals("GET") && path.equals("/status")) {
                Selection selected = file;
                if (access.state != 1) selected = null;
                if (selected != null && selected.recipient != null && !selected.recipient.equals(access.id)) selected = null;
                String status = access.state == 0 ? "waiting" : selected == null ? "empty" : "ready";
                String details = selected == null ? "" : selected.source.name() + "\n" + selected.source.size() + "\n" + selected.etag;
                text(output,200,status+"\n"+details); return;
            }
            if (method.equals("GET") && path.equals("/file") && access.state == 1) {
                Selection selection = file;
                if (selection == null || (selection.recipient != null && !selection.recipient.equals(access.id))) { text(output,404,"Choose a file on the phone."); return; }
                if (headers.containsKey("if-match") && !selection.etag.equals(headers.get("if-match"))) {
                    text(output,412,"File offer changed."); return;
                }
                Source selected = selection.source;
                long size = selected.size();
                if (size < 1 || size > MAX_SIZE) { text(output,413,"Choose a file up to 10 GB."); return; }
                long offset = 0;
                String range = headers.get("range");
                if (range != null && selection.etag.equals(headers.get("if-range"))) {
                    try {
                        if (!range.matches("bytes=[0-9]+-")) throw new NumberFormatException();
                        offset = Long.parseLong(range.substring(6, range.length()-1));
                        if (offset >= size) throw new NumberFormatException();
                    } catch (NumberFormatException error) {
                        response(output,416,"text/plain",new byte[0],"Content-Range: bytes */"+size+"\r\n");
                        return;
                    }
                }
                try (InputStream stream = selected.open()) {
                    long skipped = 0;
                    while (skipped < offset) {
                        long count = stream.skip(offset-skipped);
                        if (count == 0) {
                            if (stream.read() < 0) throw new EOFException("File ended during resume");
                            count = 1;
                        }
                        skipped += count;
                    }
                    String filename = URLEncoder.encode(selected.name(),"UTF-8").replace("+","%20");
                    String resumeHeader = offset > 0 ? "Content-Range: bytes "+offset+"-"+(size-1)+"/"+size+"\r\n" : "";
                    header(output,offset > 0 ? 206 : 200,"application/octet-stream",size-offset,
                            "ETag: "+selection.etag+"\r\nAccept-Ranges: bytes\r\n"+resumeHeader+
                            "Content-Disposition: attachment; filename=quickdrop-file; filename*=UTF-8''"+filename+"\r\n");
                    byte[] buffer = new byte[128 * 1024];
                    long sent = offset, nextReport = offset;
                    while (sent < size && running && file == selection) {
                        int read = stream.read(buffer,0,(int)Math.min(buffer.length,size-sent));
                        if (read < 0) throw new EOFException("Source file ended early");
                        output.write(buffer,0,read);
                        sent += read;
                        if (sent >= nextReport) {
                            listener.progress(sent, size);
                            nextReport = sent + 8 * 1024 * 1024;
                        }
                    }
                    if (sent != size) throw new IOException("Transfer cancelled");
                    output.flush();
                    listener.progress(size, size);
                    listener.message("Waiting for your Chromebook to save the file…");
                }
                return;
            }
            text(output,404,"Not found");
        } catch (IOException | RuntimeException error) {
            if (running) listener.message("Connection interrupted. Keep both devices connected and retry the download.");
        } finally {
            connections.remove(socket);
        }
    }
    public void close() throws IOException {
        running = false;
        qrHandshake=null;
        sessions.clear();
        seen.clear();
        server.close();
        for (Socket socket : connections) try { socket.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
