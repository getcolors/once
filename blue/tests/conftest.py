import pytest

@pytest.fixture
def fake_access(monkeypatch):
    """Keep lifecycle tests process-free; exercise real access in its own suite."""
    from package_once_blue import access, machine
    monkeypatch.setattr(access, 'lock', lambda opts: None)
    async def resource(opts):
        return {**opts, 'once/ssh-resource': machine.PLACEHOLDER}
    async def registration(opts):
        return {**opts, 'once/ssh-registration': access.placeholder_registration(opts)}
    async def agent(opts):
        return {**opts, 'ssh-private-key-path': '/tmp/identity.pub', 'once/agent-socket': '/tmp/agent.sock'}
    monkeypatch.setattr(access, 'resource_step', resource)
    monkeypatch.setattr(access, 'registration_step', registration)
    monkeypatch.setattr(access, 'agent_step', agent)
    async def retired(opts):
        return opts
    monkeypatch.setattr(access, 'registration_delete', retired)
