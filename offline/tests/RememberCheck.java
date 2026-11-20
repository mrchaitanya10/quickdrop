import in.theclabs.quickdrop.LocalTransferServer;
import java.io.*;
public class RememberCheck {
 static LocalTransferServer server;
 static String trusted;
 static int port;
 static final byte[] bytes=new byte[1024*1024];
 static void start() throws Exception {
  server=new LocalTransferServer("local".getBytes(),new LocalTransferServer.Listener(){
   public void approve(String id,String code){trusted=id;server.approve(id,true);}
   public void message(String text){}
  },port,trusted);
  port=server.port();
 }
 public static void main(String[] args)throws Exception{
  for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
  start();
  System.out.println("http://127.0.0.1:"+port+"/#"+server.secret());
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
  String command;
  try {
   while((command=input.readLine())!=null){
    if(command.equals("restart")){server.close();start();}
    if(command.equals("revoke"))server.approve(trusted,false);
    if(command.equals("offer"))server.selectFor(new LocalTransferServer.Source(){
     public String name(){return "saved-test.bin";}
     public long size(){return bytes.length;}
     public InputStream open(){return new ByteArrayInputStream(bytes);}
    },trusted);
    System.out.println("done");
   }
  }finally{server.close();}
 }
}
