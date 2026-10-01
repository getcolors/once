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
