import {test,expect} from "bun:test";
import {wireFn} from "../src/workflow.ts";
for(const event of ["create","build"])test(`SSH alias precedes remote convergence on ${event}`,()=>{
 const opts={"red/event":event};
 expect(wireFn("once/tofu-smtp-post",opts)?.slice(1)).toEqual(["once/ansible-local"]);
 expect(wireFn("once/ansible-local",opts)?.slice(1)).toEqual(["once/ansible-remote"]);
});
