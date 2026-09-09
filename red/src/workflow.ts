import * as machine from "./machine.ts";
import { parName } from "red/cli";
import * as dryRun from "red/dry-run";
import * as progress from "red/progress";
import { preflight } from "red/lifecycle";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type StepFn } from "red/workflow";
import { readPars } from "red/cli";
import { dirname } from "node:path";
import * as github from "./github.ts";
import * as ssh from "./ssh.ts";
import * as tools from "./tools.ts";
import { secretErrors, stateErrors } from "./validate.ts";

async function stateOutput(opts: Opts, tool: string): Promise<Record<string, unknown> | undefined> {
  try {
    const result = await tofu.outputs(tools.toolDir(opts, tool), tools.backendCredentialEnv(opts));
    return result.params as Record<string, unknown> | undefined;
  } catch {
    return undefined;
  }
}

async function adoptExistingState(opts: Opts): Promise<Opts> {
  const loaded = await machine.load(opts);
  if (loaded['red/exit']) return loaded;
  const smtp = await stateOutput(opts,'tofu-smtp');
  return {...loaded,...(smtp??{}),...(smtp?{'once/smtp-params':smtp}:{})};
}

// Attach the keys ansible-remote installs and the github step publishes.
//
// Generating them is a create-time side effect, so a build or a dry-run takes
// fixed placeholders instead: a fresh key rendered into the artifact would make
// the build nondeterministic and break byte parity between the colours.
async function withDeployKeys(opts: Opts, real: boolean): Promise<Opts> {
  if (real && opts["red/event"] === "create") {
    const [keys, err] = await github.generateKeys(opts);
    if (err) return { ...opts, "red/exit": 1, "red/err": err };
    return {
      ...opts,
      "red/exit": 0,
      "once/deploy-keys": keys,
      ...(keys.length ? { "once/key-dir": dirname(String(keys[0]!.privateFile)) } : {}),
    };
  }
  return { ...opts, "red/exit": 0, "once/deploy-keys": github.placeholderKeys(opts) };
}

export async function startStep(
  original: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  return preflight(original, {
    defaults: { "compute-prevent-destroy": true }, overlay: readPars,
    validators: [
      (opts) => stateErrors(opts),
      (opts, _env, ctx) => ctx.real && ["create", "delete"].includes(String(ctx.event)) ? secretErrors(opts) : [],
      (opts, _env, ctx) => ctx.real && ctx.event === "delete" && opts["compute-prevent-destroy"]
        ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`] : [],
    ],
    afterValidate: async (opts, _env, ctx) => {
      if (ctx.real && ctx.event === 'delete') return adoptExistingState(opts);
      return withDeployKeys(opts, ctx.real);
    },
  }, env);
}

export async function ansibleCleanupStep(opts: Opts): Promise<Opts> {
  return tools.ansibleRemoteStep(await tools.ansibleLocalStep(opts));
}

export const tofuSteps = ["once/tofu-compute", "once/tofu-smtp", "once/tofu-dns", "once/tofu-smtp-post"];
export const sideEffectingSteps = [...tofuSteps, "once/ansible-local", "once/ansible-remote", "once/ansible-cleanup", "once/github", "once/ssh-cleanup"];

export function wireFn(step: string, runOpts: Opts) {
  if (runOpts["red/event"] === "delete") {
    switch (step) {
      // Revoking runs before anything is destroyed: a withdrawn credential
      // against a live host is a loud, recoverable broken deploy, while a live
      // credential against a destroyed host is silent. It needs no key
      // material, so it also works when the box is already gone.
      case "once/start": return [startStep, "once/github"] as const;
      case "once/github": return [github.githubStep, "once/ansible-cleanup"] as const;
      case "once/ansible-cleanup": return [ansibleCleanupStep, "once/tofu-smtp-post"] as const;
      case "once/tofu-smtp-post": return [tools.tofuSmtpPostStep, "once/tofu-dns"] as const;
      case "once/tofu-dns": return [tools.tofuDnsStep, "once/tofu-smtp", "once/tofu-compute"] as const;
      case "once/tofu-smtp": return [tools.tofuSmtpStep] as const;
      // The local keypair goes last, strictly after a successful compute
      // destroy: a failed delete leaves the key, which is still the only
      // credential to whatever survived.
      case "once/tofu-compute": return [tools.tofuComputeStep] as const;
      case "once/ssh-cleanup": return [ssh.cleanupStep] as const;
    }
  } else {
    switch (step) {
      case "once/start": return [startStep, "once/tofu-compute"] as const;
      case "once/tofu-compute": return [tools.tofuComputeStep, "once/tofu-smtp"] as const;
      case "once/tofu-smtp": return [tools.tofuSmtpStep, "once/tofu-dns"] as const;
      case "once/tofu-dns": return [tools.tofuDnsStep, "once/tofu-smtp-post"] as const;
      case "once/tofu-smtp-post": return [tools.tofuSmtpPostStep, "once/ansible-local"] as const;
      case "once/ansible-local": return [tools.ansibleLocalStep, "once/ansible-remote"] as const;
      // Publishing follows the remote stage, not the local one: the credentials
      // describe a configured host, and a workstation-side failure should not
      // gate them.
      case "once/ansible-remote": return [tools.ansibleRemoteStep, "once/github"] as const;
      case "once/github": return [github.githubStep] as const;
    }
  }
}

export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile ?? "default"}/${tool}.tfstate`,
  });
}

function createWorkflow() {
  let result = workflow({ start: "once/start", wireFn });
  for (const tool of tofuSteps.slice(1).map((step) => step.slice("once/".length))) {
    result = adviceAdd(result, `once/${tool}`, "before", "once.workflow/backend", backendAdvice(tool));
  }
  result = progress.advise(result);
  return dryRun.advise(result, sideEffectingSteps);
}

export const onceWorkflow = createWorkflow();
