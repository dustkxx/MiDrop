#!/usr/bin/env python3
"""MiDrop 0.2: pinned HTTPS data plane and private Unix-socket UI control plane."""
import argparse
import hashlib
import hmac
import json
import mimetypes
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import socketserver
import ssl
import stat
import subprocess
import tempfile
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_FILE = 20 * 1024 ** 3
TERMINAL = {'completed', 'rejected', 'cancelled', 'expired', 'failed'}


def atomic_json(path, value):
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.state-')
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(value, out, ensure_ascii=False)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def read_json(path, fallback):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return fallback


def safe_name(name):
    name = unicodedata.normalize('NFC', name.replace('\\', '/').split('/')[-1])
    name = ''.join(c for c in name if ord(c) >= 32 and c not in ':\x7f')
    name = name.strip().lstrip('.') or '未命名文件'
    while len(name.encode('utf-8')) > 180:
        name = name[:-1]
    return name


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


class Receiver:
    def __init__(self, root, destination):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        self.destination = Path(destination)
        self.destination.mkdir(parents=True, exist_ok=True)
        # A crash / process termination may skip a worker's finally block.
        # Only reclaim this app's private temporary-file namespace, never received files.
        for partial in self.destination.glob('.midrop-*.part'):
            try:
                if (re.fullmatch(r'\.midrop-[a-z0-9_]{8}\.part', partial.name)
                        and not partial.is_symlink() and partial.stat().st_uid == os.getuid()
                        and stat.S_IMODE(partial.stat().st_mode) == 0o600):
                    partial.unlink()
            except FileNotFoundError:
                pass
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.tokens_path = self.root / 'devices.json'
        # Keep the original token-hash -> name schema, preserving every 0.1 token.
        self.devices = read_json(self.tokens_path, {})
        self.meta = read_json(self.root / 'device_meta.json', {})
        self.recent = read_json(self.root / 'history.json', [])[-20:]
        self.sessions, self.pair_requests, self.jobs = {}, {}, {}
        self.attempts, self.trust_attempts = [], []
        self.port = 0
        self.last_publish = 0
        cert, key = self.root / 'cert.pem', self.root / 'key.pem'
        if not cert.exists() or not key.exists():
            subprocess.run(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048',
                            '-sha256', '-nodes', '-days', '3650', '-subj', '/CN=MiDrop',
                            '-keyout', str(key), '-out', str(cert)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        os.chmod(key, 0o600)
        self.fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
        self.server_id = self.fingerprint[:24]
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.context.load_cert_chain(cert, key)
        self.renew_pairing()

    def save_devices(self):
        atomic_json(self.tokens_path, self.devices)
        atomic_json(self.root / 'device_meta.json', self.meta)

    def renew_pairing(self, revoke=False):
        with self.lock:
            self.code = '%06d' % secrets.randbelow(1000000)
            self.expires = time.time() + 300
            if revoke:
                for key in list(self.devices):
                    self.revoke(key[:24])
                self.save_devices()
            self.publish()

    def public_devices(self):
        now = time.time()
        return [dict(id=key[:24], name=self.meta.get(key, {}).get('name', name),
                     online=now - self.sessions.get(key, {}).get('seen', 0) < 60,
                     receiving=(now - self.sessions.get(key, {}).get('seen', 0) < 60
                                and self.sessions.get(key, {}).get('receiving', False)))
                for key, name in self.devices.items()]

    def public_job(self, job):
        return {k: v for k, v in job.items() if k not in ('files', 'owner', 'streaming')}

    def publish(self, force=True, notify=True):
        with self.lock:
            now = time.time()
            if not force and now - self.last_publish < 0.4:
                return
            self.last_publish = now
            atomic_json(self.root / 'state.json', {
                'pid': os.getpid(), 'port': self.port, 'code': self.code, 'expires': self.expires,
                'fingerprint': self.fingerprint, 'server_id': self.server_id,
                'destination': str(self.destination), 'devices': len(self.devices),
                'trusted_devices': self.public_devices(),
                'pending_pairs': [{k: p[k] for k in ('id', 'name', 'peer', 'expires')}
                                  for p in self.pair_requests.values() if p['state'] == 'pending'],
                'transfers': [self.public_job(j) for j in list(self.jobs.values())[-30:]],
                'recent': self.recent[-20:][::-1]})
            if notify:
                self.changed.notify_all()

    def record(self, name, size, direction, device, path=''):
        self.recent.append(dict(name=name, size=size, direction=direction, device=device,
                                path=path, time=time.time()))
        self.recent = self.recent[-20:]
        atomic_json(self.root / 'history.json', self.recent)

    def add_device(self, name, client_id=''):
        token = secrets.token_urlsafe(32)
        key = token_hash(token)
        self.devices[key] = name[:80]
        self.meta[key] = dict(name=name[:80], client_id=client_id, created=time.time())
        self.save_devices()
        return token

    def pair(self, code, name):
        # Legacy API retained for already-installed 0.1 clients and regression tests.
        with self.lock:
            now = time.time()
            self.attempts = [t for t in self.attempts if now - t < 60]
            if len(self.attempts) >= 5:
                return 429, {'error': '尝试过多，请一分钟后重试'}
            self.attempts.append(now)
            if now > self.expires or not re.fullmatch('[0-9]{6}', str(code)) or not hmac.compare_digest(str(code), self.code):
                return 403, {'error': '配对码错误或已过期，请在 Mac 刷新'}
            token = self.add_device(str(name))
            self.expires = 0
            self.publish()
            return 200, {'token': token, 'name': socket.gethostname(), 'server_id': self.server_id}

    def authorized(self, token):
        with self.lock:
            return token_hash(token) in self.devices

    def trust_request(self, data, peer):
        with self.lock:
            self.sweep()
            now = time.time()
            self.trust_attempts = [t for t in self.trust_attempts if now - t < 60]
            if len(self.trust_attempts) >= 5 or sum(p['state'] == 'pending' for p in self.pair_requests.values()) >= 5:
                return 429, {'error': '请求过多，请稍后重试'}
            self.trust_attempts.append(now)
            request_id, ticket = secrets.token_hex(16), secrets.token_urlsafe(32)
            self.pair_requests[request_id] = dict(id=request_id, ticket=ticket, state='pending',
                name=str(data.get('name', 'Android'))[:80], client_id=str(data.get('client_id', ''))[:80],
                peer=peer, expires=now + 60)
            self.publish()
            return 200, dict(request_id=request_id, ticket=ticket, expires=now + 60)

    def trust_status(self, data):
        with self.lock:
            self.sweep()
            p = self.pair_requests.get(str(data.get('request_id', '')))
            if not p or not re.fullmatch('[A-Za-z0-9_-]{32,64}', str(data.get('ticket', ''))) or not hmac.compare_digest(p['ticket'], str(data.get('ticket', ''))):
                return 404, {'error': '连接请求不存在'}
            result = dict(state=p['state'])
            if p['state'] == 'approved':
                result.update(token=p['token'], name=socket.gethostname(), server_id=self.server_id)
            return 200, result

    def approve(self, request_id, allow):
        with self.lock:
            self.sweep()
            p = self.pair_requests.get(request_id)
            if not p or p['state'] != 'pending':
                raise ValueError('请求已过期或已处理')
            p['state'] = 'approved' if allow else 'rejected'
            if allow:
                p['token'] = self.add_device(p['name'], p['client_id'])
            p['expires'] = time.time() + 60
            self.publish()
            return {'ok': True}

    def revoke(self, device_id):
        with self.lock:
            key = next((k for k in self.devices if k[:24] == device_id), None)
            if key:
                self.devices.pop(key)
                self.meta.pop(key, None)
                self.sessions.pop(key, None)
                for job in self.jobs.values():
                    if job['owner'] == key and job['state'] not in TERMINAL:
                        job['state'] = 'cancelled'
                self.save_devices()
                self.publish()
            return {'ok': True}

    def sweep(self):
        with self.lock:
            now, dirty = time.time(), False
            for p in self.pair_requests.values():
                if now > p['expires'] and p['state'] != 'expired':
                    p['state'] = 'expired'
                    p.pop('token', None)
                    dirty = True
            self.pair_requests = {k: p for k, p in self.pair_requests.items() if now - p['expires'] < 180}
            for j in self.jobs.values():
                if j['state'] == 'waiting' and now > j['expires']:
                    j['state'] = 'expired'
                    dirty = True
                elif j['state'] in ('accepted', 'transferring') and now - j.get('updated', now) > 300:
                    j['state'] = 'failed'
                    j['error'] = '连接中断，请重新发送'
                    dirty = True
            if len(self.jobs) > 80:
                for key in list(self.jobs):
                    if len(self.jobs) <= 60:
                        break
                    if self.jobs[key]['state'] in TERMINAL:
                        self.jobs.pop(key)
            if dirty:
                self.publish()

    def events(self, key, receiving, wait=12, name=None):
        with self.changed:
            if key not in self.devices:
                return 401, {'error': '设备信任已撤销，请重新连接'}
            if isinstance(name, str) and name.strip() and self.devices[key] != name[:80]:
                self.devices[key] = name[:80]
                self.meta.setdefault(key, {})['name'] = name[:80]
                self.save_devices()
            initial = key not in self.sessions or self.sessions[key].get('receiving') != bool(receiving)
            self.sessions[key] = dict(seen=time.time(), receiving=bool(receiving))
            self.publish(notify=False)
            def offers():
                self.sweep()
                return [dict(id=j['id'], name=j['name'], size=j['size'], expires=j['expires'],
                             files=[{k: f[k] for k in ('id', 'name', 'size', 'sha256', 'mime')} for f in j['files']])
                        for j in self.jobs.values() if j['owner'] == key and j['direction'] == 'outgoing'
                        and j['state'] == 'waiting' and receiving]
            result = offers()
            if not result and wait and not initial:
                self.changed.wait(timeout=wait)
                result = offers()
            if key not in self.devices:
                return 401, {'error': '设备信任已撤销，请重新连接'}
            return 200, dict(device_id=key[:24], server_id=self.server_id, offers=result)

    def queue(self, device_id, paths):
        with self.lock:
            device = next((d for d in self.public_devices() if d['id'] == device_id), None)
            if not device or not device['receiving']:
                raise ValueError('手机当前不可接收，请打开 MiDrop 或开启后台接收')
            if not isinstance(paths, list) or not 1 <= len(paths) <= 1000:
                raise ValueError('请选择 1–1000 个文件')
            if sum(j['state'] not in TERMINAL for j in self.jobs.values()) >= 10:
                raise ValueError('请等待当前传输完成')
            owner = next(k for k in self.devices if k[:24] == device_id)
            files = []
            for path in paths:
                p = Path(path).expanduser().resolve(strict=True)
                s = p.stat()
                if not stat.S_ISREG(s.st_mode) or s.st_size > MAX_FILE:
                    raise ValueError('仅支持不超过 20 GiB 的普通文件')
                files.append(dict(id=secrets.token_hex(12), path=str(p), name=safe_name(p.name),
                                  size=s.st_size, mtime=s.st_mtime_ns, done=False,
                                  mime=mimetypes.guess_type(p.name)[0] or 'application/octet-stream'))
            jid = secrets.token_hex(16)
            job = dict(id=jid, owner=owner, device_id=device_id, device=device['name'], direction='outgoing',
                       name=files[0]['name'] if len(files) == 1 else '%d 个文件' % len(files),
                       count=len(files), size=sum(f['size'] for f in files), bytes=0, state='preparing',
                       created=time.time(), updated=time.time(), expires=0, files=files, streaming=[])
            self.jobs[jid] = job
            self.publish()
            threading.Thread(target=self.prepare, args=(jid,), daemon=True).start()
            return {'id': jid}

    def prepare(self, jid):
        job = self.jobs[jid]
        try:
            for f in job['files']:
                digest = hashlib.sha256()
                with open(f['path'], 'rb') as source:
                    for chunk in iter(lambda: source.read(256 * 1024), b''):
                        if job['state'] == 'cancelled':
                            return
                        digest.update(chunk)
                s = os.stat(f['path'])
                if s.st_size != f['size'] or s.st_mtime_ns != f['mtime']:
                    raise ValueError('文件准备期间发生变化，请重新发送')
                f['sha256'] = digest.hexdigest()
            with self.lock:
                if job['state'] != 'cancelled' and job['owner'] in self.devices:
                    job.update(state='waiting', expires=time.time() + 120, updated=time.time())
                    self.publish()
        except (OSError, ValueError) as error:
            with self.lock:
                job.update(state='failed', error=str(error))
                self.publish()

    def decide(self, key, jid, decision):
        with self.lock:
            self.sweep()
            j = self.jobs.get(jid)
            if not j or j['owner'] != key:
                return 404, {'error': '传输不存在'}
            if j['state'] != 'waiting':
                return 409, {'error': '请求已过期或已处理', 'state': j['state']}
            j.update(state='accepted' if decision == 'accept' else 'rejected', updated=time.time())
            self.publish()
            return 200, {'ok': True}

    def finish_file(self, key, jid, data):
        with self.lock:
            j = self.jobs.get(jid)
            if not j or j['owner'] != key:
                return 404, {'error': '传输不存在'}
            if j['state'] not in ('accepted', 'transferring'):
                return 409, {'error': '传输已结束', 'state': j['state']}
            f = next((f for f in j['files'] if f['id'] == data.get('file_id')), None)
            if not f or data.get('sha256') != f['sha256']:
                return 422, {'error': '文件校验不一致'}
            if not f['done']:
                f['done'] = True
                self.record(f['name'], f['size'], 'outgoing', j['device'], f['path'])
            j['updated'] = time.time()
            if all(f['done'] for f in j['files']):
                j.update(state='completed', bytes=j['size'])
            self.publish()
            return 200, {'ok': True}

    def cancel(self, jid, key=None, failed=False):
        with self.lock:
            j = self.jobs.get(jid)
            if not j or (key is not None and j['owner'] != key):
                raise ValueError('传输不存在')
            if j['state'] not in TERMINAL or (j['state'] == 'failed' and not failed):
                j['state'] = 'failed' if failed else 'cancelled'
                self.publish()
            return {'ok': True}

    def control(self, data):
        action = data.get('action')
        if action == 'approve':
            return self.approve(str(data.get('id')), data.get('allow') is True)
        if action == 'revoke':
            return self.revoke(str(data.get('id')))
        if action == 'queue':
            return self.queue(str(data.get('device_id')), data.get('paths'))
        if action == 'cancel':
            return self.cancel(str(data.get('id')))
        if action == 'snapshot':
            return read_json(self.root / 'state.json', {})
        raise ValueError('未知操作')


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def handle_error(self, request, client_address):
        error = __import__('sys').exc_info()[1]
        if not isinstance(error, (ConnectionError, ssl.SSLError, TimeoutError)):
            super().handle_error(request, client_address)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(45)
        try:
            return self.receiver.context.wrap_socket(connection, server_side=True, do_handshake_on_connect=False), address
        except Exception:
            connection.close()
            raise


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'MiDrop/2'

    def setup(self):
        super().setup()
        self.connection.settimeout(45)

    def log_message(self, *args):
        pass

    @property
    def receiver(self):
        return self.server.receiver

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        self.wfile.write(body)

    def auth(self):
        bearer = self.headers.get('Authorization', '')
        if not bearer.startswith('Bearer ') or not self.receiver.authorized(bearer[7:]):
            self.reply(401, {'error': '请先连接并在 Mac 允许此设备'})
            return None
        return token_hash(bearer[7:])

    def do_GET(self):
        path = urlsplit(self.path)
        if path.path == '/health':
            self.reply(200, dict(name=socket.gethostname(), protocol=1, api=2,
                                server_id=self.receiver.server_id, capabilities=['trust', 'duplex']))
            return
        if path.path.startswith('/files/'):
            key = self.auth()
            if key:
                parts = path.path.split('/')
                if len(parts) == 4:
                    self.download(key, parts[2], parts[3])
                    return
                self.reply(404, {'error': 'Not found'})
        else:
            self.reply(404, {'error': 'Not found'})

    def do_POST(self):
        receiver, path = self.receiver, urlsplit(self.path)
        if self.headers.get('Transfer-Encoding'):
            self.reply(411, {'error': 'Content-Length required'})
            return
        try:
            size = int(self.headers['Content-Length'])
        except (TypeError, ValueError):
            self.reply(411, {'error': 'Content-Length required'})
            return
        if size < 0 or size > MAX_FILE:
            self.reply(413, {'error': '文件大小必须在 0–20 GiB 之间'})
            return
        if path.path == '/upload':
            key = self.auth()
            if key:
                self.upload(key, path, size)
            return
        if size > 16384:
            self.reply(413, {'error': 'Request too large'})
            return
        try:
            data = json.loads(self.rfile.read(size) or b'{}')
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, OSError):
            self.reply(400, {'error': 'Invalid request'})
            return
        try:
            if path.path == '/pair':
                self.reply(*receiver.pair(data.get('code', ''), data.get('name', 'Android')))
            elif path.path == '/trust/request':
                self.reply(*receiver.trust_request(data, self.client_address[0]))
            elif path.path == '/trust/status':
                self.reply(*receiver.trust_status(data))
            else:
                key = self.auth()
                if not key:
                    return
                if path.path == '/events':
                    self.reply(*receiver.events(key, data.get('receiving') is True, wait=12 if data.get('wait', True) else 0, name=data.get('name')))
                elif path.path.startswith('/offers/'):
                    parts = path.path.split('/')
                    if len(parts) != 4:
                        self.reply(404, {'error': 'Not found'})
                    elif parts[3] == 'decision':
                        if data.get('decision') not in ('accept', 'reject'):
                            raise ValueError('无效的接收决定')
                        self.reply(*receiver.decide(key, parts[2], data['decision']))
                    elif parts[3] == 'ack':
                        self.reply(*receiver.finish_file(key, parts[2], data))
                    elif parts[3] == 'cancel':
                        self.reply(200, receiver.cancel(parts[2], key, data.get('failed') is True))
                    else:
                        self.reply(404, {'error': 'Not found'})
                elif path.path.startswith('/transfers/') and path.path.endswith('/cancel'):
                    self.reply(200, receiver.cancel(path.path.split('/')[2], key))
                else:
                    self.reply(404, {'error': 'Not found'})
        except (ValueError, OSError) as error:
            self.reply(400, {'error': str(error)})

    def upload(self, key, path, size):
        r = self.receiver
        name = safe_name(parse_qs(path.query).get('name', ['未命名文件'])[0])
        expected = self.headers.get('X-Content-SHA256', '')
        if not re.fullmatch('[0-9a-f]{64}', expected):
            self.reply(400, {'error': 'Missing SHA-256'})
            return
        jid = self.headers.get('X-Transfer-ID') or secrets.token_hex(16)
        if not re.fullmatch('[a-zA-Z0-9-]{16,80}', jid):
            self.reply(400, {'error': 'Invalid transfer ID'})
            return
        with r.lock:
            if jid in r.jobs:
                self.reply(409, {'error': '传输标识已使用'})
                return
            job = dict(id=jid, owner=key, device_id=key[:24], device=r.devices[key], direction='incoming',
                       name=name, size=size, bytes=0, count=1, state='transferring', created=time.time(),
                       updated=time.time())
            r.jobs[jid] = job
            r.publish()
        temporary = None
        try:
            fd, temporary = tempfile.mkstemp(prefix='.midrop-', suffix='.part', dir=r.destination)
            digest = hashlib.sha256()
            with os.fdopen(fd, 'wb') as output:
                remaining = size
                while remaining:
                    if job['state'] == 'cancelled' or key not in r.devices:
                        raise EOFError('传输已取消')
                    chunk = self.rfile.read(min(256 * 1024, remaining))
                    if not chunk:
                        raise EOFError('传输中断，未保存文件')
                    digest.update(chunk)
                    output.write(chunk)
                    remaining -= len(chunk)
                    with r.lock:
                        job.update(bytes=size - remaining, updated=time.time())
                        r.publish(force=False)
                output.flush()
                os.fsync(output.fileno())
            if not hmac.compare_digest(expected, digest.hexdigest()):
                job.update(state='failed', error='文件校验失败')
                self.reply(422, {'error': '校验失败，文件未保存'})
                return
            with r.lock:
                if job['state'] == 'cancelled' or key not in r.devices:
                    raise EOFError('传输已取消')
                base = Path(name)
                for index in range(10000):
                    candidate = r.destination / (name if index == 0 else '%s (%d)%s' % (base.stem, index, base.suffix))
                    try:
                        os.link(temporary, candidate)
                        break
                    except FileExistsError:
                        continue
                else:
                    raise OSError('重名文件过多')
                job.update(state='completed', bytes=size)
                r.record(candidate.name, size, 'incoming', job['device'], str(candidate))
                r.publish()
            self.reply(201, dict(name=candidate.name, size=size, sha256=digest.hexdigest()))
        except (OSError, EOFError) as error:
            with r.lock:
                if job['state'] != 'cancelled':
                    job.update(state='failed', error=str(error))
            try:
                self.reply(500, {'error': '接收失败：' + str(error)})
            except OSError:
                pass
        finally:
            if temporary:
                try:
                    os.unlink(temporary)
                except FileNotFoundError:
                    pass
            r.publish()

    def download(self, key, jid, fid):
        r = self.receiver
        with r.lock:
            r.sweep()
            job = r.jobs.get(jid)
            if not job or job['owner'] != key or job['direction'] != 'outgoing':
                self.reply(404, {'error': '传输不存在'})
                return
            f = next((f for f in job['files'] if f['id'] == fid), None)
            if not f or job['state'] not in ('accepted', 'transferring'):
                self.reply(403, {'error': '尚未同意接收或传输已结束'})
                return
            if fid in job['streaming'] or f['done']:
                self.reply(409, {'error': '文件已传输或正在传输'})
                return
            job['streaming'].append(fid)
            job.update(state='transferring', updated=time.time())
        started = False
        try:
            with open(f['path'], 'rb') as source:
                s = os.fstat(source.fileno())
                if s.st_size != f['size'] or s.st_mtime_ns != f['mtime']:
                    raise OSError('源文件已改变，请重新发送')
                self.send_response(200)
                self.send_header('Content-Type', f['mime'])
                self.send_header('Content-Length', str(f['size']))
                self.send_header('X-Content-SHA256', f['sha256'])
                self.send_header('Connection', 'close')
                self.end_headers()
                self.close_connection = True
                started = True
                sent, completed_size = 0, sum(x['size'] for x in job['files'] if x['done'])
                while sent < f['size']:
                    if job['state'] in TERMINAL or key not in r.devices:
                        raise OSError('传输已取消')
                    chunk = source.read(min(256 * 1024, f['size'] - sent))
                    if not chunk:
                        raise OSError('源文件已改变')
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    with r.lock:
                        job.update(bytes=completed_size + sent, updated=time.time())
                        r.publish(force=False)
        except OSError as error:
            with r.lock:
                if job['state'] not in TERMINAL:
                    job.update(state='failed', error=str(error))
            if not started:
                self.reply(409, {'error': str(error)})
        finally:
            with r.lock:
                if fid in job['streaming']:
                    job['streaming'].remove(fid)
                r.publish()


class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        try:
            raw = self.rfile.readline(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError('Request too large')
            data = json.loads(raw)
            result = self.server.receiver.control(data)
        except Exception as error:
            result = {'error': str(error)}
        self.wfile.write(json.dumps(result, ensure_ascii=False).encode() + b'\n')


class ControlServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True

    def __init__(self, path, handler):
        super().__init__(path, handler)
        os.chmod(path, 0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--bind', default='0.0.0.0')
    parser.add_argument('--parent-pid', type=int)
    parser.add_argument('--control')
    args = parser.parse_args()
    if args.control:
        payload = __import__('sys').stdin.buffer.read(1024 * 1024)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(10)
            client.connect(args.control)
            client.sendall(payload + b'\n')
            print(client.makefile('rb').readline().decode(), end='')
        return
    if not args.state or not args.destination:
        parser.error('--state and --destination are required')
    receiver = Receiver(args.state, args.destination)
    server = Server((args.bind, args.port), Handler)
    server.receiver = receiver
    receiver.port = server.server_port
    socket_path = receiver.root / 'control.sock'
    if socket_path.exists():
        socket_path.unlink()
    control = ControlServer(str(socket_path), ControlHandler)
    control.receiver = receiver
    os.chmod(socket_path, 0o600)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    receiver.publish()
    signal.signal(signal.SIGUSR1, lambda *_: receiver.renew_pairing(revoke=True))
    signal.signal(signal.SIGUSR2, lambda *_: receiver.renew_pairing())
    def maintain():
        while True:
            time.sleep(2)
            if args.parent_pid and os.getppid() != args.parent_pid:
                os._exit(0)
            receiver.sweep()
            receiver.publish(notify=False)
    threading.Thread(target=maintain, daemon=True).start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        control.server_close()
        socket_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
