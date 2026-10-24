#!/usr/bin/env python3
"""Exercise the real Android first-trust Activity flow using a separate test APK."""
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('smoke',ROOT/'scripts/emulator-smoke.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
adb,wait=module.adb,module.wait
run=ROOT/'.build/trust-qa';run.mkdir(parents=True,exist_ok=True)
state=run/'state';destination=run/'received'
server=None
try:
    adb('root');adb('wait-for-device')
    assert adb('shell','getprop','ro.build.version.sdk')=='36'
    adb('install','-r',str(ROOT/'.build/android-instrumentation/qa.apk'))
    adb('shell','am','force-stop','dev.midrop')
    uid=adb('shell','stat','-c','%u','/data/user/0/dev.midrop')
    preferences=run/'empty.xml';preferences.write_text('<?xml version="1.0"?><map><boolean name="background_receive" value="true"/></map>')
    remote='/data/user/0/dev.midrop/shared_prefs'
    adb('push',str(preferences),remote+'/midrop.xml');adb('shell','rm','-f',remote+'/midrop.xml.bak')
    adb('shell','chown','-R',uid+':'+uid,remote);adb('shell','restorecon','-R',remote)
    server=subprocess.Popen(['/usr/bin/python3',str(ROOT/'mac/receiver.py'),'--state',str(state),'--destination',str(destination),'--bind','127.0.0.1'],stdout=subprocess.DEVNULL,stderr=(run/'server.log').open('w'))
    def snapshot(): return json.loads((state/'state.json').read_text())
    info=wait(lambda:snapshot() if (state/'state.json').exists() and snapshot()['pid']==server.pid else None)
    log=(run/'instrumentation.log').open('w')
    test=subprocess.Popen([str(module.ADB),'-s',module.SERIAL,'shell','am','instrument','-w','-r','-e','address','10.0.2.2:'+str(info['port']),'dev.midrop.qa/.TrustInstrumentation'],stdout=log,stderr=log)
    def pending():
        data=snapshot()
        if test.poll() is not None:
            raise AssertionError((run/'instrumentation.log').read_text())
        return data['pending_pairs'][0] if data['pending_pairs'] else None
    request=wait(pending,25)
    assert snapshot()['devices']==info['devices']
    time.sleep(.5)
    (ROOT/'previews/android-first-trust.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    with socket.socket(socket.AF_UNIX) as client:
        client.connect(str(state/'control.sock'));client.sendall(json.dumps({'action':'approve','id':request['id'],'allow':True}).encode()+b'\n')
        assert json.loads(client.makefile('rb').readline())['ok']
    test.wait(timeout=45);log.close()
    output=(run/'instrumentation.log').read_text()
    assert 'real Android connection flow persisted approved token and certificate pin' in output,output
    assert 'INSTRUMENTATION_CODE: -1' in output,output
    assert snapshot()['devices']==info['devices']+1
    report={'passed':'actual Android first-connection flow: pending request -> local approval -> persisted token and pinned certificate; no code entry','api':36}
    (ROOT/'previews/trust-qa-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False,indent=2))
finally:
    adb('shell','am','force-stop','dev.midrop')
    if server:
        server.terminate();server.wait(timeout=10)
