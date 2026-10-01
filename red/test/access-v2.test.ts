import {test,expect} from 'bun:test';
import {mkdtempSync,rmSync,symlinkSync,readFileSync,mkdirSync,writeFileSync,linkSync,statSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import * as access from '../src/access.ts';
import * as machine from '../src/machine.ts';
import {ansibleEnvironment,ansibleOnce} from '../src/tools.ts';

test('a scoped POSIX profile lock excludes another manager and releases on failure',async()=>{
 const workdir=mkdtempSync(join(tmpdir(),'once-lock-'));const opts={workdir,profile:'test'};
 try {
  await expect(access.scoped(async()=>{await access.lock(opts);await expect(access.scoped(()=>access.lock(opts))).rejects.toThrow('another ONCE operation');throw Error('body failed');})).rejects.toThrow('body failed');
  await access.scoped(()=>access.lock(opts));
 }finally{rmSync(workdir,{recursive:true,force:true});}
});
test('profile locks refuse symlinked directories',async()=>{
 const root=mkdtempSync(join(tmpdir(),'once-lock-'));symlinkSync(tmpdir(),join(root,'test'));
 try{await expect(access.scoped(()=>access.lock({workdir:root,profile:'test'}))).rejects.toThrow('profile lock is unsafe');}finally{rmSync(root,{recursive:true,force:true});}
});
test('agent identities cannot fall back to the ambient agent',()=>{
 expect(access.identityArgs({'ssh-private-key-path':'/identity.pub'})).toContain('IdentityAgent=none');
 expect(access.identityArgs({'ssh-private-key-path':'/identity.pub','once/agent-socket':'/scope.sock'})).toContain('IdentityAgent=/scope.sock');
 expect(access.identityArgs({'ssh-private-key-path':'/identity.pub'})).toContain('ForwardAgent=no');
});
test('Ansible receives only selected SMTP and app runtime secrets',()=>{
 const opts:any={'provider-smtp':'resend','resend-password':'smtp-secret','app-key':'app-secret','once-ssh-passphrase':'private','github-token':'private','r2-secret-access-key':'private',once:{applications:[{host:'www.example.com',image:'image',env:{APP_KEY:'app-key',FORBIDDEN:'once-ssh-passphrase'}}]}};
 expect(ansibleEnvironment(opts)).toEqual({ONCE_PAR_RESEND_PASSWORD:'smtp-secret',ONCE_PAR_APP_KEY:'app-secret'});
 const rendered=ansibleOnce(opts);expect(rendered).toContain('ONCE_PAR_APP_KEY');expect(rendered).not.toContain('app-secret');expect(rendered).not.toContain('smtp-secret');
 expect(machine.libraryOptions(opts)).not.toHaveProperty('once-ssh-passphrase');
});
test('v1 configuration is refused before inspecting any authority',()=>{
 expect(machine.errors({})).toEqual(['compute-api-version must be 2; existing deployments must retain their pinned launchers']);
});

test('guarded create inspects registration before node ownership can be checked',async()=>{
 const operations:string[]=[];
 const opts:any={profile:'guard','provider-compute':'digitalocean',workdir:'/tmp/unused-once-guard','red/event':'create','once/ssh-resource':machine.placeholderResource,'compute-require-existing-state':true};
 const run:any=async (_opts:any,_request:any,operation:string)=>{operations.push(operation);return access.placeholderRegistration(opts);};
 await access.registrationStep(opts,run);
 await access.registrationStep({...opts,'compute-require-existing-state':false},run);
 expect(operations).toEqual(['inspect','create']);
});


test('build files are atomic private and repeatable, refusing linked files and directories',()=>{
 const root=mkdtempSync(join(tmpdir(),'once-build-'));const directory=join(root,'profile','node');
 const plan={directory,documents:{'compute.tf.json':{z:1,a:2},'backend.tf.json':{}}};
 try{
  machine.writeBuild(plan);machine.writeBuild(plan);
  expect(readFileSync(join(directory,'compute.tf.json'),'utf8')).toBe('{\n  "a": 2,\n  "z": 1\n}\n');
  expect(statSync(join(directory,'compute.tf.json')).mode&0o777).toBe(0o600);
  const victim=join(root,'victim');writeFileSync(victim,'unchanged');
  rmSync(join(directory,'compute.tf.json'));symlinkSync(victim,join(directory,'compute.tf.json'));
  expect(()=>machine.writeBuild(plan)).toThrow('unsafe compute file');expect(readFileSync(victim,'utf8')).toBe('unchanged');
  rmSync(join(directory,'compute.tf.json'));linkSync(victim,join(directory,'compute.tf.json'));
  expect(()=>machine.writeBuild(plan)).toThrow('unsafe compute file');
  const linked=join(root,'linked');symlinkSync(directory,linked);
  expect(()=>machine.writeBuild({...plan,directory:join(linked,'new')})).toThrow('unsafe compute directory');
 }finally{rmSync(root,{recursive:true,force:true});}
});

test('explicit external identity paths are refused before sanitizing library options',()=>{
 for(const key of ['ssh-key-path','ssh-private-key-path','ssh-public-key-path'])
  expect(machine.errors({'compute-api-version':2,'provider-backend':'r2',[key]:null})).toEqual(['external SSH keys are outside the single-node contract']);
});
