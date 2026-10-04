import { scoped } from "./access.ts";
import { findUp, runCli } from "red/cli";
import type { Opts } from "red/workflow";
import { describeFile } from "./describe.ts";
import { onceWorkflow } from "./workflow.ts";

export const usage = "Usage: red <build|create|delete|describe|ssh|ssh-install|ssh-uninstall> [-f|--file colors.yml] [--dry-run]";

// Walking up means a colour can be run from any subdirectory of a project and
// still find the one desired state every colour shares.

function hasFileArg(args: string[]): boolean {
  return args.some((arg) => arg === "-f" || arg === "--file" || arg.startsWith("--file="));
}

export function defaultArgs(args: string[]): string[] {
  return hasFileArg(args) ? args : [...args, "-f", findUp("colors.yml") ?? "colors.yml"];
}

function fileFromArgs(args: string[]): string {
  const equals = args.find((arg) => arg.startsWith("--file="));
  if (equals) return equals.slice("--file=".length);
  const index = args.findIndex((arg) => arg === "-f" || arg === "--file");
  return index >= 0 ? args[index + 1] ?? findUp("colors.yml") ?? "colors.yml" : findUp("colors.yml") ?? "colors.yml";
}

export async function run(...input: string[]): Promise<Opts> {
  const args = defaultArgs(input);
  const command = args[0];
  if (["help", "--help", "-h"].includes(command ?? "")) return { "red/exit": 0, "red/err": usage };
  if (command === "describe") return describeFile(fileFromArgs(args));
  if (["build", "create", "delete", "ssh", "ssh-install", "ssh-uninstall"].includes(command ?? "")) return scoped(() => runCli(onceWorkflow, args));
  return { "red/exit": 2, "red/err": usage };
}

export async function exec(args: string[] = Bun.argv.slice(2)): Promise<never> {
  const command = args[0];
  if (["build", "create", "delete", "ssh", "ssh-install", "ssh-uninstall"].includes(command ?? "")) {
    const result = await run(...args);
    if (result["red/err"]) console.error(result["red/err"]);
    return process.exit(result["red/exit"] ?? 0);
  }
  const result = await run(...args);
  if (result["red/err"]) {
    const output = (result["red/exit"] ?? 0) === 0 ? console.log : console.error;
    output(result["red/err"]);
    if (result["red/trace"]) console.error(result["red/trace"]);
  }
  return process.exit(result["red/exit"] ?? 0);
}
