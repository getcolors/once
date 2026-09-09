/** ONCE's single host requirement and application parameter adapter. */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { stageDir } from 'red/cli';
import type { Opts } from 'red/workflow';
import { validate, backend_plan, source_cidrs, read_deployment, orchestrate, plan_deployment, keyMode } from 'colors-compute-red';
const topology = [{role: null, count: 1}];
export function requirements(opts: Opts) {
  const ingress = [['ssh',22],['http',80],['https',443]].map(([name,port]) => {
    const suffix = name === 'ssh' ? 'ssh-sources' : 'http-sources';
    const sources = source_cidrs(opts, suffix, 'compute-' + suffix);
    if (!sources.length) throw new Error('compute-' + suffix + ' is required');
    return {id:name,protocol:'tcp',from_port:port,to_port:port,sources};
  });
  return {single_host:true,private:false,security:{ingress,egress:'all',private_filter:false},legacy_state_keys:[String(opts.profile)+'/tofu-compute.tfstate']};
}
export function errors(opts: Opts): string[] {
  const result = validate(opts);
  if (!result.length) { try { plan_deployment(opts,topology,requirements(opts)); } catch (error) { result.push((error as Error).message); } }
  return result;
}
export function params(opts: Opts, result: any): Opts {
  const node = result.cluster.nodes[0];
  let path = result.key?.private_key_path ?? node.ssh_identity_file;
  if (path && result.status === 'planned') path=path.replace('$HOME/.ssh','/home/build-placeholder/.ssh');
  return {...node,'ssh-keygen':keyMode(opts).mode==='managed',...(path?{'ssh-private-key-path':path}:{})};
}
export function fallbackParams(opts: Opts): Opts {
  if(opts['red/event']!=='build'&&!opts['red/dry-run'])throw new Error('compute inventory unavailable');
  return params(opts,plan_deployment(opts,topology,requirements(opts)));
}
function sorted(value: any): any {return Array.isArray(value)?value.map(sorted):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,sorted(value[key])])):value;}
export async function step(opts: Opts): Promise<Opts> {
  const planning=opts['red/event']==='build'||opts['red/dry-run'];
  const result:any=planning?plan_deployment(opts,topology,requirements(opts)):await orchestrate(opts,topology,requirements(opts));
  if(!['planned','ready','destroyed'].includes(result.status))return {...opts,'red/exit':1,'red/err':result.errors?.join('\n')||'compute lifecycle refused'};
  if(planning)for(const [stage,documents] of [['shared',result.documents.shared],...Object.entries(result.documents.nodes).map(([id,docs])=>['nodes/'+id,docs])] as [string,Record<string,unknown>][]){
    const key=stage==='shared'?result.state_keys.shared:result.state_keys.nodes[stage.split('/')[1]!];
    for(const [filename,document]of Object.entries({...documents,'backend.tf.json':backend_plan(opts,key).config})){
    const target=join(stageDir(opts,'tofu-compute'),stage,filename);mkdirSync(dirname(target),{recursive:true});writeFileSync(target,JSON.stringify(sorted(document),null,2)+'\n');
  }}
  if(!result.cluster)return {...opts,'red/exit':0};
  const adopted=params(opts,result);return {...opts,...adopted,'once/compute-params':adopted,'colors-compute/cluster':result.cluster,'red/exit':0};
}
export async function load(opts:Opts, env:Record<string,string|undefined>=process.env):Promise<Opts>{
  const result:any=await read_deployment(opts,env);
  if(result.status==='destroyed'&&opts['red/event']==='delete')return {...opts,'red/exit':0,'colors-compute/already-destroyed':true};
  if(result.status!=='present')return {...opts,'red/exit':1,'red/err':'compute inventory unavailable; legacy state requires explicit migration'};
  const adopted=params(opts,result);return {...opts,...adopted,'once/compute-params':adopted,'colors-compute/cluster':result.cluster,'red/exit':0};
}
