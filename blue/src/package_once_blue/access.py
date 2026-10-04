"""Scoped encrypted machine access; lock ownership is shared across colours."""
import contextvars
import fcntl
import os
import stat
from pathlib import Path
from blue.scope import with_scope
from blue.process import run_inherit
from colors_compute import ssh_resource, start_agent, compute_registration, registration_plan
from . import machine

_register = contextvars.ContextVar('once_access_scope', default=None)
_locked_paths = set()


def register_cleanup(cleanup):
    """Attach a temporary resource when called inside an ONCE access scope."""
    register = _register.get()
    if register is not None:
        register('resource', cleanup)


async def scoped(body):
    async def run(register):
        token = _register.set(register)
        try:
            return await body()
        finally:
            _register.reset(token)
    return await with_scope(run)


def lock(opts):
    register = _register.get()
    if register is None:
        raise ValueError('ONCE runtime requires an access scope')
    directory = Path(machine.sdk_workdir(opts)) / opts['profile']
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for parent in (directory.parent, directory):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise ValueError('unsafe ONCE work directory')
        parent.chmod(0o700)
    path = str(directory / '.once.lock')
    if path in _locked_paths:
        raise ValueError('another ONCE operation owns this profile')
    fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise ValueError('unsafe ONCE lock')
        os.fchmod(fd, 0o600)
        try:
            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('another ONCE operation owns this profile') from None
        _locked_paths.add(path)
        def close():
            try:
                os.close(fd)
            finally:
                _locked_paths.discard(path)
        register('resource', close)
    except BaseException:
        os.close(fd)
        raise


async def resource_step(opts):
    existing = (Path(machine.sdk_workdir(opts)) / opts['profile'] / machine.NODE_ID / 'compute.tf.json').exists()
    operation = 'inspect' if existing or opts.get('compute-require-existing-state') or opts.get('blue/event') != 'create' else 'create'
    result = machine.PLACEHOLDER if machine.planning(opts) else await ssh_resource(machine.library_options(opts), machine.ssh_request(opts), operation, dict(os.environ))
    return {**opts, 'once/ssh-resource': result, 'blue/exit': 0} if result['status'] == 'ready' else machine.failure(opts, result)


def placeholder_registration(opts):
    return {'status': 'ready', 'reference': 'registration:build-placeholder', 'provider': opts['provider-compute'], 'ssh_resource_reference': machine.resource(opts)['reference'], 'fingerprint': machine.resource(opts)['fingerprint'], 'id': '0'}


async def registration_step(opts):
    if not machine.registration(opts):
        return opts
    operation = 'create' if opts.get('blue/event') == 'create' and not opts.get('compute-require-existing-state') else 'inspect'
    result = machine.canonicalize(registration_plan(machine.library_options(opts), machine.registration_request(opts))) if machine.planning(opts) else await compute_registration(machine.library_options(opts), machine.registration_request(opts), operation)
    if result['status'] in ('ready', 'built'):
        return {**opts, 'once/ssh-registration': result if result['status'] == 'ready' else placeholder_registration(opts), 'blue/exit': 0}
    if result['status'] == 'destroyed' and opts.get('blue/event') == 'delete':
        return {**opts, 'once/registration-destroyed': True, 'once/ssh-registration': placeholder_registration(opts)}
    return machine.failure(opts, result)


async def agent_step(opts):
    if machine.planning(opts):
        return {**opts, 'ssh-private-key-path': f'/home/build-placeholder/compute/{opts["profile"]}/ssh/machine-access/identity.pub', 'once/agent-socket': '/home/build-placeholder/agent.sock'}
    register = _register.get()
    if register is None:
        raise ValueError('ONCE runtime requires an access scope')
    agent = await start_agent([{'opts': machine.library_options(opts), 'request': machine.ssh_request(opts), 'resource': machine.resource(opts)}], dict(os.environ), register)
    return {**opts, 'once/agent-socket': agent['socket'], 'ssh-private-key-path': agent['identities'][machine.resource(opts)['reference']]}


async def registration_delete(opts):
    if not machine.registration(opts):
        return opts
    result = await compute_registration(machine.library_options(opts), machine.registration_request(opts), 'delete')
    return {**opts, 'blue/exit': 0} if result['status'] == 'destroyed' else machine.failure(opts, result)


def identity_args(opts):
    path = opts.get('ssh-private-key-path')
    return ['-F', '/dev/null', '-o', 'IdentityFile=none', '-i', path, '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=' + (opts.get('once/agent-socket') or 'none'), '-o', 'ForwardAgent=no', '-o', 'ControlMaster=no', '-o', 'ControlPersist=no', '-S', 'none'] if path else []


def ssh_args(opts):
    return ['ssh', '-p', '22', '-l', opts.get('user'), '-o', 'StrictHostKeyChecking=accept-new', *identity_args(opts), '--', opts.get('ip')]


async def ssh_step(opts):
    if machine.planning(opts):
        return opts
    if any(not isinstance(opts.get(key), str) or not opts[key].strip() for key in ('ip', 'user', 'ssh-private-key-path', 'once/agent-socket')):
        return {**opts, 'blue/exit': 1, 'blue/err': 'SSH requires a resolved address, login and scoped identity'}
    result = run_inherit(ssh_args(opts))
    return {**opts, 'blue/exit': result.exit}


def install_lock(opts, home=None):
    """Hold the per-profile installation lock across different work directories."""
    import re
    register = _register.get()
    if register is None:
        raise ValueError('ONCE installation requires an access scope')
    profile = opts.get('profile')
    if not isinstance(profile, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,62}', profile):
        raise ValueError('invalid SSH deployment alias')
    directory = Path(os.path.abspath(home or Path.home())) / '.ssh'
    try:
        for parent in reversed((directory, *directory.parents)):
            try:
                info = parent.lstat()
            except FileNotFoundError:
                parent.mkdir(mode=0o700)
                info = parent.lstat()
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError('unsafe ONCE installation lock')
        if directory.lstat().st_uid != os.getuid():
            raise ValueError('unsafe SSH directory owner')
        directory.chmod(0o700)
        path = str(directory / f'.once-install-{profile}.lock')
        if path in _locked_paths:
            raise ValueError('another ONCE operation owns this SSH installation')
        fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    except OSError:
        raise ValueError('unsafe ONCE installation lock') from None
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise ValueError('unsafe ONCE installation lock')
        os.fchmod(fd, 0o600)
        try:
            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('another ONCE operation owns this SSH installation') from None
        _locked_paths.add(path)
        def close():
            try:
                os.close(fd)
            finally:
                _locked_paths.discard(path)
        register('resource', close)
    except OSError:
        os.close(fd)
        raise ValueError('unsafe ONCE installation lock') from None
    except BaseException:
        os.close(fd)
        raise


def export_directory(opts):
    return str(Path.home() / '.ssh' / 'once' / opts['profile'])


async def export_operation(opts, operation, run_fn=None):
    from colors_compute import ssh_export
    request = machine.ssh_request(opts)
    if opts.get('once/ssh-resource'):
        request = {**request, 'expected': opts['once/ssh-resource']}
    env = dict(os.environ) if operation == 'install' else {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR') if key in os.environ}
    return await (run_fn or ssh_export)(machine.library_options(opts), request, export_directory(opts), operation, env)


async def installed_identity(opts, export_fn=None):
    if machine.planning(opts):
        return None
    install_lock(opts)
    result = await (export_fn or export_operation)(opts, 'inspect')
    if result['status'] == 'installed':
        return result['private_key_file']
    if result['status'] != 'absent':
        raise ValueError((result.get('error') or {}).get('message') or 'Invalid installed SSH identity')
    return None


def config_payload(opts, mode, identity):
    return {'host_alias': opts['profile'], 'block_state': mode, 'keygen': True, 'installed': True,
            'identity_file': identity or '', 'legacy_marker_prefix': '',
            'ssh_hosts': [] if mode == 'absent' else [{'name': opts['profile'], 'ip': opts.get('ip'), 'user': opts.get('user')}]}


async def update_config(payload):
    import json
    from blue.runtime import runtime
    script = (Path(__file__).parent / 'resources/tools/ansible-local/ssh_config.py').read_text()
    result = await runtime.exec(['python3', '-c', 'import io, sys\nsys.stdin = io.StringIO(sys.argv[1])\n' + script, json.dumps(payload)])
    return {'exit': result.exit, 'err': result.err}


def config_failure(opts, result):
    return {**opts, 'blue/exit': result.get('exit', 1), 'blue/err': 'SSH config update failed: ' + (result.get('err') or '')}


async def install_step(opts, export_fn=None, config_fn=None):
    if machine.planning(opts):
        return opts
    export_fn, config_fn = export_fn or export_operation, config_fn or update_config
    install_lock(opts)
    result = await config_fn({**config_payload(opts, 'present', str(Path(export_directory(opts)) / 'identity')), 'check_only': True})
    if result.get('exit', 1) != 0:
        return config_failure(opts, result)
    exported = await export_fn(opts, 'install')
    if exported['status'] != 'installed':
        return machine.failure(opts, exported)
    result = await config_fn(config_payload(opts, 'present', exported['private_key_file']))
    return {**opts, 'blue/exit': 0} if result.get('exit', 1) == 0 else config_failure(opts, result)


async def uninstall_step(opts, export_fn=None, config_fn=None):
    if machine.planning(opts):
        return opts
    export_fn, config_fn = export_fn or export_operation, config_fn or update_config
    lock(opts)
    install_lock(opts)
    exported = await export_fn(opts, 'inspect')
    if exported['status'] not in ('installed', 'absent'):
        return machine.failure(opts, exported)
    result = await config_fn(config_payload(opts, 'absent', None))
    if result.get('exit', 1) != 0:
        return config_failure(opts, result)
    removed = await export_fn(opts, 'remove')
    return {**opts, 'blue/exit': 0} if removed['status'] in ('removed', 'absent') else machine.failure(opts, removed)
