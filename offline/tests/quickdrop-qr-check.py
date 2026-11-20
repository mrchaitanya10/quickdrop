import hashlib, json, secrets, socket, struct, subprocess, sys, tempfile, threading, time
from pathlib import Path
import urllib.error, urllib.request
sys.path.insert(0,'QuickDropLinux')
from receiver import Receiver
from qr_pairing import new_ticket,ticket_id,proof,payload,pair_receiver,qr_matrix,discover_receiver,SERVICE_TYPE
from zeroconf import IPVersion, ServiceInfo, Zeroconf

ticket=new_ticket()
process=subprocess.Popen(['java','-cp','tests/classes','QrServerCheck',ticket],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
def command(value):
 process.stdin.write(value+'\n');process.stdin.flush()
 while process.stdout.readline().strip()!='DONE':pass
def denied(operation):
 try:operation()
 except urllib.error.HTTPError as error:assert error.code==403
 else:raise AssertionError('Unauthenticated or replayed QR accepted')
try:
 origin=process.stdout.readline().strip();client=Receiver(origin)
 denied(lambda:client.status())
 identity=ticket_id(ticket)
 with client.opener.open(origin+'/qr-challenge/'+identity) as response:
  challenge=response.read().decode().splitlines()
 assert ticket not in '\n'.join(challenge)
 nonce=secrets.token_hex(16)
 invalid=urllib.request.Request(origin+'/qr-pair',data='\n'.join([identity,nonce,'0'*64]).encode(),headers={'Origin':origin})
 denied(lambda:client.opener.open(invalid))
 pair_receiver(client,ticket)
 assert process.stdout.readline().strip()=='PAIRED'
 assert client.status()[0]=='empty'
 restored=Receiver(origin);restored.restore(client.token());assert restored.status()[0]=='empty'
 denied(lambda:pair_receiver(Receiver(origin),ticket))
 command('offer');state,name,size=client.status();assert state=='ready'
 client.expected_offer=client.offer
 with tempfile.TemporaryDirectory() as temporary:
  result=client.download(name,size,temporary,threading.Event(),lambda *a:None)
  assert hashlib.sha256(result.read_bytes()).digest()==hashlib.sha256(bytes(i%251 for i in range(3*1024*1024))).digest()
  client.acknowledge(client.offer)
  assert process.stdout.readline().strip()=='SAVED'
  assert client.status()[0]=='empty'
 # A fresh challenge for the same scanned ticket invalidates older proofs.
 command('refresh '+ticket)
 with client.opener.open(origin+'/qr-challenge/'+identity) as response:old=response.read().decode().splitlines()[2]
 command('refresh '+ticket)
 stale=urllib.request.Request(origin+'/qr-pair',data='\n'.join([identity,nonce,proof(ticket,'client:'+old+':'+nonce)]).encode(),headers={'Origin':origin})
 denied(lambda:client.opener.open(stale))
 command('expire');denied(lambda:pair_receiver(Receiver(origin),ticket))
 command('refresh '+ticket)
 # End-to-end DNS-SD discovery, proof exchange and approved access.
 host='127.0.0.1'
 zc=Zeroconf(interfaces=[host],ip_version=IPVersion.V4Only)
 info=ServiceInfo(SERVICE_TYPE,'QuickDrop-'+identity+'.'+SERVICE_TYPE,
  addresses=[socket.inet_aton(host)],port=int(origin.rsplit(':',1)[1]),properties={'v':'1','hosts':'127.0.0.2,127.0.0.1'},server='quickdrop-qr-test.local.')
 try:
  zc.register_service(info)
  slow_started=threading.Event();slow_done=threading.Event();stages=[]
  class AddressReceiver(Receiver):
   def __init__(self,origin):
    super().__init__(origin)
    if '127.0.0.2:' in origin:
     class SlowOpener:
      def open(self,*args,**kwargs):
       slow_started.set();time.sleep(3);slow_done.set();raise TimeoutError('Simulated unreachable phone address')
     self.opener=SlowOpener()
  began=time.monotonic()
  discovered=discover_receiver(ticket,AddressReceiver,threading.Event(),timeout=10,interfaces=[host],on_progress=lambda stage,text:stages.append(stage))
  elapsed=time.monotonic()-began
  assert slow_started.is_set() and not slow_done.is_set(),'A dead address delayed the valid address'
  assert elapsed<2,'Parallel discovery took too long in the local fixture'
  assert all(stage in stages for stage in ['searching','found','verifying','connected'])
  print(f'PASS: parallel local address probes paired in {elapsed:.2f}s without waiting for a simulated 3s dead address; connection stages reported.')
  assert discovered and discovered.status()[0]=='empty','mDNS discovery did not complete'
  assert process.stdout.readline().strip()=='PAIRED'
  denied(lambda:client.status())
  command('refresh '+ticket)
  class BlockedReceiver(Receiver):
   def __init__(self,origin):
    super().__init__(origin)
    class BlockedOpener:
     def open(self,*args,**kwargs):raise TimeoutError('Simulated blocked local connection')
    self.opener=BlockedOpener()
  try:
   discover_receiver(ticket,BlockedReceiver,threading.Event(),timeout=3,interfaces=[host],connect_timeout=.35)
  except ValueError as error:assert 'Phone found' in str(error)
  else:raise AssertionError('Blocked connection did not report a clear failure')
  assert discovered.status()[0]=='empty','A failed probe changed existing approved access'
  print('PASS: blocked discovered phone produces a bounded, explicit failure without consuming credentials.')
 finally:
  zc.unregister_service(info);zc.close()
 # Decode the actual QR matrix with the same Java decoder used by the phone scanner.
 matrix=qr_matrix(ticket);scale=5;side=len(matrix)*scale
 pixels=bytes(0 if matrix[y//scale][x//scale] else 255 for y in range(side) for x in range(side))
 with tempfile.TemporaryDirectory() as temporary:
  path=Path(temporary)/'qr-y-plane.bin';path.write_bytes(struct.pack('>II',side,side)+pixels)
  subprocess.run(['java','-cp','tests/classes:tests/zxing.jar','QrCodecCheck',str(path),ticket],check=True)
 print('PASS: offline QR proof exchange, invalid proof and replay rejection, stale/closed ticket rejection, remembered access, 3 MiB checksummed transfer, receipt, and loopback multicast DNS discovery.')
finally:
 process.terminate();process.wait(timeout=5)
