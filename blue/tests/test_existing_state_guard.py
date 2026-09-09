import pytest
from blue.workflow import run, workflow
from package_once_blue import workflow as w
from test_once import valid

ENV = {'COLORS_PAR_DO_TOKEN':'fixture', 'COLORS_PAR_RESEND_API_KEY':'fixture',
       'COLORS_PAR_RESEND_PASSWORD':'fixture', 'COLORS_PAR_CLOUDFLARE_API_TOKEN':'fixture'}

@pytest.mark.parametrize('present', [False, True])
async def test_guarded_start_blocks_every_branch_before_key_generation(monkeypatch, present):
    calls=[]
    async def load(opts, env):
        assert env==ENV
        calls.append('read')
        return {**opts,'blue/exit':0 if present else 1}
    async def keys(opts):
        calls.append('keys')
        return [],None
    monkeypatch.setattr(w.machine,'load',load)
    monkeypatch.setattr(w.github,'generate_keys',keys)
    def wire(step,opts):
        original=w.wire_fn(step,opts)
        async def fake(o):
            calls.append(step)
            return {**o,'blue/exit':0}
        async def start(o):return await w.start_step(o,ENV)
        return ((start if step=='once/start' else fake),*original[1:]) if original else None
    result=await run(workflow(start='once/start',wire_fn=wire),{**valid,'blue/event':'create','compute-require-existing-state':True})
    assert result['blue/exit']==(0 if present else 1)
    assert calls[:2]==(['read','keys'] if present else ['read'])
    assert ('once/tofu-smtp' in calls) is present
    assert ('once/tofu-compute' in calls) is present

@pytest.mark.parametrize('event,dry',[('build',False),('create',True)])
async def test_guarded_offline_planning_never_reads_state(monkeypatch,event,dry):
    async def load(*args):raise AssertionError('offline state read')
    monkeypatch.setattr(w.machine,'load',load)
    result=await w.start_step({**valid,'blue/event':event,'blue/dry-run':dry,'compute-require-existing-state':True},{})
    assert result['blue/exit']==0

async def test_retired_delete_stops_before_cleanup_without_key_files(monkeypatch):
    from package_once_blue import machine
    calls=[]
    async def inspect(opts,env):
        calls.append('read')
        return {'status':'destroyed'}
    async def unwanted(*args):raise AssertionError('retired delete must not read app state or touch keys')
    monkeypatch.setattr(machine,'read_deployment',inspect)
    monkeypatch.setattr(w,'_state_output',unwanted)
    monkeypatch.setattr(w.github,'generate_keys',unwanted)
    def wire(step,opts):
        original=w.wire_fn(step,opts)
        async def start(o):return await w.start_step(o,ENV)
        async def downstream(o):
            calls.append(step)
            return {**o,'blue/exit':0}
        return ((start if step=='once/start' else downstream),*original[1:]) if original else None
    opts={**valid,'blue/event':'delete','compute-prevent-destroy':False}
    opts.pop('digitalocean-ssh-keys')
    result=await run(workflow(start='once/start',wire_fn=wire,next_fn=w.next_steps),opts)
    assert result['blue/exit']==0 and calls==['read']
    assert (await machine.load({**opts,'blue/event':'create'},{}))['blue/exit']==1
    assert w.next_steps('once/start',['once/github'],{'blue/exit':1})==[]
    assert w.wire_fn('once/tofu-dns',opts)[1:]==('once/tofu-smtp',)
    assert w.wire_fn('once/tofu-smtp',opts)[1:]==('once/tofu-compute',)

async def test_failed_alias_cleanup_never_reaches_remote(monkeypatch):
    async def local(opts):return {**opts,'blue/exit':1}
    async def remote(opts):raise AssertionError('remote cleanup after local failure')
    monkeypatch.setattr(w.tools,'ansible_local_step',local)
    monkeypatch.setattr(w.tools,'ansible_remote_step',remote)
    assert (await w.ansible_cleanup_step({'blue/event':'delete'}))['blue/exit']==1
