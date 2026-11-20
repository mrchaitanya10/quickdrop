import subprocess,sys,threading,tempfile
from pathlib import Path
import urllib.error
sys.path.insert(0,'QuickDropLinux')
from receiver import Receiver,pairing_address,connection_address
process=subprocess.Popen(['java','-cp','tests/classes','FriendlyCheck'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
def command(text):
 process.stdin.write(text+'\n');process.stdin.flush()
 lines=[]
 while True:
  line=process.stdout.readline().strip()
  if line=='DONE':return lines
  lines.append(line)
try:
 origin,key=pairing_address(process.stdout.readline().strip())
 assert connection_address('192.168.43.1')==('http://192.168.43.1:45085',None)
 assert connection_address(origin)==(origin,None)
 assert connection_address(origin+'/#'+key)==(origin,key)
 for value in ['8.8.8.8','0.0.0.0','https://192.168.1.1','192.168.1.1/path','192.168.1.1:99999','localhost','192.168.1.1?x=1']:
  try:connection_address(value)
  except ValueError:pass
  else:raise AssertionError('Invalid address accepted '+value)
 command('close')
 client=Receiver(origin)
 try:client.pair(None)
 except urllib.error.HTTPError as e:assert e.code==403
 else:raise AssertionError('Closed pairing accepted')
 command('open');code=client.pair(None)
 assert process.stdout.readline().strip()=='CODE '+code
 assert client.status()[0]=='waiting'
 command('offer')
 assert client.status()==('waiting',None,None)
 command('approve')
 assert client.status()[0]=='ready'
 stale=client.offer
 with tempfile.TemporaryDirectory() as folder:
  state,name,size=client.status();client.expected_offer=client.offer
  result=client.download(name,size,folder,threading.Event(),lambda *a:None)
  assert result.read_bytes()==b'Your file arrived. Hello there!\n'
  client.acknowledge(stale)
  assert process.stdout.readline().strip()=='SAVED Welcome to QuickDrop.txt'
  assert client.status()[0]=='empty'
  command('offer')
  try:client.acknowledge(stale)
  except urllib.error.HTTPError as e:assert e.code==412
  else:raise AssertionError('Stale receipt cleared a new offer')
  assert client.status()[0]=='ready'
  command('cancel');assert client.status()[0]=='empty'
 print('PASS: short-address setup, legacy links, address validation, pairing-window gate, approval gate with hidden metadata, verified save receipts, stale receipt rejection, and cancellation.')
finally:
 process.terminate();process.wait(timeout=5)
