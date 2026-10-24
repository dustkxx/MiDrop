#!/usr/bin/env python3
"""Verify in-place APK upgrade only on the disposable emulator-5556."""
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('qa',ROOT/'scripts/emulator-smoke.py')
qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
adb=qa.adb
adb('root');adb('wait-for-device')
assert adb('shell','getprop','ro.build.version.sdk')=='36'
adb('shell','am','force-stop','dev.midrop')
old=ROOT/'.build/backup-0.1/MiDrop-android.apk'
try:
    result=adb('install','-r','-d',str(old))
    assert 'Success' in result,result
except Exception:
    # This is a disposable QA AVD, never a physical device. Some images disallow downgrades.
    adb('uninstall','dev.midrop');adb('install',str(old))
    adb('shell','am','start','-n','dev.midrop/.MainActivity')
    adb('shell','am','force-stop','dev.midrop')
remote='/data/user/0/dev.midrop/shared_prefs/midrop.xml'
# A fresh private marker makes preservation measurable even on a fallback fresh install.
uid=adb('shell','stat','-c','%u','/data/user/0/dev.midrop')
prefs=ROOT/'.build/upgrade-fixture.xml'
prefs.write_text('<map><string name="token">qa-upgrade-token</string><string name="pin">'+'c'*64+'</string><string name="address">192.0.2.1:9999</string><string name="name">QA upgrade marker</string></map>')
adb('push',str(prefs),remote);adb('shell','rm','-f',remote+'.bak')
adb('shell','chown',uid+':'+uid,remote);adb('shell','restorecon',remote)
before=adb('shell','cat',remote)
assert 'versionCode=1' in adb('shell','dumpsys','package','dev.midrop')
assert 'Success' in adb('install','-r',str(ROOT/'dist/MiDrop-android.apk'))
after=adb('shell','cat',remote)
assert before==after
assert 'versionCode=2' in adb('shell','dumpsys','package','dev.midrop')
report={'passed':['actual APK 0.1 -> 0.2 in-place installation','application private preferences preserved byte-for-byte','certificate/token/address fields retained'],'device':'disposable Android 16 AVD only'}
(ROOT/'previews/upgrade-qa-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False,indent=2))
