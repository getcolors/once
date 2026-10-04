import asyncio, json, sys
from package_once_blue import access, workflow, machine, tools
fixture=json.load(open(sys.argv[1]))
access.lock=lambda *_: None
access.install_lock=lambda *_: None
async def main():
    rows=[]
    for case in fixture['cases']:
        calls=[]
        async def export(opts, op):
            calls.append(op)
            if case.get('export_failure')==op: return {'status':'error','error':{'message':'fixture export refusal'}}
            return {'status':'absent' if case.get('absent') else ('removed' if op=='remove' else 'installed'),'private_key_file':'/fixture/encrypted'}
        async def config(payload):
            phase='preflight' if payload.get('check_only') else payload['block_state']
            calls.append(phase)
            return {'exit':7 if case.get('config_failure')==phase else 0,'err':'fixture config refusal'}
        opts={**fixture['base'],'blue/event':'ssh-'+case['command'],'blue/dry-run':case.get('dry_run',False)}
        fn=access.install_step if case['command']=='install' else access.uninstall_step
        result=await fn(opts,export,config)
        rows.append([case['name'],calls,result.get('blue/exit'),result.get('blue/err')])
    for state in ['installed','absent','error']:
        calls=[]
        async def export(opts,op):
            calls.append(op)
            return {'status':state,'private_key_file':'/fixture/encrypted','error':{'message':'fixture ownership refusal'}}
        try: value=await access.installed_identity({**fixture['base'],'blue/event':'create'},export)
        except Exception as error: value=str(error)
        rows.append(['preserve-'+state,calls,value])
    for event,dry in [('build',False),('create',True)]:
        calls=[]
        async def export(opts,op): calls.append(op); raise RuntimeError('unexpected export')
        value=await access.installed_identity({**fixture['base'],'blue/event':event,'blue/dry-run':dry},export)
        rows.append(['preserve-'+event+('-dry' if dry else ''),calls,value])
    workflow.state_errors=lambda _: []
    for command,dry in [('ssh-install',False),('ssh-uninstall',False),('ssh-install',True),('ssh-uninstall',True)]:
        calls=[]
        async def resource(opts):
            if not machine.planning(opts): calls.append('resource-inspect')
            return opts
        async def registration(opts):
            if not machine.planning(opts): calls.append('registration-inspect')
            return opts
        async def connection(opts,*_):
            if not machine.planning(opts): calls.append('live-connection')
            return opts
        async def agent(opts): calls.append('agent'); return opts
        access.resource_step=resource; access.registration_step=registration
        access.agent_step=agent; machine.load=connection
        result=await workflow.start_step({**fixture['base'],'blue/event':command,'blue/dry-run':dry},{})
        rows.append(['start-'+command+('-dry' if dry else ''),calls,result.get('blue/exit',0)])
    for command in ['ssh-install','ssh-uninstall']:
        rows.append(['graph-'+command,[list(workflow.wire_fn(step,{'blue/event':command})[1:]) for step in ['once/start','once/'+command]]])
    for event,identity in [('create','/fixture/encrypted'),('create',None),('delete','/fixture/encrypted')]:
        calls=[]
        async def installed(opts): calls.append('inspect'); return identity
        async def ansible(opts,specs,**config): return config['extra_vars']
        access.installed_identity=installed; tools.ansible_with_spec=ansible
        result=await tools.ansible_local_step({**fixture['base'],'workdir':'/fixture/workdir','ssh-private-key-path':'/fixture/public.pub','blue/event':event})
        rows.append(['local-'+event+('-installed' if identity else '-absent'),calls,result])
    rows.append(['payload',access.config_payload(fixture['base'],'present','/fixture/encrypted'),access.config_payload(fixture['base'],'absent',None)])
    print(json.dumps(rows,sort_keys=True,separators=(',',':')))
asyncio.run(main())
