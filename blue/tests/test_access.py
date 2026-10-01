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
