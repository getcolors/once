import {readFileSync} from 'node:fs';
import * as machine from '../red/src/machine.ts';
const cases=JSON.parse(readFileSync(process.argv[2]!, 'utf8'));
console.log(JSON.stringify(cases.map((item:any)=>machine.params(item.opts,item.result))));
