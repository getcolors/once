import {wireFn} from '../red/src/workflow.ts';
console.log(JSON.stringify(['create','build'].map(event=>[event,...['once/tofu-smtp-post','once/ansible-local','once/ansible-remote'].map(step=>wireFn(step,{'red/event':event})!.slice(1))])));
