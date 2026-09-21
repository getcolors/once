// Compare only DMARC errors; unrelated provider diagnostics have older formatting differences.
import { stateErrors } from "../red/src/validate.ts";
const { base, cases } = JSON.parse(await Bun.file(Bun.argv[2]).text());
for (const { name, opts } of cases) {
  console.log(JSON.stringify([name, stateErrors({ ...base, ...opts }).filter(error => error.startsWith("smtp-dmarc-"))]));
}
