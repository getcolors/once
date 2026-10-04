/** Runtime capabilities remain scoped, never serialized in workflow opts. */
import {AsyncLocalStorage} from 'node:async_hooks';
import {existsSync} from 'node:fs';
import {join} from 'node:path';
import {homedir} from 'node:os';
import sshConfig from '../resources/tools/ansible-local/ssh_config.py' with {type:'text'};
import {withScope,type RegisterFinalizer} from 'red/scope';
import {runtime} from 'red/runtime';
import type {Opts} from 'red/workflow';
import {ssh_resource,ssh_export,start_agent,compute_registration,registration_plan,resolve_connection} from 'colors-compute-red';
import * as machine from './machine.ts';
const scope=new AsyncLocalStorage<RegisterFinalizer>();
export function registerCleanup(cleanup:()=>unknown|Promise<unknown>):void{scope.getStore()?.("resource",cleanup);}
export const scoped=<T>(fn:()=>Promise<T>)=>withScope(register=>scope.run(register,fn));
const register=()=>{const value=scope.getStore();if(!value)throw Error('ONCE runtime requires an access scope');return value;};
const lockScript=`import os,sys,fcntl,stat\npath=sys.argv[1]\nroot=os.path.dirname(path)\nparts=root.split('/')\ncursor='/'\nfor part in parts:\n if not part: continue\n cursor=os.path.join(cursor,part)\n try: os.mkdir(cursor,0o700)\n except FileExistsError: pass\n st=os.lstat(cursor)\n if not stat.S_ISDIR(st.st_mode): raise RuntimeError('unsafe profile directory')\nfor owned in (os.path.dirname(root),root):\n if os.stat(owned).st_uid!=os.getuid(): raise RuntimeError('unsafe profile ownership')\n os.chmod(owned,0o700)\nfd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW|os.O_NONBLOCK,0o600)\nst=os.fstat(fd)\nif not stat.S_ISREG(st.st_mode) or st.st_nlink!=1 or st.st_uid!=os.getuid(): raise RuntimeError('unsafe profile lock')\nos.fchmod(fd,0o600)\ntry: fcntl.lockf(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)\nexcept BlockingIOError: sys.exit(3)\nprint('locked',flush=True)\nsys.stdin.read()\n`;
export async function lock(opts:Opts){const cleanup=register();const child=Bun.spawn(['python3','-c',lockScript,join(machine.sdkWorkdir(opts),String(opts.profile),'.once.lock')],{stdin:'pipe',stdout:'pipe',stderr:'pipe'});const reader=child.stdout.getReader();const first=await reader.read();reader.releaseLock();if(!first.value||new TextDecoder().decode(first.value).trim()!=='locked'){child.stdin.end();await child.exited;throw Error('another ONCE operation owns this profile or the profile lock is unsafe');}cleanup('resource',async()=>{child.stdin.end();await child.exited;});}
export async function resourceStep(opts:Opts,run=ssh_resource):Promise<Opts>{if(!opts['red/dry-run'])await lock(opts);const existing=existsSync(join(machine.sdkWorkdir(opts),String(opts.profile),machine.nodeId,'compute.tf.json'));const operation=existing||opts['compute-require-existing-state']||!['build','create'].includes(String(opts['red/event']))?'inspect':'create';const result=machine.planning(opts)?machine.placeholderResource:await run(machine.libraryOptions(opts),machine.sshRequest(opts),operation,process.env);return result.status==='ready'?{...opts,'once/ssh-resource':result,'red/exit':0}:machine.failedResult(opts,result);}
export function placeholderRegistration(opts:Opts){return {status:'ready',reference:'registration:build-placeholder',provider:opts['provider-compute'],ssh_resource_reference:machine.resource(opts).reference,fingerprint:machine.resource(opts).fingerprint,id:'0'};}
export async function registrationStep(opts:Opts,run=compute_registration):Promise<Opts>{if(!machine.registration(opts)||opts['red/dry-run'])return opts;const result:any=machine.planning(opts)?{...registration_plan(machine.libraryOptions(opts),machine.registrationRequest(opts)),status:'built'}:await run(machine.libraryOptions(opts),machine.registrationRequest(opts),opts['red/event']==='create'&&!opts['compute-require-existing-state']?'create':'inspect');if(result.status==='built')machine.writeBuild(result);if(result.status==='destroyed'&&opts['red/event']==='delete')return {...opts,'once/ssh-registration':placeholderRegistration(opts),'once/registration-destroyed':true,'red/exit':0};return ['ready','built'].includes(result.status)?{...opts,'once/ssh-registration':result.status==='ready'?result:placeholderRegistration(opts),'red/exit':0}:machine.failedResult(opts,result);}
export async function agentStep(opts:Opts,run=start_agent):Promise<Opts>{if(machine.planning(opts))return {...opts,'ssh-private-key-path':machine.placeholderKey(opts),'once/agent-socket':'/home/build-placeholder/agent.sock','red/exit':0};const agent=await run([{opts:machine.libraryOptions(opts),request:machine.sshRequest(opts),resource:machine.resource(opts)}],process.env,register());return {...opts,'once/agent-socket':agent.socket,'ssh-private-key-path':agent.identities[machine.resource(opts).reference],'red/exit':0};}
export async function registrationDeleteStep(opts:Opts):Promise<Opts>{if(!machine.registration(opts))return opts;const result=await compute_registration(machine.libraryOptions(opts),machine.registrationRequest(opts),'delete');return result.status==='destroyed'?{...opts,'red/exit':0}:machine.failedResult(opts,result);}
export function identityArgs(opts:Opts):string[]{return opts['ssh-private-key-path']?['-F','/dev/null','-o','IdentityFile=none','-i',String(opts['ssh-private-key-path']),'-o','IdentitiesOnly=yes','-o','IdentityAgent='+String(opts['once/agent-socket']??'none'),'-o','ForwardAgent=no','-o','ControlMaster=no','-o','ControlPersist=no','-S','none']:[];}
export async function connectionStep(opts:Opts):Promise<Opts>{if(machine.planning(opts))return opts;const result:any=await resolve_connection(machine.libraryOptions(opts),machine.request(opts));if(result.status!=='ready')return machine.failedResult(opts,result);return {...opts,...result.params,'once/compute-params':machine.params(opts,result),'colors-compute/node':result.params,'red/exit':0};}
export function sshArgs(opts:Opts):string[]{return ['ssh','-p','22','-l',String(opts.user),'-o','StrictHostKeyChecking=accept-new',...identityArgs(opts),'--',String(opts.ip)];}
export async function sshStep(opts:Opts):Promise<Opts>{if(machine.planning(opts))return opts;if([opts.ip,opts.user,opts['ssh-private-key-path'],opts['once/agent-socket']].some(value=>typeof value!=='string'||!value.trim()))return {...opts,'red/exit':1,'red/err':'SSH requires a resolved address, login and scoped identity'};const result=await runtime.execInherit(sshArgs(opts));return {...opts,'red/exit':result.exit};}

export const exportDirectory=(opts:Opts)=>join(process.env.HOME??homedir(),'.ssh','once',String(opts.profile));
export async function installLock(opts:Opts){
  const cleanup=scope.getStore();
  if(!cleanup)throw Error('ONCE installation requires an access scope');
  if(typeof opts.profile!=='string'||! /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/.test(opts.profile))throw Error('invalid SSH deployment alias');
  // Known exit codes keep filesystem diagnostics deterministic and path-free.
  const script=`import os,sys,fcntl,stat
path=sys.argv[1]
root=os.path.dirname(path)
try:
 cursor='/'
 for part in root.split('/'):
  if not part: continue
  cursor=os.path.join(cursor,part)
  try: os.mkdir(cursor,0o700)
  except FileExistsError: pass
  if not stat.S_ISDIR(os.lstat(cursor).st_mode): sys.exit(1)
 if os.stat(root).st_uid!=os.getuid(): sys.exit(4)
 os.chmod(root,0o700)
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW|os.O_NONBLOCK,0o600)
 st=os.fstat(fd)
 if not stat.S_ISREG(st.st_mode) or st.st_nlink!=1 or st.st_uid!=os.getuid(): sys.exit(1)
 os.fchmod(fd,0o600)
 try: fcntl.lockf(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
 except BlockingIOError: sys.exit(3)
 print('locked',flush=True)
 sys.stdin.read()
except OSError: sys.exit(1)
`;
  const child=Bun.spawn(['python3','-c',script,join(process.env.HOME??homedir(),'.ssh',`.once-install-${opts.profile}.lock`)],{stdin:'pipe',stdout:'pipe',stderr:'pipe'});
  const reader=child.stdout.getReader();const first=await reader.read();reader.releaseLock();
  if(!first.value||new TextDecoder().decode(first.value).trim()!=='locked'){
    child.stdin.end();const code=await child.exited;
    throw Error(code===3?'another ONCE operation owns this SSH installation':code===4?'unsafe SSH directory owner':'unsafe ONCE installation lock');
  }
  cleanup('resource',async()=>{child.stdin.end();await child.exited;});
}
export async function exportOperation(opts:Opts,operation:string,run=ssh_export):Promise<any>{
  const env=operation==='install'?process.env:Object.fromEntries(['PATH','HOME','TMPDIR'].filter(key=>process.env[key]!==undefined).map(key=>[key,process.env[key]]));
  return run(machine.libraryOptions(opts),{...machine.sshRequest(opts),...(opts['once/ssh-resource']?{expected:opts['once/ssh-resource']}:{})},exportDirectory(opts),operation,env);
}
export async function installedIdentity(opts:Opts,exportFn=exportOperation,lockFn=installLock):Promise<string|undefined>{
  if(machine.planning(opts))return undefined;
  await lockFn(opts);const result=await exportFn(opts,'inspect');
  if(result.status==='installed')return result.private_key_file;
  if(result.status==='absent')return undefined;
  throw Error(result.error?.message??'Invalid installed SSH identity');
}
export function configPayload(opts:Opts,mode:string,identity?:string){
  return {host_alias:opts.profile,block_state:mode,keygen:true,installed:true,identity_file:identity??'',legacy_marker_prefix:'',ssh_hosts:mode==='absent'?[]:[{name:opts.profile,ip:opts.ip,user:opts.user}]};
}
export const updateConfig=async(payload:any)=>runtime.exec(['python3','-c','import io, sys\nsys.stdin = io.StringIO(sys.argv[1])\n'+sshConfig,JSON.stringify(payload)]);
const configFailure=(opts:Opts,result:any):Opts=>({...opts,'red/exit':result.exit??1,'red/err':'SSH config update failed: '+(result.err??'')});
export async function installStep(opts:Opts,exportFn=exportOperation,configFn=updateConfig,lockFn=installLock):Promise<Opts>{
  if(machine.planning(opts))return opts;
  await lockFn(opts);
  const preflight=await configFn({...configPayload(opts,'present',join(exportDirectory(opts),'identity')),check_only:true});
  if(preflight.exit!==0)return configFailure(opts,preflight);
  const exported=await exportFn(opts,'install');
  if(exported.status!=='installed')return machine.failedResult(opts,exported);
  const result=await configFn(configPayload(opts,'present',exported.private_key_file));
  return result.exit===0?{...opts,'red/exit':0}:configFailure(opts,result);
}
export async function uninstallStep(opts:Opts,exportFn=exportOperation,configFn=updateConfig,lockFn=async(opts:Opts)=>{await lock(opts);await installLock(opts);}):Promise<Opts>{
  if(machine.planning(opts))return opts;
  await lockFn(opts);
  const exported=await exportFn(opts,'inspect');
  if(!['installed','absent'].includes(exported.status))return machine.failedResult(opts,exported);
  const result=await configFn(configPayload(opts,'absent'));
  if(result.exit!==0)return configFailure(opts,result);
  const removed=await exportFn(opts,'remove');
  return ['removed','absent'].includes(removed.status)?{...opts,'red/exit':0}:machine.failedResult(opts,removed);
}
