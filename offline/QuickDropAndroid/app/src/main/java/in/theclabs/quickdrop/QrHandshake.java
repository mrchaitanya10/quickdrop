package in.theclabs.quickdrop;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A short-lived, single-use pairing ticket scanned from the receiver's screen. */
public final class QrHandshake {
    public final String id;
    public final String challenge;
    private final byte[] key;
    private final long deadline;
    private boolean claimed;
    public QrHandshake(String ticket) { this(ticket,120_000L); }
    public QrHandshake(String ticket,long lifetimeMillis) {
        if(ticket==null || !ticket.matches("[a-f0-9]{64}") || lifetimeMillis<0 || lifetimeMillis>120_000L)throw new IllegalArgumentException("Invalid QR ticket");
        key=unhex(ticket); id=sha256(ticket).substring(0,16);
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);challenge=hex(bytes);
        deadline=System.nanoTime()+lifetimeMillis*1_000_000L;
    }
    public static String ticket(String payload) {
        if(payload==null || !payload.matches("quickdrop://pair\\?v=1&t=[a-f0-9]{64}"))throw new IllegalArgumentException("Scan the QR code inside QuickDrop on your laptop.");
        return payload.substring(payload.indexOf("&t=")+3);
    }
    public synchronized boolean active(){return !claimed && System.nanoTime()<deadline;}
    public String challengeResponse(){return "1\n"+id+"\n"+challenge+"\n"+proof("server:"+challenge);}
    public synchronized boolean claim(String nonce,String proof) {
        if(!active() || nonce==null || !nonce.matches("[a-f0-9]{32}") || proof==null || !proof.matches("[a-f0-9]{64}"))return false;
        boolean valid=MessageDigest.isEqual(proof.getBytes(StandardCharsets.US_ASCII),proof("client:"+challenge+":"+nonce).getBytes(StandardCharsets.US_ASCII));
        if(valid)claimed=true;return valid;
    }
    public String pairedProof(String nonce,String token){return proof("paired:"+nonce+":"+token);}
    private String proof(String value) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return hex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    private static String sha256(String value) {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    private static byte[] unhex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
