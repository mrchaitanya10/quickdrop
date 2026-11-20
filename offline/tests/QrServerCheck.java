import in.theclabs.quickdrop.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
public class QrServerCheck {
 public static void main(String[] args)throws Exception {
  AtomicReference<String> paired=new AtomicReference<>();
  LocalTransferServer server=new LocalTransferServer("local".getBytes(),new LocalTransferServer.Listener(){
   public void approve(String id,String code){}
   public void message(String message){}
   public void paired(String id){paired.set(id);System.out.println("PAIRED");}
   public void received(String name){System.out.println("SAVED");}
  },args.length>1?Integer.parseInt(args[1]):0,null);
  server.beginQr(args[0]);System.out.println("http://127.0.0.1:"+server.port());
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in));String command;
  byte[] bytes=new byte[3*1024*1024];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
  try{
   while((command=input.readLine())!=null) {
    if(command.startsWith("refresh "))server.beginQr(command.substring(8));
    if(command.equals("expire"))server.closeQr();
    if(command.equals("offer"))server.selectFor(new LocalTransferServer.Source(){
     public String name(){return "QR transfer check.bin";}
     public long size(){return bytes.length;}
     public InputStream open(){return new ByteArrayInputStream(bytes);}
    },paired.get());
    System.out.println("DONE");
   }
  }finally{server.close();}
 }
}
