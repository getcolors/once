import {existsSync,writeFileSync} from "node:fs";
import {dirname} from "node:path";
import {scoped} from "../src/access.ts";
import { expect, test } from "bun:test";
import {
  commands,
  generateKeys,
  githubStep,
  keyComment,
  placeholderKeys,
  knownHostsLine,
  publishCommands,
  revokeCommands,
  type DeployKey,
} from "../src/github.ts";
import { deployKeysContent } from "../src/tools.ts";

// Two applications share one repository — the same image answering for two
// hosts — and a third names none. That is the shape per-application keys got
// wrong: both published into `acme/site`'s environment and the second
// overwrote the first.
const opts = {
  profile: "prod",
  ip: "203.0.113.10",
  "github-token": "gh_token",
  once: {
    applications: [
      { host: "www.example.com", github: "acme/site" },
      { host: "www.example.net", github: "acme/site" },
      { host: "app.example.com" },
    ],
  },
};

function recorder(exit = 0) {
  const calls: Array<{ args: string[]; env?: Record<string, string | undefined> }> = [];
  return {
    calls,
    runFn: async (args: string[], o: { env?: Record<string, string | undefined> } = {}) => {
      calls.push({ args, env: o.env });
      return { exit, out: "", err: exit === 0 ? "" : "boom" };
    },
  };
}

const key: DeployKey = {
  hosts: ["www.example.com", "www.example.net"],
  github: "acme/site",
  public: "ssh-ed25519 AAAA one",
  privateFile: "/tmp/once/key-0",
};

test("a repository is published once however many hosts it serves", () => {
  // Keyed per application this issued two publishes into one environment, and
  // the second overwrote the first's key.
  expect(commands({ ...opts, "red/event": "create", "once/deploy-keys": [key] })).toHaveLength(5);
});

test("publish sends the address as a variable and the key as a secret", () => {
  const [environment, ip, user, knownHosts, secret] = publishCommands(opts, key);
  // The environment is created first — writing into one that does not exist is
  // a 404, and nothing guarantees a workflow made it.
  expect(environment!.args).toEqual(["gh", "api", "--method", "PUT", "--silent", "repos/acme/site/environments/prod"]);
  // The address and user are variables — DNS already reveals them, and masking
  // them only makes CI logs harder to read.
  expect(ip!.args).toEqual([
    "gh", "variable", "set", "SERVER_IP",
    "--repo", "acme/site", "--env", "prod",
    "--body", "203.0.113.10",
  ]);
  expect(user!.args).toEqual([
    "gh", "variable", "set", "SERVER_USER",
    "--repo", "acme/site", "--env", "prod",
    "--body", "deploy",
  ]);
  // The host key is pinned as a variable, so CI stops asking the network who
  // the server is on every deploy.
  expect(knownHosts!.args).toEqual([
    "gh", "variable", "set", "SSH_KNOWN_HOSTS",
    "--repo", "acme/site", "--env", "prod", "--body", "",
  ]);
  // The private key is read from its file, never passed as an argument.
  expect(secret!.args.slice(0, 2)).toEqual(["sh", "-c"]);
  expect(secret!.args[2]).toContain("< '/tmp/once/key-0'");
});

test("revoking needs no key material", () => {
  expect(revokeCommands(opts, { github: "acme/site" }).map((c) => c.args)).toEqual([
    ["gh", "variable", "delete", "SERVER_IP", "--repo", "acme/site", "--env", "prod"],
    ["gh", "variable", "delete", "SERVER_USER", "--repo", "acme/site", "--env", "prod"],
    ["gh", "variable", "delete", "SSH_KNOWN_HOSTS", "--repo", "acme/site", "--env", "prod"],
    ["gh", "secret", "delete", "SSH_PRIVATE_KEY", "--repo", "acme/site", "--env", "prod"],
  ]);
});

test("a build never reaches GitHub", async () => {
  // wireFn runs the same branch for build and create, so the event check in the
  // step is what keeps a build offline.
  const { calls, runFn } = recorder();
  await githubStep({ ...opts, "red/event": "build" }, runFn);
  expect(calls).toEqual([]);
});

test("the token travels in the environment", async () => {
  const { calls, runFn } = recorder();
  await githubStep({ ...opts, "red/event": "create", "once/deploy-keys": [key] }, runFn);
  expect(calls.length).toBeGreaterThan(0);
  // Every gh call carries it; the host-key read is an ssh call and has no
  // business with a GitHub token.
  expect(calls.filter((c) => c.args[0] === "gh").every((c) => c.env?.GH_TOKEN === "gh_token")).toBe(true);
});

test("a failed publish fails the step", async () => {
  const { runFn } = recorder(1);
  const result = await githubStep({ ...opts, "red/event": "create", "once/deploy-keys": [key] }, runFn);
  expect(result["red/exit"]).toBe(1);
  expect(String(result["red/err"])).toContain("acme/site");
});

test("a failed revoke does not", async () => {
  // Delete has to be re-runnable, and a missing secret is the state it is
  // trying to reach.
  const { calls, runFn } = recorder(1);
  const result = await githubStep({ ...opts, "red/event": "delete" }, runFn);
  expect(result["red/exit"]).toBe(0);
  // And a repository named by two applications is revoked once, not twice.
  expect(calls).toHaveLength(4);
});

test("each key is pinned to the hosts its repository serves", () => {
  // The entry names every host, so the client never has to — and a key still
  // cannot reach an application belonging to another repository.
  const keys = [
    { hosts: ["www.example.com", "www.example.net"], public: "ssh-ed25519 AAAA one" },
    { hosts: ["app.example.com"], public: "ssh-ed25519 BBBB two" },
  ];
  expect(deployKeysContent({ ...opts, "once/deploy-keys": keys })).toBe(
    'restrict,command="/usr/local/bin/deploy www.example.com www.example.net" ssh-ed25519 AAAA one\n' +
      'restrict,command="/usr/local/bin/deploy app.example.com" ssh-ed25519 BBBB two\n',
  );
});

test("a build renders a fixed placeholder", () => {
  // A fresh key per build would make the artifact nondeterministic and break
  // byte parity between the colours.
  const a = placeholderKeys(opts);
  expect(a).toEqual(placeholderKeys(opts));
  // One key per repository, not per application.
  expect(a).toHaveLength(1);
  expect(a[0]!.hosts).toEqual(["www.example.com", "www.example.net"]);
  expect(a[0]!.public.endsWith("once-deploy-prod-acme-site")).toBe(true);
});

test("the key comment carries no clock reading", () => {
  // And slugs the slash, because it sits in an authorized_keys comment.
  expect(keyComment(opts, "acme/site")).toBe("once-deploy-prod-acme-site");
});

test("an application without a repository gets no key at all", () => {
  // Not a key on the box and nothing else — nothing.
  expect(placeholderKeys(opts).some((k) => k.hosts.includes("app.example.com"))).toBe(false);
});

test("a host key becomes a known_hosts line", () => {
  // The trailing comment is the server's own hostname at key generation time
  // and means nothing to a client.
  expect(knownHostsLine("203.0.113.10", "ssh-ed25519 AAAAC3Nz root@once\n"))
    .toBe("203.0.113.10 ssh-ed25519 AAAAC3Nz");
  expect(knownHostsLine("203.0.113.10", "")).toBeUndefined();
  expect(knownHostsLine("203.0.113.10", "No such file or directory")).toBeUndefined();
});


for (const fail of [false,true]) test(`deployment keys are cleaned when scope exits, failure=${fail}`,async()=>{
 let directory='';
 const body=async()=>{
  const [keys,error]=await generateKeys(opts,async args=>{
   const path=args[args.length-1]!;directory=dirname(path);writeFileSync(path,'private fixture');writeFileSync(path+'.pub','public fixture');return {exit:0,out:'',err:''};
  });
  expect(error).toBeUndefined();expect(keys).toHaveLength(1);expect(existsSync(directory)).toBe(true);
  if(fail)throw Error('compute failed');
 };
 if(fail)await expect(scoped(body)).rejects.toThrow('compute failed');else await scoped(body);
 expect(existsSync(directory)).toBe(false);
});
for(const failure of ['exit','throw','missing-public'])test(`partial key generation cleans unscoped files: ${failure}`,async()=>{
 let directory='';
 const run=()=>generateKeys(opts,async args=>{const path=args[args.length-1]!;directory=dirname(path);writeFileSync(path,'partial private fixture');if(failure==='throw')throw Error('runner failed');return {exit:failure==='exit'?1:0,out:'',err:'failed'};});
 if(failure==='exit')expect((await run())[1]).toContain('ssh-keygen failed');else await expect(run()).rejects.toThrow();
 expect(existsSync(directory)).toBe(false);
});
