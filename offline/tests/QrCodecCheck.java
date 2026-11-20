import in.theclabs.quickdrop.QrHandshake;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.*;
public class QrCodecCheck {
 public static void main(String[] args)throws Exception {
  try(DataInputStream input=new DataInputStream(new FileInputStream(args[0]))) {
   int width=input.readInt(),height=input.readInt();byte[] data=new byte[width*height];input.readFully(data);
   String payload=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(data,width,height,0,0,width,height,false)))).getText();
   if(!QrHandshake.ticket(payload).equals(args[1]))throw new AssertionError("QR ticket mismatch");
   if(new QrHandshake(args[1],0).active())throw new AssertionError("Expired QR accepted");
   for(String value:new String[]{"https://example.com",payload+"&x=1","quickdrop://pair?v=1&t=invalid"}) {
    try{QrHandshake.ticket(value);throw new AssertionError("Foreign QR accepted");}catch(IllegalArgumentException expected){}
   }
   System.out.println("PASS: laptop QR decoded with the Android scanner's ZXing/YUV pipeline, strict payload validation, and expiration.");
  }
 }
}
