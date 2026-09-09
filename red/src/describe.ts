import * as machine from "./machine.ts";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { runtime, type ExecResult } from "red/runtime";
import type { Opts } from "red/workflow";
import { readPars } from "red/cli";
import { identityArgs, withMachineKey } from "./ssh.ts";
import { backendCredentialEnv, toolDir } from "./tools.ts";

const runTimeoutMs = 30_000;
const sshProbeTimeoutMs = 10_000;
const registryTimeoutMs = 30_000;
const placeholderIp = "192.168.0.1";

type Runner = (args: string[], opts?: { cwd?: string; env?: Record<string, string | undefined>; timeoutMs?: number }) => Promise<ExecResult>;
const run: Runner = (args, opts = {}) => runtime.exec(args, { ...opts, timeoutMs: opts.timeoutMs ?? runTimeoutMs });
const ok = (result: ExecResult) => result.exit === 0;

function stripAnsi(value: string): string {
  return String(value ?? "").replace(/\x1b\]8;[^\x07]*\x07/g, "").replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, "");
}

function snippet(value: string): string | undefined {
  const text = value?.trim();
  return text ? (text.length > 200 ? `${text.slice(0, 200)}…` : text) : undefined;
}

function resultDetail(label: string, result: ExecResult): string {
  const detail = snippet(result.err) ?? snippet(result.out);
  return `${label} failed (exit ${result.exit ?? -1})${detail ? ` — ${detail}` : ""}`;
}

// The keygen identity travels with the target so every probe names the
// machine key explicitly; in opt-out mode the target is unchanged.
function sshTarget(params: Opts): { ip?: string; user: string } & Opts {
  let ip = params.ip as string | undefined;
  if (params["provider-compute"] === "no-infra" && (!ip || ip === placeholderIp)) ip = params["no-infra-compute-ip"] as string;
  return {
    ip,
    user: String(params.user || params.sudoer || params["no-infra-compute-user"] || params["no-infra-compute-sudoer"] || "root"),
    ...(params["ssh-keygen"]
      ? { "ssh-keygen": params["ssh-keygen"], "ssh-private-key-path": params["ssh-private-key-path"] }
      : {}),
  };
}

function sshArgs(compute: { ip?: string; user: string } & Opts, remote: string[]): string[] {
  return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-o", "StrictHostKeyChecking=accept-new", ...identityArgs(compute), `${compute.user}@${compute.ip}`, ...remote];
}

async function computeStatus(runner: Runner, params: Opts, computeDetail?: string) {
  const external = params["provider-compute"] === "no-infra";
  const target = sshTarget(params);
  if (!target.ip || target.ip === placeholderIp) {
    return {
      ...target,
      status: external ? "unreachable" : "absent",
      detail: external ? "no host configured" : computeDetail ?? `no OpenTofu state in ${toolDir(params, "tofu-compute")}`,
    };
  }
  const result = await runner(sshArgs(target, ["true"]), { timeoutMs: sshProbeTimeoutMs });
  return { ...target, status: ok(result) ? "running" : "unreachable", detail: ok(result) ? "ssh ok" : resultDetail("ssh", result) };
}

export function providerSummary(params: Opts) {
  return { compute: params["provider-compute"], backend: params["provider-backend"], smtp: params["provider-smtp"], dns: params["provider-dns"] };
}

const hostStatusRe = /([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?/;
export function parseOnceList(output: string): Array<{ host: string; status?: string }> {
  return stripAnsi(output).split(/\r?\n/).flatMap((line) => {
    const match = hostStatusRe.exec(line);
    return match ? [{ host: match[1]!, ...(match[2]?.trim() ? { status: match[2] } : {}) }] : [];
  });
}

export function imageRepositoryTag(image: unknown): { repository: string; tag: string; image: string } | undefined {
  const value = String(image ?? "").trim();
  if (!value || /^sha256:[A-Fa-f0-9]+$/.test(value)) return undefined;
  const withoutDigest = value.split("@", 1)[0]!;
  const slash = withoutDigest.lastIndexOf("/");
  const colon = withoutDigest.lastIndexOf(":");
  const tagged = colon > slash;
  const repository = tagged ? withoutDigest.slice(0, colon) : withoutDigest;
  const tag = tagged ? withoutDigest.slice(colon + 1) : "latest";
  return { repository, tag, image: `${repository}:${tag}` };
}

export function matchingRepoDigest(repository: string, digests: unknown[] = []): string | undefined {
  for (const entry of digests) {
    const [repo, digest] = String(entry).split("@", 2);
    if (repo === repository) return digest;
  }
}

function containerText(container: any): string {
  return JSON.stringify({ Name: container.Name, Config: container.Config, NetworkSettings: container.NetworkSettings }).toLowerCase();
}
// The host ONCE recorded on the container, from its `once` label. ONCE writes the
// application's desired state there as JSON, making this the authoritative
// container-to-host mapping; everything else is inference from names and labels.
function onceLabelHost(container: any): string | null {
  const raw = String(container?.Config?.Labels?.once ?? "").trim();
  if (!raw) return null;
  try {
    const host = JSON.parse(raw)?.host;
    return host ? String(host).trim().toLowerCase() || null : null;
  } catch {
    return null;
  }
}
// `example.com` sits inside `www.example.com`, so a plain substring lets the shorter host
// claim the longer one's container. Dots and alphanumerics block a match; `-` and `_` do
// not, because container names embed a dot-substituted host inside a longer name
// (`/once-www-example-com`).
function hostVariantRegExp(variant: string): RegExp {
  return new RegExp(`(?<![a-z0-9.])${variant.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![a-z0-9.])`);
}
// The `once` label wins outright: a labelled container belongs to exactly the host it
// names and can never be claimed by another, so only unlabelled containers fall through
// to matching on names and third-party labels.
export function containerForHost(containers: any[], host: string): any {
  const lower = host.toLowerCase();
  const labelled = containers.find((container) => onceLabelHost(container) === lower);
  if (labelled) return labelled;
  const variants = [...new Set([lower, lower.replaceAll(".", "-"), lower.replaceAll(".", "_")])].map(hostVariantRegExp);
  return containers.find((container) => onceLabelHost(container) === null && variants.some((rx) => rx.test(containerText(container))));
}

async function registryDigest(runner: Runner, image: string, os: string, arch: string) {
  const args = ["skopeo", "inspect", "--no-tags", ...(os ? ["--override-os", os] : []), ...(arch ? ["--override-arch", arch] : []), `docker://${image}`];
  const result = await runner(args, { timeoutMs: registryTimeoutMs });
  if (!ok(result)) return { detail: resultDetail("skopeo inspect", result) };
  try { return { digest: JSON.parse(result.out).Digest as string }; }
  catch (error) { return { detail: `registry response was not valid JSON: ${error}` }; }
}

async function applicationReport(runner: Runner, containers: any[], images: any[], app: any) {
  const container = containerForHost(containers, app.host);
  const imageRef = container?.Config?.Image;
  const parsed = imageRepositoryTag(imageRef);
  const info = images.find((image) => image.Id === container?.Image || image.RepoTags?.includes(imageRef) || image.RepoTags?.includes(parsed?.image));
  const running = parsed ? matchingRepoDigest(parsed.repository, info?.RepoDigests) : undefined;
  const fallback = info?.Id ?? container?.Image;
  const registry = parsed ? await registryDigest(runner, parsed.image, info?.Os ?? "linux", info?.Architecture ?? "amd64") : {};
  return {
    host: app.host, status: container?.State?.Status ?? app.status ?? "unknown", image: parsed?.image ?? imageRef,
    version: parsed?.tag, digest: running ?? fallback, "digest-source": running ? "repo-digest" : fallback ? "image-id" : undefined,
    "registry-digest": (registry as any).digest,
    "new-version?": running && (registry as any).digest ? running !== (registry as any).digest : undefined,
    ...((registry as any).detail ? { "registry-detail": (registry as any).detail } : {}),
  };
}

async function remoteApplications(runner: Runner, compute: { ip?: string; user: string }) {
  const check = await runner(sshArgs(compute, ["command", "-v", "once", ">/dev/null", "2>&1", "||", "test", "-x", "/usr/local/bin/once", "||", "{", "echo", "once:", "command", "not", "found", ">&2", ";", "exit", "127", ";", "}"]));
  if (!ok(check)) return { ok: false, fatal: true, detail: resultDetail("once command check", check), applications: [] };
  const listed = await runner(sshArgs(compute, ["sudo", "-n", "once", "list"]));
  if (!ok(listed)) return { ok: false, fatal: listed.exit === 127 || /once: (command )?not found/i.test(`${listed.out}\n${listed.err}`), detail: resultDetail("once list", listed), applications: [] };
  const apps = parseOnceList(listed.out);
  if (!apps.length) return { ok: true, applications: [] };
  const ps = await runner(sshArgs(compute, ["sudo", "-n", "docker", "ps", "-q"]));
  if (!ok(ps)) return { ok: false, detail: resultDetail("docker ps", ps), applications: [] };
  const ids = ps.out.split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
  if (!ids.length) return { ok: true, applications: apps.map((app) => ({ ...app, status: app.status ?? "unknown" })) };
  const inspected = await runner(sshArgs(compute, ["sudo", "-n", "docker", "inspect", "--type", "container", ...ids]));
  if (!ok(inspected)) return { ok: false, detail: resultDetail("docker inspect", inspected), applications: [] };
  const containers = JSON.parse(inspected.out || "[]");
  const imageIds = [...new Set(containers.flatMap((container: any) => [container.Image, container.Config?.Image]).filter(Boolean))] as string[];
  let images: any[] = [];
  if (imageIds.length) {
    const imageResult = await runner(sshArgs(compute, ["sudo", "-n", "docker", "image", "inspect", ...imageIds]));
    if (!ok(imageResult)) return { ok: false, detail: resultDetail("docker image inspect", imageResult), applications: [] };
    images = JSON.parse(imageResult.out || "[]");
  }
  return { ok: true, applications: await Promise.all(apps.map((app) => applicationReport(runner, containers, images, app))) };
}

async function tofuOutputParams(runner: Runner, opts: Opts, tool: string) {
  const result = await runner(["tofu", "output", "-json"], { cwd: toolDir(opts, tool), env: backendCredentialEnv(opts) });
  if (!ok(result)) return { params: {}, detail: resultDetail(`tofu output in ${toolDir(opts, tool)}`, result) };
  try { return { params: JSON.parse(result.out)?.params?.value ?? {} }; }
  catch (error) { return { params: {}, detail: `${tool} output was not valid JSON: ${error}` }; }
}

export async function describeReport(input: Opts, runner: Runner = run, resolve = true): Promise<any> {
  let opts = input;
  let detail: string | undefined;
  let computeDetail: string | undefined;
  if (resolve) {
    const loaded = await machine.load(opts);
    const compute = {params: loaded["once/compute-params"] as Record<string,unknown> ?? {}, detail: loaded["red/err"]};
    const smtp = await tofuOutputParams(runner, opts, "tofu-smtp");
    opts = { ...opts, ip: undefined, ...compute.params, ...smtp.params };
    computeDetail = compute.detail;
    detail = [compute.detail, smtp.detail].filter(Boolean).join("; ") || undefined;
  }
  const compute = await computeStatus(runner, opts, computeDetail);
  if (detail && compute.status !== "absent") compute.detail += `; ${detail}`;
  const appResult = compute.status === "running"
    ? await remoteApplications(runner, compute)
    : { ok: false, applications: [], detail: compute.status === "absent" ? "not checked because compute has not been created" : "not checked because compute is not reachable" };
  return {
    profile: opts.profile, providers: providerSummary(opts), compute,
    applications: appResult.applications ?? [],
    "applications-error": appResult.ok ? undefined : appResult.detail,
    "fatal-error?": Boolean((appResult as any).fatal),
  };
}

function present(value: unknown): string { return value === undefined || value === null || String(value).trim() === "" ? "unknown" : String(value); }
function updateLabel(value: unknown): string { return value === true ? "yes" : value === false ? "no" : "unknown"; }

export function printReport(report: any): void {
  console.log(`Profile: ${present(report.profile)}\n`);
  console.log(`Providers:\n  Compute: ${present(report.providers.compute)}\n  Backend: ${present(report.providers.backend)}\n  SMTP: ${present(report.providers.smtp)}\n  DNS: ${present(report.providers.dns)}\n`);
  console.log(`Compute:\n  IP: ${present(report.compute.ip)}\n  SSH user: ${present(report.compute.user)}\n  Status: ${present(report.compute.status)}${report.compute.detail ? ` (${report.compute.detail})` : ""}\n`);
  if (report["applications-error"]) console.log(`Applications: ${report["applications-error"]}.`);
  else if (!report.applications.length) console.log("Applications: none found.");
  else {
    console.log("Applications:");
    for (const app of report.applications) {
      console.log(`  - ${present(app.host)}\n    status: ${present(app.status)}\n    image: ${present(app.image)}\n    version: ${present(app.version)}\n    digest: ${present(app.digest)}${app["digest-source"] === "image-id" ? " (image id; digest comparison unknown)" : ""}\n    registry digest: ${present(app["registry-digest"])}\n    update available: ${updateLabel(app["new-version?"])}`);
      if (app["registry-detail"]) console.log(`    registry check: ${app["registry-detail"]}`);
    }
  }
}

export async function describe(opts: Opts): Promise<Opts> {
  const result = await describeReport(opts);
  printReport(result);
  const computeError = result.compute.status === "running" ? undefined : `compute is ${result.compute.status}${result.compute.detail ? ` — ${result.compute.detail}` : ""}`;
  if (result["fatal-error?"]) return { ...opts, "once.describe/result": result, "red/exit": 1, "red/err": result["applications-error"] ?? "describe failed" };
  return { ...opts, "once.describe/result": result, ...(computeError ? { "red/exit": 1, "red/err": computeError } : { "red/exit": 0 }) };
}

export async function describeFile(path: string): Promise<Opts> {
  try {
    if (!existsSync(path)) return { "red/exit": 2, "red/err": `desired state file not found: ${path}` };
    // Describe reads the live host, so it needs the keygen identity the way
    // create does — real event semantics, opt-out untouched.
    return describe(withMachineKey(readPars({
      ...((Bun.YAML.parse(readFileSync(path, "utf8")) ?? {}) as Opts),
      "red/state-file": resolve(path),
    }), true));
  } catch (error) {
    return { "red/exit": 2, "red/err": error instanceof Error ? error.message : String(error) };
  }
}
