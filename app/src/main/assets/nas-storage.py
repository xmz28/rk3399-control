"""USB-only storage administration shared by the web and Android clients."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import signal
import socket
import sys
import time

HOST = Path('/proc/1/root')
REGISTRY = Path('/etc/nas-usb-names')
PAUSE = Path('/run/nas-storage-formatting')

def host(*args, timeout=20):
    if args and args[0] == '/system/bin/sm':
        args = ('/system/bin/sh',) + args
    result = subprocess.run(['/usr/sbin/chroot', str(HOST), *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise ValueError((result.stderr or result.stdout).strip()[-500:] or '系统操作失败')
    return result.stdout

def read(path, default=''):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return default

def names():
    return {p.name: read(p) for p in REGISTRY.glob('*') if p.is_file() and re.fullmatch(r'[A-Za-z0-9_-]+', p.name) and re.fullmatch(r'USB[1-9][0-9]*', read(p))}

def volumes():
    result = []
    mapping = names()
    mounts = {}
    for line in (HOST / 'proc/1/mountinfo').read_text().splitlines():
        fields = line.split()
        mounts[fields[4]] = fields
    for line in host('/system/bin/sm', 'list-volumes', 'all').splitlines():
        fields = line.split()
        if len(fields) != 3 or not re.fullmatch(r'public:\d+,\d+', fields[0]):
            continue
        volume, state, uuid = fields
        dev = volume[7:].replace(',', ':')
        sysdev = (HOST / 'sys/dev/block' / dev).resolve()
        # Public SD cards and internal eMMC are explicitly excluded.
        if not any(re.fullmatch(r'usb\d+', p) for p in sysdev.parts) or not sysdev.exists():
            continue
        device = Path('/dev/block/vold') / volume
        properties = subprocess.run(['/sbin/blkid', str(device)], capture_output=True, text=True, timeout=5)
        props = dict(re.findall(r'([A-Z_]+)="([^"]*)"', properties.stdout))
        if uuid == 'null':
            uuid = props.get('UUID', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]+', uuid):
            continue
        parent = sysdev.parent if (sysdev / 'partition').exists() else sysdev
        model = read(parent / 'device/model')
        vendor = read(parent / 'device/vendor')
        serial = next((read(p / 'serial') for p in sysdev.parents if (p / 'idVendor').exists()), '')
        size = int(read(sysdev / 'size', '0')) * 512
        source = mounts.get('/mnt/media_rw/' + uuid)
        mounted = state in ('mounted', 'mounted_ro') and source is not None
        readonly = state == 'mounted_ro' or Path('/etc/samba/usb-readonly-' + uuid).exists() or bool(source and 'ro' in source[5].split(','))
        sourceid = source[0] if source else ''
        physical = [read('/proc/sys/kernel/random/boot_id'), volume, str(sysdev), sysdev.stat().st_ino, size, serial, vendor, model]
        token = hashlib.sha256(json.dumps(physical + [uuid, state, sourceid]).encode()).hexdigest()
        disk = dict(volume=volume, uuid=uuid, name=mapping.get(uuid, '未命名'), state=state,
            mounted=mounted, readonly=readonly, filesystem=props.get('TYPE', ''), label=props.get('LABEL', ''),
            model=model, vendor=vendor, serial=serial, device=sysdev.name, sizeBytes=size,
            totalBytes=None, freeBytes=None, availableBytes=None, identity=token,
            canFormat=not readonly and props.get('TYPE') == 'vfat', physical=physical)
        if mounted:
            stats = os.statvfs(HOST / 'mnt/media_rw' / uuid)
            disk.update(totalBytes=stats.f_blocks * stats.f_frsize, freeBytes=stats.f_bfree * stats.f_frsize,
                        availableBytes=stats.f_bavail * stats.f_frsize)
        result.append(disk)
    return result

def listing():
    disks = volumes()
    seen = {d['uuid'] for d in disks}
    for uuid, name in names().items():
        if uuid not in seen:
            disks.append(dict(uuid=uuid, name=name, state='disconnected', mounted=False, readonly=False,
                canFormat=False, identity='', totalBytes=None, availableBytes=None, sizeBytes=0))
    for disk in disks:
        disk.pop('physical', None)
    return dict(ok=True, disks=sorted(disks, key=lambda d: int(d['name'][3:]) if d['name'].startswith('USB') else 999),
                formatTypes=['vfat'], busy=PAUSE.exists())

def select(body):
    uuid = body.get('uuid')
    if not isinstance(uuid, str) or not re.fullmatch(r'[A-Za-z0-9_-]+', uuid):
        raise ValueError('无效磁盘 UUID')
    matches = [d for d in volumes() if d['uuid'] == uuid]
    if len(matches) != 1 or matches[0]['identity'] != body.get('identity'):
        raise ValueError('磁盘已拔插或状态变化，请刷新后重试')
    return matches[0]

def action(body):
    operation = body.get('action')
    if operation not in ('eject', 'mount', 'format'):
        raise ValueError('不支持的操作')
    with open('/run/nas-storage-operation.lock', 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('另一项存储操作正在进行')
        if PAUSE.exists():
            raise ValueError('上一项格式化尚未结束，请等待并刷新状态')
        disk = select(body)
        if operation == 'mount':
            if disk['mounted']:
                raise ValueError('磁盘已经挂载')
            host('/system/bin/sm', 'mount', disk['volume'])
        elif operation == 'eject':
            if not disk['mounted']:
                raise ValueError('磁盘未挂载')
            host('/system/bin/sh', '/system/bin/nas-usb-eject.sh', disk['uuid'], timeout=40)
        else:
            if body.get('confirm') != disk['name'] or body.get('filesystem') != 'vfat' or not disk['canFormat']:
                raise ValueError('必须确认盘名；仅支持可写 USB 卷的 FAT 格式化')
            PAUSE.write_text(json.dumps(dict(uuid=disk['uuid'], volume=disk['volume'])))
            try:
                if disk['mounted']:
                    host('/system/bin/sh', '/system/bin/nas-usb-eject.sh', disk['uuid'], timeout=40)
                # Revalidate the same USB device after unmount, immediately before format.
                current = [d for d in volumes() if d['volume'] == disk['volume']]
                if len(current) != 1 or current[0]['physical'] != disk['physical'] or current[0]['uuid'] != disk['uuid'] or current[0]['mounted']:
                    raise ValueError('目标磁盘身份已变化，取消格式化')
                host('/system/bin/sm', 'format', disk['volume'], timeout=60)
                try:
                    host('/system/bin/sm', 'mount', disk['volume'])
                except ValueError:
                    pass  # vold may have mounted it automatically.
                new = None
                for _ in range(40):
                    new = next((d for d in volumes() if d['volume'] == disk['volume'] and d['physical'] == disk['physical'] and d['mounted']), None)
                    if new:
                        break
                    time.sleep(0.5)
                if not new:
                    raise ValueError('格式化已执行，但系统未完成挂载，请刷新检查')
                target = REGISTRY / new['uuid']
                temp = target.with_name(target.name + '.tmp')
                temp.write_text(disk['name'] + '\n')
                temp.chmod(0o600)
                os.replace(temp, target)
                if disk['uuid'] != new['uuid']:
                    (REGISTRY / disk['uuid']).unlink(missing_ok=True)
                Path('/run/nas-usb-ejected', disk['uuid']).unlink(missing_ok=True)
            finally:
                PAUSE.unlink(missing_ok=True)
            # A same-name volume with a new backing UUID leaves Copyparty's
            # delete/index worker holding the previous path. Recreate it after
            # the watcher has regenerated the new volume configuration.
            time.sleep(3)
            pid = int(read('/run/copyparty.pid', '0'))
            if pid > 1 and b'copyparty-sfx.py' in Path('/proc/%d/cmdline' % pid).read_bytes():
                os.kill(pid, signal.SIGTERM)
            ready = False
            for _ in range(100):
                time.sleep(0.5)
                if read('/run/copyparty.pid') == str(pid):
                    continue
                try:
                    for port in (3923, 12345, 1234):
                        with socket.create_connection(('127.0.0.1', port), timeout=0.5):
                            pass
                    ready = True
                    break
                except OSError:
                    continue
            if not ready:
                raise ValueError('格式化完成，但文件服务仍在启动，请刷新检查')
        time.sleep(3)
        response = listing()
        if operation == 'mount' and not any(d['uuid'] == disk['uuid'] and d['mounted'] for d in response['disks']):
            raise ValueError('已请求挂载，但系统尚未挂载成功，请刷新检查')
        response['message'] = {'eject': '安全卸载完成，可以拔出', 'mount': '已挂载', 'format': '格式化完成，磁盘名称已保留'}[operation]
        return response

if __name__ == '__main__':
    try:
        result = action(json.loads(sys.argv[2])) if len(sys.argv) > 2 and sys.argv[1] == 'action' else listing()
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps(dict(ok=False, error=str(e)), ensure_ascii=False))
        sys.exit(1)
