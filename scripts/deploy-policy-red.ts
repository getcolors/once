import { readFileSync } from "node:fs";
import { stateErrors } from "../red/src/validate.ts";
for (const app of JSON.parse(readFileSync(Bun.argv[2]!, "utf8"))) {
 console.log(JSON.stringify(stateErrors({once:{applications:[{host:"wiki.example.com",image:"example/wiki",...app}]}}).filter(s => s.startsWith("deploy-") || s.startsWith("stop-first"))));
}
