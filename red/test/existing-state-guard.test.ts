import {test,expect,spyOn} from 'bun:test';
import {workflow,run} from 'red/workflow';
import * as w from '../src/workflow.ts';
import * as machine from '../src/machine.ts';
import * as github from '../src/github.ts';
const input={profile:'guard-test',workdir:'.once','provider-compute':'digitalocean','digitalocean-region':'ams3','digitalocean-size':'small','digitalocean-image':'ubuntu','digitalocean-ssh-keys':'external','provider-backend':'s3','s3-bucket':'states','s3-region':'eu-west-1','provider-dns':'cloudflare','provider-smtp':'resend','compute-ssh-sources':['0.0.0.0/0'],'compute-http-sources':['0.0.0.0/0'],once:{applications:[{host:'app.example.com',image:'example/app:latest'}]},'compute-require-existing-state':true};
const env={COLORS_PAR_DO_TOKEN:'fixture',COLORS_PAR_RESEND_API_KEY:'fixture',COLORS_PAR_RESEND_PASSWORD:'fixture',COLORS_PAR_CLOUDFLARE_API_TOKEN:'fixture'};
for(const present of [false,true])test(`guarded start permits provider branches only with recorded ownership: ${present}`,async()=>{
 const calls:string[]=[];
 const load=spyOn(machine,'load').mockImplementation(async(o,e)=>{expect(e).toEqual(env);calls.push('read');return {...o,'red/exit':present?0:1};});
 const keys=spyOn(github,'generateKeys').mockImplementation(async()=>{calls.push('keys');return [[],undefined];});
 try{
  const wf=workflow({start:'once/start',wireFn:(step,o)=>{const edge=w.wireFn(step,o);if(!edge)return undefined;return [step==='once/start'?(values:any)=>w.startStep(values,env):async(values:any)=>{calls.push(step);return {...values,'red/exit':0};},...edge.slice(1)] as any;}});
  const result=await run(wf,{...input,'red/event':'create'});
  expect(result['red/exit']).toBe(present?0:1);expect(calls.slice(0,2)).toEqual(present?['read','keys']:['read']);
  expect(calls.includes('once/tofu-smtp')).toBe(present);expect(calls.includes('once/tofu-compute')).toBe(present);
 }finally{load.mockRestore();keys.mockRestore();}
});
for(const [event,dry] of [['build',false],['create',true]] as const)test(`offline guarded ${event} does not read state`,async()=>{
 const load=spyOn(machine,'load').mockImplementation(async()=>{throw Error('offline state read');});
 try{expect((await w.startStep({...input,'red/event':event,'red/dry-run':dry},{}))['red/exit']).toBe(0);expect(load).not.toHaveBeenCalled();}finally{load.mockRestore();}
});
