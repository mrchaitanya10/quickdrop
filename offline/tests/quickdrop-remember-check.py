import sys, subprocess, tempfile, threading, time, queue, json, stat
from pathlib import Path
import urllib.error, urllib.request
sys.path.insert(0, 'QuickDropLinux')
import receiver as module
from receiver import Receiver, App, pairing_address
process=subprocess.Popen(['java','-cp','tests/classes','RememberCheck'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
def command(value):
 process.stdin.write(value+'\n');process.stdin.flush()
 assert process.stdout.readline().strip()=='done'
def wait_for(predicate):
 end=time.monotonic()+8
 while time.monotonic()<end:
  if predicate():return
  time.sleep(.05)
 raise AssertionError('Timed out')
try:
 origin,key=pairing_address(process.stdout.readline().strip())
 client=Receiver(origin);assert len(client.pair(key))==6
 wait_for(lambda:client.status()[0]=='empty')
 token=client.token();assert token and len(token)==48
 with tempfile.TemporaryDirectory() as folder:
  root=Path(folder);settings=root/'config'/'receiver.json'
  module.settings_path=lambda:settings
  saved={'origin':origin,'token':token,'folder':str(root/'files'),'last_offer':None}
  module.save_settings(saved)
  assert stat.S_IMODE(settings.stat().st_mode)==0o600
  restored=Receiver(origin);restored.restore(module.load_settings()['token'])
  assert restored.status()[0]=='empty'
  app=App.__new__(App);app.stop=threading.Event();app.messages=queue.Queue()
  app.receiver=restored;app.saved=module.load_settings();app.folder_value=saved['folder'];app.selected=None
  thread=threading.Thread(target=app.listen,daemon=True);thread.start()
  command('offer')
  wait_for(lambda:len(list((root/'files').glob('*.bin')))==1)
  time.sleep(1.3)
  assert len(list((root/'files').glob('*.bin')))==1,'Repeated offer downloaded twice'
  old=module.load_settings()['last_offer'];assert old
  command('offer')
  wait_for(lambda:len(list((root/'files').glob('*.bin')))==2)
  command('restart')
  assert restored.status()[0]=='empty','Saved token failed after server restart'
  command('offer')
  wait_for(lambda:len(list((root/'files').glob('*.bin')))==3)
  expected=bytes(i%251 for i in range(1024*1024))
  for file in (root/'files').glob('*.bin'):assert file.read_bytes()==expected
  assert module.load_settings()['last_offer']!=old
  app.stop.set();thread.join(4);assert not thread.is_alive()
  # A stale offer must never download the replacement under the old name/size.
  stale=restored.offer;command('offer');restored.expected_offer=stale
  try:restored.download('saved-test.bin',len(expected),root/'files',threading.Event(),lambda *a:None)
  except urllib.error.HTTPError as error:assert error.code==412
  else:raise AssertionError('Stale offer allowed')
  unknown=Receiver(origin);unknown.restore('a'*48)
  try:unknown.status()
  except urllib.error.HTTPError as error:assert error.code==403
  else:raise AssertionError('Unknown receiver trusted')
  command('revoke')
  try:restored.status()
  except urllib.error.HTTPError as error:assert error.code==403
  else:raise AssertionError('Revoked receiver trusted')
 print('PASS: saved pairing across phone-server restart, 0600 settings, continuous receiving of three checksummed offers, no repeated download, stale-offer rejection, unknown-token rejection, and revocation.')
finally:
 process.terminate();process.wait(timeout=5)
