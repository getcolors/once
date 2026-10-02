import {readFileSync} from 'node:fs';
import {failedResult} from '../red/src/machine.ts';
for(const item of JSON.parse(readFileSync(process.argv[2]!, 'utf8'))){
  const opts=failedResult({'red/event':item.event},item.result);
  console.log(JSON.stringify([item.name,opts['red/exit'],opts['red/err']]));
}
