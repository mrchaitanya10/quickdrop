import sys, subprocess, tempfile, hashlib, json, threading
from pathlib import Path
sys.path.insert(0,'QuickDropLinux')
from receiver import Receiver, pairing_address
process=subprocess.Popen(['java','-cp','tests/classes','LinuxCheck'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
try:
 origin,key=pairing_address(process.stdout.readline().strip())
 receiver=Receiver(origin)
 assert len(receiver.pair(key))==6
 state,name,size=receiver.status()
 assert state=='ready'
 expected=bytes(i%251 for i in range(4*1024*1024))
 with tempfile.TemporaryDirectory() as directory:
  folder=Path(directory)
  result=receiver.download(name,size,folder,threading.Event(),lambda *args:None)
  assert hashlib.sha256(result.read_bytes()).digest()==hashlib.sha256(expected).digest()
  prefix=hashlib.sha256(name.encode()).hexdigest()[:16]
  partial=folder/f'.quickdrop-{prefix}.part'
  metadata=partial.with_suffix('.json')
  with receiver.opener.open(origin+'/file') as response:
   etag=response.headers['ETag']
   partial.write_bytes(response.read(1024*1024))
  metadata.write_text(json.dumps({'size':size,'etag':etag}))
  class RecordingOpener:
   def __init__(self,opener):self.opener=opener;self.status=None
   def open(self,*args,**kwargs):
    response=self.opener.open(*args,**kwargs);self.status=response.status;return response
  receiver.opener=RecordingOpener(receiver.opener)
  result2=receiver.download(name,size,folder,threading.Event(),lambda *args:None)
  assert receiver.opener.status==206
  assert result2!=result and result.exists()
  assert result2.read_bytes()==expected
  partial.write_bytes(b'wrong data')
  metadata.write_text(json.dumps({'size':size,'etag':etag}))
  process.stdin.write('change\n');process.stdin.flush()
  assert process.stdout.readline().strip()=='changed'
  result3=receiver.download(name,size,folder,threading.Event(),lambda *args:None)
  assert receiver.opener.status==200
  assert result3.read_bytes()==expected
  stop=threading.Event();stop.set()
  assert receiver.download(name,size,folder,stop,lambda *args:None) is None
 for value in ['https://8.8.8.8/#'+'a'*48,'http://8.8.8.8/#'+'a'*48,'http://127.0.0.1/#wrong']:
  try:pairing_address(value)
  except ValueError:pass
  else:raise AssertionError('Invalid link accepted')
 print('PASS: pairing, streamed download, identical checksum, partial-file resume (206), changed-file restart (200), no filename overwrite, pause, and address validation.')
finally:
 process.terminate();process.wait(timeout=5)
