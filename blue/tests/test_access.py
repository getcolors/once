import asyncio
import subprocess
import sys
from pathlib import Path

import pytest
from blue.runtime import ExecResult
from package_once_blue import access, machine, tools
from test_once import valid


def test_v2_is_explicit_and_external_keys_are_refused():
    assert machine.errors({**valid, 'compute-api-version': 1}) == [machine.API_ERROR]
    assert machine.errors({**valid, 'provider-backend': 'local'}) == ['compute state requires an s3 or r2 backend']
    assert machine.errors({**valid, 'ssh-private-key-path': '/tmp/private'}) == ['external SSH keys are outside the single-node contract']
    assert machine.errors({**valid, 'digitalocean-ssh-keys': 'external'}) == ['external SSH keys are outside the single-node contract']


def test_provider_adapter_excludes_runtime_data_and_passphrase():
    options = machine.library_options({**valid, 'once-ssh-passphrase': 'secret', 'blue.workflow/inherited': object(), 'once/agent-socket': '/tmp/socket', 'ssh-private-key-path': '/tmp/public'})
    assert 'once-ssh-passphrase' not in options
    assert all('/' not in key for key in options)
    assert 'ssh-private-key-path' not in options


def test_only_selected_application_runtime_secrets_are_forwarded():
    opts = {'provider-smtp': 'resend', 'resend-password': 'smtp', 'resend-api-key': 'provider', 'once-ssh-passphrase': 'authority', 'app-key': 'application', 'once': {'applications': [{'env': {'TOKEN': 'app-key', 'MUST_NOT_PASS': 'once-ssh-passphrase'}}]}}
    assert tools.runtime_secret_env(opts) == {'ONCE_PAR_RESEND_PASSWORD': 'smtp', 'ONCE_PAR_APP_KEY': 'application'}
    assert tools.runtime_secret_env({**opts, 'app-key': False})['ONCE_PAR_APP_KEY'] == 'false'


async def test_scoped_profile_lock_interoperates_with_posix_record_lock(tmp_path):
    opts = {**valid, 'workdir': str(tmp_path)}
    path = tmp_path / 'test' / '.once.lock'
    code = 'import fcntl,sys; f=open(sys.argv[1],"r+"); fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)'
    async def body():
        access.lock(opts)
        with pytest.raises(ValueError, match='another ONCE operation owns this profile'):
            access.lock(opts)
        result = subprocess.run([sys.executable, '-c', code, str(path)], capture_output=True)
        assert result.returncode != 0
    await access.scoped(body)
    assert subprocess.run([sys.executable, '-c', code, str(path)], capture_output=True).returncode == 0


async def test_scope_cleans_up_agent_after_failure(monkeypatch):
    closed = []
    async def start(resources, environment, register):
        register('resource', lambda: closed.append(True))
        return {'socket': '/tmp/scoped.sock', 'identities': {machine.PLACEHOLDER['reference']: '/tmp/public'}}
    monkeypatch.setattr(access, 'start_agent', start)
    async def body():
        result = await access.agent_step({**valid, 'blue/event': 'create', 'once/ssh-resource': machine.PLACEHOLDER})
        assert 'IdentityAgent=/tmp/scoped.sock' in access.identity_args(result)
        raise RuntimeError('failure')
    with pytest.raises(RuntimeError, match='failure'):
        await access.scoped(body)
    assert closed == [True]


async def test_registration_retirement_preserves_encrypted_authority(monkeypatch):
    calls = []
    async def run(opts, request, operation):
        calls.append((request['state_filename'], operation))
        return {'status': 'destroyed'}
    monkeypatch.setattr(access, 'compute_registration', run)
    result = await access.registration_delete({**valid, 'once/ssh-resource': machine.PLACEHOLDER})
    assert result['blue/exit'] == 0
    assert calls == [('once-ssh-registration.tfstate', 'delete')]


@pytest.mark.asyncio
async def test_install_preflights_before_export_and_retains_on_config_failure(monkeypatch):
    monkeypatch.setattr(access, 'install_lock', lambda _: None)
    calls = []
    async def export(opts, operation):
        calls.append(operation)
        return {'status': 'installed', 'private_key_file': '/tmp/encrypted-identity'}
    async def config(payload):
        calls.append('preflight' if payload.get('check_only') else 'config')
        return {'exit': 0 if payload.get('check_only') else 1, 'err': 'collision'}
    result = await access.install_step({**valid, 'blue/event': 'ssh-install', 'ip': '192.0.2.1', 'user': 'root'}, export, config)
    assert calls == ['preflight', 'install', 'config']
    assert result['blue/exit'] == 1
    assert result['blue/err'] == 'SSH config update failed: collision'


@pytest.mark.asyncio
async def test_uninstall_removes_aliases_before_export_and_is_dry_run_safe(monkeypatch):
    calls = []
    monkeypatch.setattr(access, 'lock', lambda _: calls.append('lock'))
    monkeypatch.setattr(access, 'install_lock', lambda _: calls.append('install-lock'))
    async def export(opts, operation):
        calls.append(operation)
        return {'status': 'installed' if operation == 'inspect' else 'removed'}
    async def config(payload):
        calls.append('config')
        assert payload['ssh_hosts'] == []
        return {'exit': 0}
    opts = {**valid, 'blue/event': 'ssh-uninstall'}
    assert (await access.uninstall_step(opts, export, config))['blue/exit'] == 0
    assert calls == ['lock', 'install-lock', 'inspect', 'config', 'remove']
    calls.clear()
    await access.uninstall_step({**opts, 'blue/dry-run': True}, export, config)
    await access.install_step({**opts, 'blue/dry-run': True}, export, config)
    assert calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize('kind', ['parent-link', 'ssh-link', 'lock-link', 'hardlink', 'owner'])
async def test_install_lock_refuses_unsafe_paths(tmp_path, monkeypatch, kind):
    import os
    home = tmp_path.resolve() / 'home'
    home.mkdir()
    ssh_dir = home / '.ssh'
    ssh_dir.mkdir()
    other = tmp_path.resolve() / 'other'
    other.mkdir()
    if kind == 'parent-link':
        link = tmp_path.resolve() / 'link'
        link.symlink_to(home, target_is_directory=True)
        home = link
    elif kind == 'ssh-link':
        ssh_dir.rmdir()
        ssh_dir.symlink_to(other, target_is_directory=True)
    elif kind in ('lock-link', 'hardlink'):
        target = other / 'file'
        target.write_text('untouched')
        lock_file = ssh_dir / '.once-install-demo.lock'
        if kind == 'lock-link':
            lock_file.symlink_to(target)
        else:
            os.link(target, lock_file)
    elif kind == 'owner':
        uid = os.getuid()
        monkeypatch.setattr(access.os, 'getuid', lambda: uid + 1)
    async def body():
        with pytest.raises(ValueError, match='unsafe SSH directory owner' if kind == 'owner' else 'unsafe ONCE installation lock'):
            access.install_lock({'profile': 'demo'}, home)
    await access.scoped(body)
    if kind in ('lock-link', 'hardlink'):
        assert target.read_text() == 'untouched'


@pytest.mark.asyncio
async def test_install_lock_serializes_and_releases(tmp_path):
    home = tmp_path.resolve()
    async def body():
        access.install_lock({'profile': 'demo'}, home)
        with pytest.raises(ValueError, match='another ONCE operation owns this SSH installation'):
            access.install_lock({'profile': 'demo'}, home)
    await access.scoped(body)
    await access.scoped(body)


@pytest.mark.asyncio
async def test_uninstall_preflight_is_offline(monkeypatch):
    from package_once_blue.workflow import start_step
    def unexpected(*args, **kwargs):
        pytest.fail('offline uninstall touched runtime resources')
    for name in ('resource_step', 'registration_step', 'agent_step', 'lock'):
        monkeypatch.setattr(access, name, unexpected)
    monkeypatch.setattr(machine, 'load', unexpected)
    result = await start_step({**valid, 'blue/event': 'ssh-uninstall'}, {})
    assert not result.get('blue/exit')
