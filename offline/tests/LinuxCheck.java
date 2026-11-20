import in.theclabs.quickdrop.LocalTransferServer;
import java.io.*;
import java.nio.file.*;
public class LinuxCheck {
 public static void main(String[] args)throws Exception{
  final LocalTransferServer[] ref=new LocalTransferServer[1];
  LocalTransferServer server=new LocalTransferServer("local".getBytes(),new LocalTransferServer.Listener(){
   public void approve(String id,String code){ref[0].approve(id,true);}
   public void message(String text){}
  });
  ref[0]=server;
  byte[] bytes=new byte[4*1024*1024];
  for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
  LocalTransferServer.Source source=new LocalTransferServer.Source(){
   public String name(){return "receiver-test.bin";}
   public long size(){return bytes.length;}
   public InputStream open(){return new ByteArrayInputStream(bytes);}
  };
  server.select(source);
  Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.close();}catch(Exception ignored){}}));
  System.out.println("http://127.0.0.1:"+server.port()+"/#"+server.secret());
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
  while(input.readLine()!=null){server.select(source);System.out.println("changed");}
 }
}
