import { expect, test } from "bun:test";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { run as runWorkflow } from "red/workflow";
import { run as runCli } from "../src/cli.ts";
import { containerForHost, describeReport, imageRepositoryTag, parseOnceList } from "../src/describe.ts";
import { ansibleOnce, renderFn } from "../src/tools.ts";
import { readPars } from "red/cli";
import { appsDomains } from "../src/utils.ts";
import { stateErrors } from "../src/validate.ts";
import { onceWorkflow, startStep, wireFn } from "../src/workflow.ts";

const valid = {
  profile: "test",
  workdir: ".once",
  once: { applications: [{ host: "www.example.com", image: "example/app:latest" }] },
  "provider-compute": "digitalocean",
  "provider-smtp": "resend",
  "provider-dns": "cloudflare",
  "provider-backend": "s3",
    "s3-bucket": "once-tests", "s3-region": "eu-west-1",
    "compute-ssh-sources": ["0.0.0.0/0"], "compute-http-sources": ["0.0.0.0/0"],
  "compute-prevent-destroy": true,
  "digitalocean-name": "once",
  "digitalocean-region": "ams3",
  "digitalocean-size": "s-1vcpu-1gb",
  "digitalocean-image": "ubuntu",
  "digitalocean-ssh-keys": "key-id",
};

function files(root: string): string[] {
  const result: string[] = [];
  const visit = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) visit(path); else result.push(relative(root, path));
    }
  };
  visit(root);
  return result.sort();
}

test("unknown commands are rejected", async () => {
  expect((await runCli("bogus"))["red/exit"]).toBe(2);
});

test("one parameter namespace, and no colour keeps one of its own", () => {
  expect(readPars({ port: 1 }, { COLORS_PAR_PORT: "3" }).port).toBe(3);
  expect(
    readPars({ port: 1 }, { RED_PAR_PORT: "2", ONCE_PAR_PORT: "2", GREEN_PAR_PORT: "2" }).port,
  ).toBe(1);
});

test("zones and generated application DNS records", () => {
  expect(appsDomains({ once: { applications: [{ host: "b.example.net" }, { host: "a.example.com" }, { host: "c.example.net" }] } })).toEqual(["example.com", "example.net"]);
  const rendered = JSON.parse(renderFn("apps", { ip: "203.0.113.10", applications: [{ host: "www.example.com" }] }));
  const records = Object.values(rendered.resource.cloudflare_dns_record) as any[];
  expect(records).toEqual([{ content: "203.0.113.10", name: "www.example.com", proxied: true, ttl: 1, type: "A", zone_id: '${data.cloudflare_zone.domains["example.com"].id}' }]);
});

test("yandex DNS records are absolute, unproxied, and carry MX priority in data", () => {
  const apps = JSON.parse(renderFn("apps", { provider: "yandex", ip: "203.0.113.10", applications: [{ host: "www.example.com" }] }));
  expect(Object.values(apps.resource.yandex_dns_recordset)).toEqual([
    { data: ["203.0.113.10"], name: "www.example.com.", ttl: 300, type: "A", zone_id: '${yandex_dns_zone.domains["example.com"].id}' },
  ]);
  const smtp = JSON.parse(renderFn("smtp", {
    provider: "yandex",
    domains: [{ zone: "example.com", records: [
      { name: "send.example.com", record: "send", type: "MX", priority: 10, value: "feedback-smtp.eu-west-1.amazonses.com" },
      { name: "send.example.com", record: "send", type: "TXT", value: "v=spf1 include:amazonses.com ~all" },
    ] }],
  }));
  const records = Object.values(smtp.resource.yandex_dns_recordset) as any[];
  expect(records.map((record) => record.data)).toEqual([["10 feedback-smtp.eu-west-1.amazonses.com."], ['"v=spf1 include:amazonses.com ~all"']]);
  expect(records.every((record) => record.name === "send.example.com.")).toBe(true);
  expect(stateErrors({ ...valid, "provider-dns": "yandex" }).join(" ")).toMatch(/yandex-cloud-id/);
});

test("Ansible rendering defers secrets and is color-portable", () => {
  const yaml = ansibleOnce({
    "provider-smtp": "resend",
    "resend-password": "real-secret",
    smtp_server: "smtp.resend.com", smtp_port: 587, smtp_username: "resend", smtp_password: "real-secret",
    once: { applications: [{ host: "www.example.com", image: "app", env: { DATABASE_URL: "app-database-url" } }] },
  });
  expect(yaml).not.toContain("real-secret");
  expect(yaml).toContain("COLORS_PAR_APP_DATABASE_URL");
});

test("validation and lifecycle safety", async () => {
  expect(stateErrors(valid)).toEqual([]);
  expect((await startStep({ ...valid, "red/event": "build" }, {}))["red/exit"]).toBe(0);
  const created = await startStep({ ...valid, "red/event": "create" }, {});
  expect(created["red/exit"]).toBe(2);
  expect(created["red/err"]).toMatch(/COLORS_PAR_DO_TOKEN/);
});

test("create/build and delete use inverse graphs", () => {
  expect(wireFn("once/start", { "red/event": "build" })?.slice(1)).toEqual(["once/tofu-compute"]);
  // Credentials are withdrawn before anything is destroyed, and publishing
  // follows the configured host rather than the workstation.
  expect(wireFn("once/start", { "red/event": "delete" })?.slice(1)).toEqual(["once/github"]);
  expect(wireFn("once/github", { "red/event": "delete" })?.slice(1)).toEqual(["once/ansible-cleanup"]);
  expect(wireFn("once/ansible-remote", { "red/event": "create" })?.slice(1)).toEqual(["once/github"]);
});

test("dry-run needs no credentials and touches nothing", async () => {
  const workdir = join(tmpdir(), `once-red-dry-${Date.now()}`);
  const result = await runWorkflow(onceWorkflow, { ...valid, workdir, "red/event": "create", "red/dry-run": true });
  expect(result["red/exit"]).toBe(0);
  expect(() => readdirSync(workdir)).toThrow();
});

test("a build renders the complete production tree without tools", async () => {
  const workdir = mkdtempSync(join(tmpdir(), "once-red-"));
  try {
    const result = await runWorkflow(onceWorkflow, { ...valid, workdir, "red/event": "build" });
    expect(result["red/exit"]).toBe(0);
    expect(files(join(workdir, "test"))).toHaveLength(23);
  } finally { rmSync(workdir, { recursive: true, force: true }); }
});

test("describe helpers remain process-free with an injected runner", async () => {
  expect(parseOnceList("\u001b[32mwww.example.com (running)\u001b[0m")).toEqual([{ host: "www.example.com", status: "running" }]);
  expect(imageRepositoryTag("registry:5000/acme/app")).toEqual({ repository: "registry:5000/acme/app", tag: "latest", image: "registry:5000/acme/app:latest" });
  const runner = async () => ({ exit: 1, out: "", err: "offline" });
  const report = await describeReport({ ...valid }, runner, false);
  expect(report.compute.status).toBe("absent");
});

test("container matching prefers the once label host", () => {
  const labelled = (host: string, image: string) => ({
    Name: `/once-app-${host.replaceAll(".", "-")}`,
    Config: { Image: image, Labels: { once: JSON.stringify({ name: "app", image, host }) } },
  });
  // the longer host is listed first, so a substring match returns it
  const containers = [labelled("www.example.com", "ghcr.io/org/site:latest"), labelled("example.com", "ghcr.io/org/redirect:latest")];
  expect(containerForHost(containers, "example.com").Config.Image).toBe("ghcr.io/org/redirect:latest");
  expect(containerForHost(containers, "www.example.com").Config.Image).toBe("ghcr.io/org/site:latest");

  // the name carries no host, so the traefik rule is the only evidence; the
  // dot-substituted form stays ambiguous by nature, which is why the label decides
  const unlabelled = [{ Name: "/app-1", Config: { Image: "ghcr.io/org/site:latest", Labels: { "traefik.http.routers.app.rule": "Host(`www.example.com`)" } } }];
  expect(containerForHost(unlabelled, "www.example.com")).toBeDefined();
  expect(containerForHost(unlabelled, "example.com")).toBeUndefined();
});

test("DMARC settings validate explicit policy, managed providers, and one bare report address", () => {
  const errors = (extra: Record<string, unknown>) => stateErrors({ ...valid, ...extra }).filter(error => error.startsWith("smtp-dmarc-"));
  expect(errors({})).toEqual([]);
  for (const policy of ["none", "quarantine", "reject"]) {
    expect(errors({ "smtp-dmarc-policy": policy, "smtp-dmarc-rua": "Reports+DMARC@example.com" })).toEqual([]);
  }
  for (const policy of [null, true, 0, "", "NONE", "reject\n", [], {}]) {
    expect(errors({ "smtp-dmarc-policy": policy })).toEqual(["smtp-dmarc-policy must be none, quarantine, or reject"]);
  }
  for (const provider of [{ "provider-smtp": "no-infra" }, { "provider-dns": "no-infra" }]) {
    expect(errors({ ...provider, "smtp-dmarc-policy": "none" })).toEqual(["smtp-dmarc-policy requires resend SMTP and managed DNS"]);
  }
  expect(errors({ "provider-dns": "yandex", "smtp-dmarc-policy": "none" })).toEqual([]);
  expect(errors({ "smtp-dmarc-rua": "reports@example.com" })).toEqual(["smtp-dmarc-rua requires smtp-dmarc-policy"]);
  for (const rua of [null, true, [], "", "${report}@example.com", "%{report}@example.com", "mailto:a@example.com", "A <a@example.com>", "a@example.com,b@example.com", "a@example.com; p=none", "a@example.com\n", " a@example.com", "a@-example.com", "a@localhost", `${"a".repeat(243)}@example.com`]) {
    expect(errors({ "smtp-dmarc-policy": "none", "smtp-dmarc-rua": rua })).toEqual(["smtp-dmarc-rua must be a single email address"]);
  }
});

test("DMARC renders one sender-domain record per zone without changing provider records", () => {
  const domains = ["example.com", "example.net"].map(zone => ({ zone, records: [{ record: "SPF", type: "TXT", name: `send.notifications.${zone}`, value: "v=spf1 ~all" }] }));
  for (const provider of ["cloudflare", "yandex"]) {
    const resource = provider === "cloudflare" ? "cloudflare_dns_record" : "yandex_dns_recordset";
    const baseline = JSON.parse(renderFn("smtp", { provider, domains })).resource[resource];
    expect(Object.keys(baseline)).toHaveLength(2);
    for (const policy of ["none", "quarantine", "reject"]) {
      for (const rua of [undefined, "reports@example.com"]) {
        const records = JSON.parse(renderFn("smtp", { provider, domains, "smtp-dmarc-policy": policy, "smtp-dmarc-rua": rua })).resource[resource];
        expect(Object.keys(records)).toHaveLength(4);
        for (const [key, record] of Object.entries(baseline)) expect(records[key]).toEqual(record);
        const dmarc = Object.entries(records).filter(([key]) => key.endsWith("_DMARC_TXT")) as [string, any][];
        expect(dmarc).toHaveLength(2);
        for (const [index, [, record]] of dmarc.entries()) {
          expect(record.name).toBe(`_dmarc.notifications.${domains[index]!.zone}${provider === "yandex" ? "." : ""}`);
          const value = `"v=DMARC1; p=${policy}${rua ? `; rua=mailto:${rua}` : ""}"`;
          expect(provider === "cloudflare" ? record.content : record.data[0]).toBe(value);
        }
      }
    }
  }
});
