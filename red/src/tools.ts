import * as machine from "./machine.ts";
import { ansibleStep, ansibleWithSpec } from "red/ansible";
import { contentSpec, PRESERVE_JINJA_DELIMITERS, scaffold, type RenderOpts, type Spec, type Template } from "red/scaffold";
import { stageDir } from "red/cli";
import * as tofu from "red/tofu";
import type { Opts } from "red/workflow";
import { toolEnv } from "red/providers";
import { publicKeys } from "./github.ts";
import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import authorizedKeys from "../resources/tools/ansible/files/authorized-keys" with { type: "text" };
import deploy from "../resources/tools/ansible/files/deploy" with { type: "text" };
import onceModule from "../resources/tools/ansible/library/once" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import dnsCloudflare from "../resources/tools/tofu-dns/cloudflare/main.tf" with { type: "text" };
import dnsNoInfra from "../resources/tools/tofu-dns/no-infra/main.tf" with { type: "text" };
import dnsYandex from "../resources/tools/tofu-dns/yandex/main.tf" with { type: "text" };
import smtpPostNoInfra from "../resources/tools/tofu-smtp-post/no-infra/main.tf" with { type: "text" };
import smtpPostResend from "../resources/tools/tofu-smtp-post/resend/main.tf" with { type: "text" };
import smtpNoInfra from "../resources/tools/tofu-smtp/no-infra/main.tf" with { type: "text" };
import smtpResend from "../resources/tools/tofu-smtp/resend/main.tf" with { type: "text" };
import { appsDomains, registrableDomain } from "./utils.ts";
import { providers } from "./validate.ts";

const templateOpts: RenderOpts = PRESERVE_JINJA_DELIMITERS;

const smtpTemplates: Record<string, Template> = {
  resend: { name: "tools/tofu-smtp/resend/main.tf", content: smtpResend },
  "no-infra": { name: "tools/tofu-smtp/no-infra/main.tf", content: smtpNoInfra },
};
const dnsTemplates: Record<string, Template> = {
  cloudflare: { name: "tools/tofu-dns/cloudflare/main.tf", content: dnsCloudflare },
  yandex: { name: "tools/tofu-dns/yandex/main.tf", content: dnsYandex },
  "no-infra": { name: "tools/tofu-dns/no-infra/main.tf", content: dnsNoInfra },
};
const smtpPostTemplates: Record<string, Template> = {
  resend: { name: "tools/tofu-smtp-post/resend/main.tf", content: smtpPostResend },
  "no-infra": { name: "tools/tofu-smtp-post/no-infra/main.tf", content: smtpPostNoInfra },
};

// A relative workdir is resolved against the directory holding colors.yml, not
// the current one, so every colour shares one work directory however deep in
// the project it was invoked from.
export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "default" });
}

function templateSpec(template: Template, target: string, data: Record<string, unknown>): Spec {
  return { template, target, data, opts: templateOpts };
}

function rawSpec(target: string, content: string): Spec {
  return contentSpec(target, content);
}

function outputParams(opts: Opts): Record<string, unknown> | undefined {
  return (opts["tofu/outputs"] as any)?.params;
}

function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  return toolEnv(providers, opts, [...slots, "provider-backend"]);
}

export function backendCredentialEnv(opts: Opts): Record<string, string> | undefined {
  return credentialEnv(opts);
}

export function fallbackComputeParams(opts: Opts): Record<string, unknown> { return machine.fallbackParams(opts); }

export function fallbackSmtpParams(opts: Opts): Record<string, unknown> {
  if (opts["provider-smtp"] === "no-infra") {
    return {
      domains: [],
      smtp_username: opts["no-infra-smtp-username"],
      smtp_password: opts["no-infra-smtp-password"],
      smtp_server: opts["no-infra-smtp-server"],
      smtp_port: opts["no-infra-smtp-port"],
    };
  }
  if (opts["provider-smtp"] === "resend") {
    return {
      domains: appsDomains(opts).map((zone) => ({ zone, id: `domain-id-not-defined-${zone}`, records: [] })),
      smtp_server: "smtp.resend.com", smtp_port: 587, smtp_username: "resend",
      smtp_password: opts["resend-password"],
    };
  }
  return { domains: [] };
}

async function tofuWithSpecs(
  opts: Opts,
  dir: string,
  specs: Spec[],
  fallback: Record<string, unknown>,
  resultKey: string | undefined,
  env: Record<string, string> | undefined,
): Promise<Opts> {
  const result = await tofu.tofuWithSpec(opts, specs, { dir, env });
  if (!resultKey || (result["red/exit"] ?? 0) > 0 || opts["red/event"] === "delete") return result;
  if (opts["red/event"] === "build") return { ...result, [resultKey]: fallback };
  return { ...result, [resultKey]: { ...fallback, ...(outputParams(result) ?? {}) } };
}

function withZones(opts: Opts): Opts {
  const zones = appsDomains(opts);
  return { ...opts, zones, "zones-hcl": tofu.hclList(zones) };
}

export function tofuComputeStep(opts: Opts): Promise<Opts> { return machine.step(opts); }

export function tofuSmtpStep(original: Opts): Promise<Opts> {
  const opts = withZones(original);
  const provider = String(opts["provider-smtp"] ?? "resend");
  const dir = toolDir(opts, "tofu-smtp");
  return tofuWithSpecs(opts, dir, [templateSpec(smtpTemplates[provider]!, `${dir}/main.tf`, opts)], fallbackSmtpParams(opts), "once/smtp-params", credentialEnv(opts, "provider-smtp"));
}

function addFqnSuffix(name: string, suffix: string): string {
  const slash = name.indexOf("/");
  return slash < 0 ? `${name}${suffix}` : `${name.slice(0, slash)}/${name.slice(slash + 1)}${suffix}`;
}

function cloudflareZoneId(zone: string): string {
  return `\${data.cloudflare_zone.domains[${JSON.stringify(zone)}].id}`;
}

function yandexZoneId(zone: string): string {
  return `\${yandex_dns_zone.domains[${JSON.stringify(zone)}].id}`;
}

// Yandex record names and targets are absolute; without the trailing dot the
// API would read them as relative to the zone.
function yandexFqdn(s: unknown): string {
  const value = String(s);
  return value.endsWith(".") ? value : `${value}.`;
}

// DNS provider -> the record resource its generated .tf.json files declare.
// A provider absent here (no-infra) gets no generated records at all.
const dnsRecordResources: Record<string, string> = {
  cloudflare: "cloudflare_dns_record",
  yandex: "yandex_dns_recordset",
};

function appRecord(provider: string, ip: unknown, host: string): Record<string, unknown> {
  const zone = registrableDomain(host)!;
  if (provider === "cloudflare") {
    return { zone_id: cloudflareZoneId(zone), name: host, content: ip, type: "A", proxied: true, ttl: 1 };
  }
  // Yandex has no proxy: the record resolves straight to the server.
  return { zone_id: yandexZoneId(zone), name: yandexFqdn(host), type: "A", ttl: 300, data: [ip] };
}

function smtpRecord(provider: string, zone: string, record: any): Record<string, unknown> {
  if (provider === "cloudflare") {
    return {
      zone_id: cloudflareZoneId(zone), name: record.name, ttl: "1", type: record.type,
      proxied: false,
      ...(record.type === "TXT" ? { content: `\"${record.value}\"` } : {}),
      ...(record.type === "MX" ? { priority: record.priority, content: record.value } : {}),
    };
  }
  // Yandex recordsets carry everything in data — the MX priority is part of
  // the value, and TXT values are quoted like a zone file.
  const data = record.type === "TXT" ? `"${record.value}"`
    : record.type === "MX" ? `${record.priority} ${yandexFqdn(record.value)}`
    : record.value;
  return { zone_id: yandexZoneId(zone), name: yandexFqdn(record.name), ttl: 300, type: record.type, data: [data] };
}

export function renderFn(source: "apps" | "smtp", data: any): string {
  const provider = String(data.provider ?? "cloudflare");
  const resource = dnsRecordResources[provider]!;
  if (source === "apps") {
    // One A record per application host — proxied on Cloudflare, plain on
    // Yandex. There is no implicit apex or wildcard record: only the hosts
    // desired state names resolve to the server.
    return tofu.constructsJson((data.applications ?? []).map((app: any) =>
      tofu.construct("resource", resource, addFqnSuffix("io.github.getcolors.once.tools/app-dns", `-${app.host}`),
        appRecord(provider, data.ip, app.host))));
  }
  return tofu.constructsJson((data.domains ?? []).flatMap((domain: any) =>
    (domain.records ?? []).map((record: any) => tofu.construct(
      "resource", resource,
      addFqnSuffix("io.github.getcolors.once.tools/smtp-dns", `-${domain.zone}-${record.record}-${record.type}`),
      smtpRecord(provider, domain.zone, record),
    )),
  ));
}

function joinedParams(opts: Opts): Opts {
  const branches = (opts["red/branches"] as Opts[] | undefined) ?? [];
  const compute = branches.find((branch) => branch["once/compute-params"])?.["once/compute-params"] ?? opts["once/compute-params"] ?? fallbackComputeParams(opts);
  const smtp = branches.find((branch) => branch["once/smtp-params"])?.["once/smtp-params"] ?? opts["once/smtp-params"] ?? fallbackSmtpParams(opts);
  return { ...opts, ...(compute as object), ...(smtp as object), "once/compute-params": compute, "once/smtp-params": smtp };
}

export function tofuDnsStep(original: Opts): Promise<Opts> {
  const opts = withZones(original["red/event"] === "delete" ? original : joinedParams(original));
  const provider = String(opts["provider-dns"] ?? "cloudflare");
  const dir = toolDir(opts, "tofu-dns");
  const specs: Spec[] = [templateSpec(dnsTemplates[provider]!, `${dir}/main.tf`, opts)];
  if (provider in dnsRecordResources) {
    const apps = (opts.once as any)?.applications;
    specs.push(
      rawSpec(`${dir}/apps.tf.json`, renderFn("apps", { provider, applications: apps, ip: opts.ip })),
      rawSpec(`${dir}/smtp.tf.json`, renderFn("smtp", { provider, domains: opts.domains })),
    );
  }
  return tofuWithSpecs(opts, dir, specs, {}, undefined, credentialEnv(opts, "provider-dns"));
}

export function tofuSmtpPostStep(original: Opts): Promise<Opts> {
  const ids = Object.fromEntries([...(original.domains as any[] ?? [])]
    .sort((a, b) => String(a.zone).localeCompare(String(b.zone))).map((domain) => [domain.zone, domain.id]));
  const opts: Opts = { ...original, "domain-ids-hcl": tofu.hclMap(ids) };
  const provider = String(opts["provider-smtp"] ?? "resend");
  const dir = toolDir(opts, "tofu-smtp-post");
  return tofuWithSpecs(opts, dir, [templateSpec(smtpPostTemplates[provider]!, `${dir}/main.tf`, opts)], {}, undefined, credentialEnv(opts, "provider-smtp"));
}

function dataFn(data: Opts): Opts {
  return { ...data, sudoer: data.sudoer ?? "root", hosts: [data.ip ?? "64.227.72.100"], users: [] };
}

function prettyJson(value: any, indent = 0): string {
  if (Array.isArray(value)) return value.length ? `[ ${value.map((item) => prettyJson(item, indent)).join(", ")} ]` : "[ ]";
  if (value && typeof value === "object") {
    const entries = Object.entries(value);
    if (!entries.length) return "{ }";
    const pad = " ".repeat(indent + 2), close = " ".repeat(indent);
    return `{\n${entries.map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${prettyJson(nested, indent + 2)}`).join(",\n")}\n${close}}`;
  }
  return JSON.stringify(value ?? null);
}

export function inventory(data: any): string {
  const users = (data.users ?? []).filter((user: any) => !user.remove)
    .flatMap((user: any) => (data.hosts ?? []).map((host: string) => ({ ...user, host })));
  const usersHosts = Object.fromEntries(users.map((user: any) => [`${user.name}@${user.host}`, { ansible_host: user.host, ansible_user: user.name, uid: user.uid }]));
  // In keygen mode nothing guarantees an agent holds the machine key, so the
  // inventory names it — a path, never key material.
  const adminsHosts = Object.fromEntries((data.hosts ?? []).map((host: string) => [`root@${host}`, {
    ansible_host: host,
    ansible_user: data.sudoer ?? "root",
    ...(data["ssh-private-key-path"] ? { ansible_ssh_private_key_file: data["ssh-private-key-path"] } : {}),
  }]));
  return prettyJson({ all: { children: { admin: { hosts: adminsHosts }, users: { hosts: usersHosts } } } });
}

function yamlScalar(value: any): string | undefined {
  if (value === undefined || value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "boolean" || typeof value === "number") return String(value);
  if (Array.isArray(value) && value.length === 0) return "[]";
  if (typeof value === "object" && Object.keys(value).length === 0) return "{}";
  return undefined;
}

function yamlLines(value: any, indent = 0): string[] {
  const scalar = yamlScalar(value);
  if (scalar !== undefined) return [`${" ".repeat(indent)}${scalar}`];
  if (Array.isArray(value)) return value.flatMap((item) => {
    const child = yamlLines(item, indent + 2);
    const prefix = " ".repeat(indent + 2);
    return [`${" ".repeat(indent)}- ${child[0]!.slice(prefix.length)}`, ...child.slice(1)];
  });
  return Object.entries(value).flatMap(([key, nested]) => {
    const nestedScalar = yamlScalar(nested);
    return nestedScalar !== undefined
      ? [`${" ".repeat(indent)}${key}: ${nestedScalar}`]
      : [`${" ".repeat(indent)}${key}:`, ...yamlLines(nested, indent + 2)];
  });
}

function yaml(value: any): string { return `${yamlLines(value).join("\n")}\n`; }
function parLookup(key: string): string {
  const suffix = key.toUpperCase().replaceAll("-", "_");
  return `{{ lookup('env','COLORS_PAR_${suffix}') }}`;
}

function resolveEnv(env: any): any {
  return env && !Array.isArray(env) && typeof env === "object"
    ? Object.entries(env).map(([name, key]) => `${name}=${parLookup(String(key))}`)
    : env;
}

function applicationData(smtp: any, app: any): any {
  const zone = registrableDomain(app.host);
  // github never reaches the host. It says where the deploy credentials are
  // published, which is no business of the module reconciling containers.
  const { github: _github, ...rest } = app;
  return { ...rest, ...smtp, smtp_from: `Info <info@notifications.${zone}>`, ...(app.env && !Array.isArray(app.env) && typeof app.env === "object" ? { env: resolveEnv(app.env) } : {}) };
}

export function ansibleOnce(opts: Opts): string {
  const passwordKey: Record<string, string> = { resend: "resend-password", "no-infra": "no-infra-smtp-password" };
  const smtp: any = {
    smtp_server: opts.smtp_server, smtp_port: opts.smtp_port,
    smtp_username: opts.smtp_username, smtp_password: opts.smtp_password,
  };
  const key = passwordKey[String(opts["provider-smtp"] ?? "resend")];
  if (key && smtp.smtp_password) smtp.smtp_password = parLookup(key);
  const once: any = opts.once ?? {};
  const configured = { ...once, applications: (once.applications ?? []).map((app: any) => applicationData(smtp, app)) };
  return yaml([{ name: "Reconcile ONCE applications", become: true, once: configured }]);
}

// The authorized_keys lines for the current generation, one per repository
// named in desired state.
//
// Each key carries every host its repository serves inside the ForceCommand, so
// a key leaked from one repository cannot redeploy another repository's
// application, and the client never has to name a host at all. Pure and
// deterministic: this is rendered into the artifact the colours compare byte
// for byte, which is also why the key comment holds no timestamp.
export function deployKeysContent(opts: Opts): string {
  const lines = publicKeys(opts).map(
    ({ hosts, public: pub }) => `restrict,command="/usr/local/bin/deploy ${hosts.join(" ")}" ${pub}`,
  );
  return lines.length ? `${lines.join("\n")}\n` : "";
}

function ansibleRemoteSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, "ansible-remote");
  const data = dataFn(opts);
  return [
    templateSpec({ name: "tools/ansible/ansible.cfg", content: ansibleCfg }, `${dir}/ansible.cfg`, data),
    templateSpec({ name: "tools/ansible/main.yml", content: ansibleMain }, `${dir}/main.yml`, data),
    templateSpec({ name: "tools/ansible/files/authorized-keys", content: authorizedKeys }, `${dir}/files/authorized-keys`, data),
    rawSpec(`${dir}/deploy_keys`, deployKeysContent(opts)),
    templateSpec({ name: "tools/ansible/files/deploy", content: deploy }, `${dir}/files/deploy`, data),
    templateSpec({ name: "tools/ansible/library/once", content: onceModule }, `${dir}/library/once`, data),
    rawSpec(`${dir}/inventory.json`, inventory(data)), rawSpec(`${dir}/once.yml`, ansibleOnce(data)),
  ];
}

export async function ansibleRemoteStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, "ansible-remote");
  const rendered = scaffold(opts, ansibleRemoteSpecs(opts));
  if (["build", "delete"].includes(String(opts["red/event"]))) return rendered;
  return ansibleStep(rendered, { dir, inventory: "inventory.json", playbooks: { create: "main.yml" }, hostKeyChecking: false });
}

function localHostAlias(data: Opts): string {
  return String(data.name || data.profile || "once");
}

export function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, "ansible-local");
  const data = dataFn(opts);
  const specs = [
    templateSpec({ name: "tools/ansible-local/ansible.cfg", content: ansibleLocalCfg }, `${dir}/ansible.cfg`, data),
    templateSpec({ name: "tools/ansible-local/inventory.ini", content: ansibleLocalInventory }, `${dir}/inventory.ini`, data),
    templateSpec({ name: "tools/ansible-local/main.yml", content: ansibleLocalMain }, `${dir}/main.yml`, data),
  ];
  return ansibleWithSpec(opts, {
    dir, inventory: "inventory.ini", playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: data.profile,
      ssh_hosts: [{name:data.profile,ip:data.ip,user:data.user,identity_file:data['ssh-private-key-path']}],
      block_state: opts["red/event"] === "delete" ? "absent" : "present",
    },
  }, specs);
}
