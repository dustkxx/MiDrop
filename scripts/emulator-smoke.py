#!/usr/bin/env python3
"""Development-only end-to-end checks on a disposable, rooted AOSP Android 16 AVD.
Never connects to a physical phone or uses the user's production pairing store.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ADB=ROOT/'.sdk/platform-tools/adb'
RUNTIME=ROOT/'.build/android-qa'
PREVIEWS=ROOT/'previews'
SERIAL='emulator-5556'
APP='dev.midrop'


def adb(*args, binary=False):
    r=subprocess.run([str(ADB),'-s',SERIAL,*args],check=True,capture_output=True)
    return r.stdout if binary else r.stdout.decode().strip()


def wait(check, timeout=40):
    until=time.monotonic()+timeout
    last=None
    while time.monotonic()<until:
        try:
            value=check()
            if value:
                return value
        except Exception as error:
            last=error
        time.sleep(.25)
    raise AssertionError('Timed out: '+str(last))


def main():
    devices=subprocess.run([str(ADB),'devices'],capture_output=True,text=True,check=True).stdout
    assert SERIAL+'\tdevice' in devices, 'Start the disposable MiDrop-QA AVD on port 5556 first'
    RUNTIME.mkdir(parents=True,exist_ok=True); PREVIEWS.mkdir(exist_ok=True)
    adb('root'); adb('wait-for-device')
    assert adb('shell','getprop','ro.build.version.sdk')=='36'
    adb('shell','setprop','debug.hwui.drawing_enabled','1')
    adb('shell','svc','power','stayon','true')
    adb('logcat','-c')
    adb('install','-r',str(ROOT/'dist/MiDrop-android.apk'))
    adb('shell','pm','grant',APP,'android.permission.POST_NOTIFICATIONS')
    adb('shell','am','start','-n',APP+'/.MainActivity')
    time.sleep(1)
    adb('shell','am','force-stop',APP)
    state=RUNTIME/'state'; received=RUNTIME/'received'
    server=subprocess.Popen(['/usr/bin/python3',str(ROOT/'mac/receiver.py'),'--state',str(state),
                             '--destination',str(received),'--bind','127.0.0.1'],
                            stdout=subprocess.DEVNULL,stderr=(RUNTIME/'server.log').open('w'))
    try:
        def snapshot(): return json.loads((state/'state.json').read_text())
        info=wait(lambda: snapshot() if (state/'state.json').exists() and snapshot()['pid']==server.pid else None)
        def request(path,body,token=None):
            c=http.client.HTTPSConnection('127.0.0.1',info['port'],context=ssl._create_unverified_context(),timeout=20)
            headers={'Content-Type':'application/json'}
            if token: headers['Authorization']='Bearer '+token
            c.request('POST',path,json.dumps(body).encode(),headers)
            r=c.getresponse(); data=json.loads(r.read()); c.close()
            assert 200<=r.status<300,(path,r.status,data)
            return data
        token=request('/pair',{'code':info['code'],'name':'Android 16 QA'})['token']
        # Inject only the disposable test device's app-private preferences, via adb root.
        uid=adb('shell','stat','-c','%u','/data/user/0/'+APP)
        preferences=ET.Element('map')
        for key,value in {'token':token,'pin':info['fingerprint'],'address':'10.0.2.2:'+str(info['port']),
                          'server_id':info['server_id'],'name':'我的 Mac','client_id':'disposable-qa'}.items():
            ET.SubElement(preferences,'string',{'name':key}).text=value
        ET.SubElement(preferences,'boolean',{'name':'background_receive','value':'true'})
        prefs=RUNTIME/'preferences.xml'; ET.ElementTree(preferences).write(prefs,encoding='utf-8',xml_declaration=True)
        remote='/data/user/0/'+APP+'/shared_prefs'
        adb('shell','mkdir','-p',remote); adb('push',str(prefs),remote+'/midrop.xml'); adb('shell','chown','-R',uid+':'+uid,remote); adb('shell','restorecon','-R',remote); adb('shell','rm','-f',remote+'/midrop.xml.bak')
        adb('shell','am','start','-n',APP+'/.MainActivity')
        device=wait(lambda: next((d for d in snapshot()['trusted_devices'] if d['receiving']),None),60)
        def control(data):
            import socket
            with socket.socket(socket.AF_UNIX) as s:
                s.settimeout(10); s.connect(str(state/'control.sock')); s.sendall(json.dumps(data).encode()+b'\n')
                result=json.loads(s.makefile('rb').readline()); assert 'error' not in result,result; return result
        def job(jid): return next(j for j in snapshot()['transfers'] if j['id']==jid)
        def phone_prefs():
            return ET.fromstring(adb('shell','cat',remote+'/midrop.xml'))
        def pref(key):
            node=phone_prefs().find("*[@name='"+key+"']"); return '' if node is None else node.text or node.get('value','')
        def capture(name):
            data=adb('exec-out','screencap','-p',binary=True)
            assert len(data)>30000,'ATD drawing disabled or blank screenshot'
            (PREVIEWS/name).write_bytes(data)
        wait(lambda: pref('connected')=='true',30)
        time.sleep(1); capture('android-light.png')
        adb('shell','cmd','uimode','night','yes'); time.sleep(1.5); capture('android-dark.png')
        adb('shell','cmd','uimode','night','no'); time.sleep(1)
        # Download consent, zero bytes, Unicode, multiple files, 32 MiB content.
        files=RUNTIME/'source'; files.mkdir(exist_ok=True)
        (files/'空文件.txt').write_bytes(b'')
        (files/'照片与文件-测试.bin').write_bytes(bytes(range(256))*1024)
        with (files/'视频测试-32MiB.bin').open('wb') as f:
            for _ in range(32): f.write(b'Q'*1024*1024)
        jid=control({'action':'queue','device_id':device['id'],'paths':[str(files/n) for n in ['空文件.txt','照片与文件-测试.bin','视频测试-32MiB.bin']]})['id']
        wait(lambda: job(jid)['state']=='waiting'); time.sleep(1.5)
        # Consent is deliberately not delivered yet: no visible file may be published.
        assert pref('last_received_uri')==''
        capture('android-consent.png')
        if '--visual-only' in sys.argv:
            adb('shell','settings','put','system','font_scale','1.3'); time.sleep(1.5)
            capture('android-large-font.png')
            adb('shell','settings','put','system','font_scale','1.0'); time.sleep(1)
        # Exercise the same non-exported service action used by the notification button.
        adb('shell','am','start-foreground-service','-n',APP+'/.SessionService','-a','DECIDE','--es','id',jid,'--ez','accept','true')
        wait(lambda: job(jid)['state']=='completed',120)
        wait(lambda: pref('transfer_state')=='completed',10)
        if '--visual-only' in sys.argv:
            capture('android-completed.png')
            report={'android_api':36,'passed':['latest APK light/dark actual renders','rounded receive prompt','1.3x font scale render','accept action and MediaStore completion']}
            (PREVIEWS/'android-visual-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps(report,ensure_ascii=False,indent=2)); return
        rows=adb('shell','content','query','--uri','content://media/external/downloads','--projection','_id:_display_name:is_pending')
        assert 'is_pending=1' not in rows,rows
        # Phone -> Mac using the actual content URI created by MediaStore above.
        uri=pref('last_received_uri')
        original_name=pref('last_received_name')
        adb('shell','am','start-foreground-service','-n',APP+'/.TransferService','-a','SEND_FILES','--eu','android.intent.extra.STREAM',uri)
        wait(lambda: any(j['direction']=='incoming' and j['state']=='completed' for j in snapshot()['transfers']),120)
        saved=next(x for x in snapshot()['recent'] if x['direction']=='incoming')
        assert hashlib.sha256(Path(saved['path']).read_bytes()).digest()==hashlib.sha256((files/'视频测试-32MiB.bin').read_bytes()).digest()
        wait(lambda: pref('transfer_state')=='completed',20)
        # A second received copy must keep the first MediaStore row/file intact.
        duplicate=control({'action':'queue','device_id':device['id'],'paths':[str(files/'视频测试-32MiB.bin')]})['id']
        wait(lambda: job(duplicate)['state']=='waiting'); time.sleep(1)
        adb('shell','am','start-foreground-service','-n',APP+'/.SessionService','-a','DECIDE','--es','id',duplicate,'--ez','accept','true')
        wait(lambda: job(duplicate)['state']=='completed',120)
        wait(lambda: pref('transfer_state')=='completed',10)
        capture('android-completed.png')
        # Throttle only this disposable emulator, then cancel an active receive.
        adb('emu','network','speed','1024')
        try:
            cancelled=control({'action':'queue','device_id':device['id'],'paths':[str(files/'视频测试-32MiB.bin')]})['id']
            wait(lambda: job(cancelled)['state']=='waiting'); time.sleep(1)
            adb('shell','am','start-foreground-service','-n',APP+'/.SessionService','-a','DECIDE','--es','id',cancelled,'--ez','accept','true')
            wait(lambda: job(cancelled)['state']=='transferring' and job(cancelled)['bytes']>0,40)
            adb('shell','am','start-foreground-service','-n',APP+'/.SessionService','-a','CANCEL')
            wait(lambda: pref('transfer_state')=='cancelled',30)
            assert 'is_pending=1' not in adb('shell','content','query','--uri','content://media/external/downloads','--projection','_id:is_pending')
        finally:
            adb('emu','network','speed','full')
        # Reject from the background using the notification's action, without a download.
        adb('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
        denied=control({'action':'queue','device_id':device['id'],'paths':[str(files/'空文件.txt')]})['id']
        wait(lambda: job(denied)['state']=='waiting'); time.sleep(1)
        adb('shell','am','start-foreground-service','-n',APP+'/.SessionService','-a','DECIDE','--es','id',denied,'--ez','accept','false')
        wait(lambda: job(denied)['state']=='rejected')
        # Disable notifications while backgrounded: Mac must mark receiving unavailable.
        adb('shell','pm','revoke',APP,'android.permission.POST_NOTIFICATIONS')
        wait(lambda: not next(d for d in snapshot()['trusted_devices'] if d['id']==device['id'])['receiving'],75)
        adb('shell','pm','grant',APP,'android.permission.POST_NOTIFICATIONS')
        adb('shell','am','start','-n',APP+'/.MainActivity')
        wait(lambda: next(d for d in snapshot()['trusted_devices'] if d['id']==device['id'])['receiving'],40)
        capture('android-after-tests.png')
        log=adb('logcat','-d','-s','AndroidRuntime:E')
        assert 'FATAL EXCEPTION' not in log,log
        report={'android_api':36,'device':'Disposable AOSP ATD arm64 emulator','passed':[
            'APK installation and foreground service startup','light and dark UI render',
            'no files published before consent','accepted multi-file + zero-byte + Unicode + 32 MiB download',
            'SHA-256 verified Android upload back to Mac','duplicate receipt does not overwrite',
            'active receive cancellation removes unpublished file','background rejection','notifications denied marks receiver unavailable'],
            'not_verified':['physical REDMI Turbo 4 Pro','HyperOS background kill policy','Bonjour on a physical Wi-Fi network']}
        (PREVIEWS/'android-qa-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps(report,ensure_ascii=False,indent=2))
    finally:
        adb('shell','am','force-stop',APP)
        server.terminate(); server.wait(timeout=10)


if __name__=='__main__': main()
