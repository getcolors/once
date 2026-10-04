import {readFileSync} from 'node:fs';
import {spyOn} from 'bun:test';
import * as ansible from '../red/node_modules/red/src/ansible.ts';
import * as tools from '../red/src/tools.ts';
import * as workflow from '../red/src/workflow.ts';
import * as validate from '../red/src/validate.ts';
import * as machine from '../red/src/machine.ts';
import * as access from '../red/src/access.ts';
const fixture=JSON.parse(readFileSync(process.argv[2]!, 'utf8'));
const rows:any[]=[]; const noLock=async()=>{};
for(const c of fixture.cases){
 const calls:string[]=[];
 const exp=async(_opts:any,op:string)=>{calls.push(op);return c.export_failure===op?{status:'error',error:{message:'fixture export refusal'}}:{status:c.absent?'absent':op==='remove'?'removed':'installed',private_key_file:'/fixture/encrypted'};};
 const config=async(payload:any)=>{const phase=payload.check_only?'preflight':payload.block_state;calls.push(phase);return {exit:c.config_failure===phase?7:0,err:'fixture config refusal'};};
 const opts={...fixture.base,'red/event':'ssh-'+c.command,'red/dry-run':!!c.dry_run};
 const result=await (c.command==='install'?access.installStep:access.uninstallStep)(opts,exp as any,config,noLock);
 rows.push([c.name,calls,result['red/exit']??null,result['red/err']??null]);
}
for(const status of ['installed','absent','error']){
 const calls:string[]=[];const exp=async(_opts:any,op:string)=>{calls.push(op);return {status,private_key_file:'/fixture/encrypted',error:{message:'fixture ownership refusal'}};};
 let value:any;try{value=await access.installedIdentity({...fixture.base,'red/event':'create'},exp as any,noLock);}catch(error){value=(error as Error).message;}
 rows.push(['preserve-'+status,calls,value??null]);
}
for(const [event,dry] of [['build',false],['create',true]]){
 const calls:string[]=[]; const exp=async(_opts:any,op:string)=>{calls.push(op);throw Error('unexpected export');};
 const value=await access.installedIdentity({...fixture.base,'red/event':event,'red/dry-run':dry},exp as any,noLock);
 rows.push(['preserve-'+event+(dry?'-dry':''),calls,value??null]);
}
spyOn(validate,'stateErrors').mockImplementation(()=>[]);
for(const [command,dry] of [['ssh-install',false],['ssh-uninstall',false],['ssh-install',true],['ssh-uninstall',true]]){
 const calls:string[]=[];
 spyOn(access,'resourceStep').mockImplementation(async(opts:any)=>{if(!machine.planning(opts))calls.push('resource-inspect');return opts;});
 spyOn(access,'registrationStep').mockImplementation(async(opts:any)=>{if(!machine.planning(opts))calls.push('registration-inspect');return opts;});
 spyOn(access,'connectionStep').mockImplementation(async(opts:any)=>{if(!machine.planning(opts))calls.push('live-connection');return opts;});
 spyOn(access,'agentStep').mockImplementation(async(opts:any)=>{calls.push('agent');return opts;});
 const result=await workflow.startStep({...fixture.base,'red/event':command,'red/dry-run':dry},{});
 rows.push(['start-'+command+(dry?'-dry':''),calls,result['red/exit']??0]);
}
for(const command of ['ssh-install','ssh-uninstall']) rows.push(['graph-'+command,['once/start','once/'+command].map(step=>workflow.wireFn(step,{'red/event':command})!.slice(1))]);
for(const [event,identity] of [['create','/fixture/encrypted'],['create',undefined],['delete','/fixture/encrypted']]){
 const calls:string[]=[];
 spyOn(access,'installedIdentity').mockImplementation(async()=>{calls.push('inspect');return identity;});
 spyOn(access,'installLock').mockImplementation(noLock);
 spyOn(ansible,'ansibleWithSpec').mockImplementation(async(_opts:any,config:any)=>config.extraVars);
 const result=await tools.ansibleLocalStep({...fixture.base,workdir:'/fixture/workdir','ssh-private-key-path':'/fixture/public.pub','red/event':event});
 rows.push(['local-'+event+(identity?'-installed':'-absent'),calls,result]);
}
rows.push(['payload',access.configPayload(fixture.base,'present','/fixture/encrypted'),access.configPayload(fixture.base,'absent',null)]);
function sorted(v:any):any{return Array.isArray(v)?v.map(sorted):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,sorted(v[k])])):v;}
console.log(JSON.stringify(sorted(rows)));
