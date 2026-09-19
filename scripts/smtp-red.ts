import { renderFn } from "../red/src/tools.ts";
console.log(renderFn("smtp", JSON.parse(await Bun.file(Bun.argv[2]).text())));
