import {readFileSync,existsSync} from 'node:fs';
import * as github from '../red/src/github.ts';
import * as access from '../red/src/access.ts';
import * as machine from '../red/src/machine.ts';
import * as workflow from '../red/src/workflow.ts';
import * as tools from '../red/src/tools.ts';
const fixture=JSON.parse(readFileSync(process.argv[2]!, 'utf8'));
const operations:string[]=[];
for(const c of fixture.registration) await access.registrationStep({...fixture.base,'provider-compute':'digitalocean','red/event':c.event,'compute-require-existing-state':c.guard,'once/ssh-resource':machine.placeholderResource}, (async (_opts:any,_request:any,operation:any)=>{operations.push(operation);return {status:'ready'};}) as any);
let keyPath='',keyAlive=false;
try {await access.scoped(async()=>{const [keys,error]=await github.generateKeys({profile:'parity',once:{applications:[{host:'www.example.com',image:'example',github:'example/site'}]}});if(error)throw Error(error);keyPath=keys[0]!.privateFile;keyAlive=existsSync(keyPath);throw Error('fixture stage failure');});}catch(error){if((error as Error).message!=='fixture stage failure')throw error;}
const result={key_cleanup:[keyAlive,!existsSync(keyPath)],registration_operations:operations,graphs:fixture.graphs.map((g:any)=>[g.event,g.steps.map((s:string)=>[s,workflow.wireFn('once/'+s,{'red/event':g.event})!.slice(1)])]),errors:fixture.cases.map((c:any)=>[c.name,machine.errors({...fixture.base,...c.opts})]),identity_args:fixture.access.map(access.identityArgs),ssh_args:fixture.access.map(access.sshArgs),secret_env:tools.ansibleEnvironment(fixture.secrets)};
function sorted(v:any):any{return Array.isArray(v)?v.map(sorted):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,sorted(v[k])])):v;}
console.log(JSON.stringify(sorted(result)));
