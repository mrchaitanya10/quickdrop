import in.theclabs.quickdrop.LocalTransferServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;

public class ServerCheck {
    public static void main(String[] args) throws Exception {
        BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        LocalTransferServer server = new LocalTransferServer("preview".getBytes(), new LocalTransferServer.Listener() {
            public void approve(String id,String code) { requests.add(id); }
            public void message(String value) {}
        });
        try {
            String base = "http://127.0.0.1:"+server.port();
            HttpURLConnection denied = (HttpURLConnection)new URL(base+"/file").openConnection();
            if(denied.getResponseCode()!=403) throw new AssertionError("Unpaired download allowed");
            HttpURLConnection pair = (HttpURLConnection)new URL(base+"/pair").openConnection();
            pair.setRequestMethod("POST"); pair.setDoOutput(true);
            pair.setRequestProperty("Origin",base);
            byte[] secret=server.secret().getBytes(StandardCharsets.US_ASCII);
            pair.setFixedLengthStreamingMode(secret.length);
            pair.getOutputStream().write(secret);
            if(pair.getResponseCode()!=200)throw new AssertionError("Pair failed");
            String cookie=pair.getHeaderField("Set-Cookie").split(";")[0];
            String id=requests.poll(3,TimeUnit.SECONDS);
            if(id==null)throw new AssertionError("No approval requested");
            HttpURLConnection before=(HttpURLConnection)new URL(base+"/file").openConnection();
            before.setRequestProperty("Cookie",cookie);
            if(before.getResponseCode()!=404)throw new AssertionError("Approval bypassed");
            final byte[] bytes=new byte[32*1024*1024];
            for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
            server.select(new LocalTransferServer.Source() {
                public String name(){return "test file.bin";}
                public long size(){return bytes.length;}
                public InputStream open(){return new ByteArrayInputStream(bytes);}
            });
            server.approve(id,true);
            HttpURLConnection download=(HttpURLConnection)new URL(base+"/file").openConnection();
            download.setRequestProperty("Cookie",cookie);
            if(download.getResponseCode()!=200)throw new AssertionError("Download failed");
            MessageDigest actual=MessageDigest.getInstance("SHA-256");
            long size=0;
            try(InputStream in=download.getInputStream()){
                byte[] chunk=new byte[131072];int n;
                while((n=in.read(chunk))!=-1){actual.update(chunk,0,n);size+=n;}
            }
            if(size!=bytes.length || !MessageDigest.isEqual(actual.digest(),MessageDigest.getInstance("SHA-256").digest(bytes)))throw new AssertionError("File mismatch");
            server.approve(id,false);
            HttpURLConnection revoked=(HttpURLConnection)new URL(base+"/file").openConnection();
            revoked.setRequestProperty("Cookie",cookie);
            if(revoked.getResponseCode()!=403)throw new AssertionError("Revocation failed");
            System.out.println("PASS: approval gate, 32 MiB streamed transfer with matching SHA-256, unpaired rejection, and access revocation.");
        } finally {server.close();}
    }
}
