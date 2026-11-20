import in.theclabs.quickdrop.LocalTransferServer;
import java.io.*;
public class FriendlyCheck {
 public static void main(String[] args)throws Exception {
  final String[] pending = new String[1];
  LocalTransferServer server = new LocalTransferServer("local".getBytes(), new LocalTransferServer.Listener(){
   public void approve(String id,String code){pending[0]=id;System.out.println("CODE "+code);}
   public void message(String text){}
   public void received(String name){System.out.println("SAVED "+name);}
  });
  server.openPairing();
  System.out.println("http://127.0.0.1:"+server.port()+"/#"+server.secret());
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in));String command;
  try {
   while((command=input.readLine())!=null) {
    if(command.equals("close"))server.closePairing();
    if(command.equals("open"))server.openPairing();
    if(command.equals("approve"))server.approve(pending[0],true);
    if(command.equals("cancel"))server.cancelOffer();
    if(command.equals("offer"))server.selectFor(new LocalTransferServer.Source(){
     public String name(){return "Welcome to QuickDrop.txt";}
     public long size(){return 32;}
     public InputStream open(){return new ByteArrayInputStream("Your file arrived. Hello there!\n".getBytes());}
    },pending[0]);
    System.out.println("DONE");
   }
  }finally{server.close();}
 }
}
