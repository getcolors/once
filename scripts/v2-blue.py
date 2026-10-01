import asyncio, json, sys
from pathlib import Path
from package_once_blue import access, machine, tools, workflow, github
fixture=json.load(open(sys.argv[1]))
operations=[]
async def mock_registration(opts, request, operation):
    operations.append(operation)
    return {"status":"ready"}
async def run_registration():
    access.compute_registration=mock_registration
    for c in fixture['registration']:
        await access.registration_step({**fixture['base'],'provider-compute':'digitalocean','blue/event':c['event'],'compute-require-existing-state':c['guard'],'once/ssh-resource':machine.PLACEHOLDER})
asyncio.run(run_registration())
key_path=None
key_alive=False
async def key_scope():
    global key_path,key_alive
    keys,error=await github.generate_keys({'profile':'parity','once':{'applications':[{'host':'www.example.com','image':'example','github':'example/site'}]}})
    if error: raise RuntimeError(error)
    key_path=Path(keys[0]['private-file'])
    key_alive=key_path.exists()
    raise RuntimeError('fixture stage failure')
try:
    asyncio.run(access.scoped(key_scope))
except RuntimeError as error:
    if str(error)!='fixture stage failure': raise
result={"key_cleanup":[key_alive,not key_path.exists()],"registration_operations":operations,"graphs":[[g["event"],[[s,list(workflow.wire_fn("once/"+s,{"blue/event":g["event"]})[1:])] for s in g["steps"]]] for g in fixture["graphs"]],"errors":[[c['name'],machine.errors({**fixture['base'],**c['opts']})] for c in fixture['cases']],"identity_args":[access.identity_args(o) for o in fixture['access']],"ssh_args":[access.ssh_args(o) for o in fixture['access']],"secret_env":tools.runtime_secret_env(fixture['secrets'])}
print(json.dumps(result,sort_keys=True,separators=(',',':')))
