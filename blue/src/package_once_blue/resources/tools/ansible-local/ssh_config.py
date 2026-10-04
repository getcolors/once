#!/usr/bin/env python3
"""Package-owned SSH config updater; stdin contains only connection metadata."""
import fcntl
import ipaddress
import json
import os
import pathlib
import pwd
import re
import shlex
import stat
import sys
import tempfile
import time


def update(payload, home=None):
    profile = payload.get('host_alias')
    if not isinstance(profile, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,62}', profile):
        raise ValueError('invalid SSH deployment alias')
    mode = payload.get('block_state')
    if mode not in ('present', 'absent') or type(payload.get('keygen')) is not bool:
        raise ValueError('invalid SSH block request')
    installed = payload.get('installed', False)
    check_only = payload.get('check_only', False)
    if type(installed) is not bool or type(check_only) is not bool:
        raise ValueError('invalid SSH installation flags')
    hosts = payload.get('ssh_hosts')
    if not isinstance(hosts, list) or (mode == 'present' and not hosts) or len(hosts) > 1001:
        raise ValueError('invalid SSH host inventory')
    aliases = set()
    for host in hosts:
        if not isinstance(host, dict):
            raise ValueError('invalid SSH host')
        alias, address, user = (host.get(key) for key in ('name', 'ip', 'user'))
        if not isinstance(alias, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', alias):
            raise ValueError('invalid SSH host alias')
        if alias != profile and not alias.startswith(profile + '-'):
            raise ValueError('SSH node alias outside deployment')
        if alias.lower() in aliases:
            raise ValueError('duplicate SSH alias')
        aliases.add(alias.lower())
        if not isinstance(user, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}', user):
            raise ValueError('invalid SSH user')
        if not isinstance(address, str) or str(ipaddress.ip_address(address)) != address:
            raise ValueError('invalid SSH address')
    if mode == 'present' and profile.lower() not in aliases:
        raise ValueError('missing SSH entry alias')
    legacy = payload.get('legacy_marker_prefix', '')
    if not isinstance(legacy, str) or (legacy and not re.fullmatch(r'[a-z][a-z0-9-]{0,31}', legacy)):
        raise ValueError('invalid legacy SSH marker')
    home = pathlib.Path(home or os.environ.get('HOME') or pwd.getpwuid(os.getuid()).pw_dir)
    if not home.is_absolute() or any(p.is_symlink() for p in (home, *home.parents)) or not home.is_dir():
        raise ValueError('unsafe SSH home')
    directory = home / '.ssh'
    if not directory.exists() and not directory.is_symlink() and mode == 'absent':
        return False
    if not check_only:
        directory.mkdir(mode=0o700, exist_ok=True)
    if directory.is_symlink() or (directory.exists() and (not directory.is_dir() or directory.stat().st_uid != os.getuid())):
        raise ValueError('unsafe SSH directory')
    if not check_only:
        os.chmod(directory, 0o700)
    lock = directory / '.colors-ssh-config.lock'
    fd = None if check_only else os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        if fd is not None:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
                raise ValueError('unsafe SSH config lock')
            os.fchmod(fd, 0o600)
            deadline = time.monotonic() + 30
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise ValueError('SSH config lock timed out')
                    time.sleep(0.025)
        path = directory / 'config'
        if path.is_symlink():
            raise ValueError('unsafe SSH config file')
        try:
            config_fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except FileNotFoundError:
            old = ''
        else:
            with os.fdopen(config_fd, 'r', encoding='utf-8') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
                    raise ValueError('unsafe SSH config file')
                old = stream.read(2 * 1024 * 1024 + 1)
                if len(old.encode('utf-8')) > 2 * 1024 * 1024:
                    raise ValueError('SSH config exceeds size limit')
        begin = '# BEGIN ' + profile + ' ANSIBLE MANAGED BLOCK'
        end = '# END ' + profile + ' ANSIBLE MANAGED BLOCK'
        pairs = {begin: end}
        if legacy:
            pairs['# BEGIN ' + legacy + ' ' + profile + ' ANSIBLE MANAGED BLOCK'] = '# END ' + legacy + ' ' + profile + ' ANSIBLE MANAGED BLOCK'
        retained = []
        expected_end = None
        seen = set()
        for number, line in enumerate(old.splitlines(keepends=True), 1):
            stripped = line.strip()
            if stripped in pairs:
                if expected_end or stripped in seen:
                    raise ValueError(f'{path}:{number}: malformed owned SSH markers')
                seen.add(stripped)
                expected_end = pairs[stripped]
            elif stripped in pairs.values():
                if stripped != expected_end:
                    raise ValueError(f'{path}:{number}: malformed owned SSH markers')
                expected_end = None
            elif expected_end is None:
                match = re.fullmatch(r'(?i)\s*Host(?:\s*=\s*|\s+)(.*?)\s*', line)
                if mode == 'present' and match and aliases.intersection(x.lower() for x in shlex.split(match[1], comments=True)):
                    raise ValueError(f'{path}:{number}: SSH alias is outside the deployment marker')
                retained.append(line)
        if expected_end:
            raise ValueError(f'{path}: unterminated owned SSH marker')
        remainder = ''.join(retained)
        if mode == 'present':
            for number, line in enumerate(retained, 1):
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                if not re.match(r'(?i)^\s*(Host|Match)(?:\s*=\s*|\s+)', line):
                    raise ValueError(f'{path}:{number}: leading SSH options require an explicit Host * stanza')
                break
            block = [begin]
            for host in hosts:
                block.extend(['Host ' + host['name'], '    HostName ' + host['ip'], '    User ' + host['user'], '    Port 22'])
                if payload['keygen']:
                    identity = payload.get('identity_file') or host.get('identity_file', '')
                    if not isinstance(identity, str) or not identity.startswith('/') or any(c in identity for c in '\r\n\x00\"'):
                        raise ValueError('invalid public identity path')
                    block.extend(['    IdentityFile \"' + identity + '\"', '    IdentitiesOnly yes', '    IdentityAgent none'])
                if payload['keygen'] and not installed:
                    block.extend(['    ControlMaster no', '    ControlPersist no', '    ControlPath none'])
                if installed:
                    block.extend(['    AddKeysToAgent no', '    IgnoreUnknown UseKeychain', '    UseKeychain no'])
                block.extend(['    StrictHostKeyChecking accept-new', '    ForwardAgent no'])
            new = '\n'.join(block + [end, '']) + remainder
        else:
            new = remainder
        if check_only or new == old:
            return False
        temporary_fd, temporary = tempfile.mkstemp(prefix='.colors-config-', dir=directory)
        try:
            with os.fdopen(temporary_fd, 'w', encoding='utf-8', newline='') as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(new)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return True
    finally:
        if fd is not None:
            os.close(fd)


if __name__ == '__main__':
    try:
        print('changed' if update(json.load(sys.stdin)) else 'unchanged')
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
