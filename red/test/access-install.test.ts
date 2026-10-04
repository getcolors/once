import {describe,test,expect} from 'bun:test';
import {installStep,uninstallStep,installedIdentity,configPayload} from '../src/access.ts';
const opts={profile:'access-test','red/event':'ssh-install',ip:'192.0.2.1',user:'root'};
const noLock=async()=>{};
describe('durable SSH installation',()=>{
  test('checks ownership before export and then installs aliases',async()=>{
    const calls:string[]=[];
    const result=await installStep(opts,async(_opts,operation)=>{calls.push(operation);return {status:'installed',private_key_file:'/encrypted/identity'};},async(payload)=>{calls.push(payload.check_only?'check':'config');return {exit:0,out:'',err:''};},noLock);
    expect(calls).toEqual(['check','install','config']);expect(result['red/exit']).toBe(0);
  });
  test('collision refuses export',async()=>{
    let exported=false;
    const result=await installStep(opts,async()=>{exported=true;return {};},async()=>({exit:1,out:'',err:'collision'}),noLock);
    expect(exported).toBe(false);expect(result['red/err']).toBe('SSH config update failed: collision');
  });
  test('uninstall removes aliases before key and stops on config failure',async()=>{
    const calls:string[]=[];
    const exp=async(_opts:any,operation:string)=>{calls.push(operation);return {status:operation==='inspect'?'installed':'removed'};};
    await uninstallStep(opts,exp,async()=>{calls.push('config');return {exit:0,out:'',err:''};},noLock);
    expect(calls).toEqual(['inspect','config','remove']);calls.length=0;
    await uninstallStep(opts,exp,async()=>({exit:1,out:'',err:'collision'}),noLock);
    expect(calls).toEqual(['inspect']);
  });
  test('dry-run has no lock/export/config effects',async()=>{
    const fail=async()=>{throw Error('unexpected side effect');};
    for(const step of [installStep,uninstallStep])expect(await step({...opts,'red/dry-run':true},fail,fail,fail)).toEqual({...opts,'red/dry-run':true});
  });
  test('installed identity is preserved and absent leaves scoped identity alone',async()=>{
    expect(await installedIdentity(opts,async()=>({status:'installed',private_key_file:'/encrypted/identity'}),noLock)).toBe('/encrypted/identity');
    expect(await installedIdentity(opts,async()=>({status:'absent'}),noLock)).toBeUndefined();
    expect(configPayload(opts,'absent').ssh_hosts).toEqual([]);
  });
});
