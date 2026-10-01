from __future__ import annotations

from blue.runtime import ExecResult

from package_once_blue.github import (
    commands,
    known_hosts_line,
    github_step,
    key_comment,
    placeholder_keys,
    publish_commands,
    revoke_commands,
)
from package_once_blue.tools import deploy_keys_content

# Two applications share one repository — the same image answering for two
# hosts — and a third names none. That is the shape per-application keys got
# wrong: both published into acme/site's environment and the second overwrote
# the first.
opts = {
    "profile": "prod",
    "ip": "203.0.113.10",
    "github-token": "gh_token",
    "once": {
        "applications": [
            {"host": "www.example.com", "github": "acme/site"},
            {"host": "www.example.net", "github": "acme/site"},
            {"host": "app.example.com"},
        ]
    },
}

key = {
    "hosts": ["www.example.com", "www.example.net"],
    "github": "acme/site",
    "public": "ssh-ed25519 AAAA one",
    "private-file": "/tmp/once/key-0",
}


def recorder(exit_code: int = 0):
    """A fake runner in place of the runtime seam.

    Records every invocation so a test can assert the exact argv without a
    process.
    """
    calls: list[dict] = []

    async def run_fn(args, *, env=None):
        calls.append({"args": args, "env": env})
        return ExecResult(exit=exit_code, out="", err="" if exit_code == 0 else "boom")

    return calls, run_fn


def test_a_repository_is_published_once_however_many_hosts_it_serves():
    # Keyed per application this issued two publishes into one environment, and
    # the second overwrote the first's key.
    assert len(commands({**opts, "blue/event": "create", "once/deploy-keys": [key]})) == 5


def test_publish_sends_the_address_as_a_variable_and_the_key_as_a_secret():
    environment, ip, user, known_hosts, secret = publish_commands(opts, key)
    # The environment is created first — writing into one that does not exist is
    # a 404, and nothing guarantees a workflow made it.
    assert environment["args"] == ["gh", "api", "--method", "PUT", "--silent", "repos/acme/site/environments/prod"]
    # The address and user are variables — DNS already reveals them, and masking
    # them only makes CI logs harder to read.
    assert ip["args"] == [
        "gh", "variable", "set", "SERVER_IP",
        "--repo", "acme/site", "--env", "prod",
        "--body", "203.0.113.10",
    ]
    assert user["args"] == [
        "gh", "variable", "set", "SERVER_USER",
        "--repo", "acme/site", "--env", "prod",
        "--body", "deploy",
    ]
    # The host key is pinned as a variable, so CI stops asking the network who
    # the server is on every deploy.
    assert known_hosts["args"] == [
        "gh", "variable", "set", "SSH_KNOWN_HOSTS",
        "--repo", "acme/site", "--env", "prod", "--body", "",
    ]
    # The private key is read from its file, never passed as an argument.
    assert secret["args"][:2] == ["sh", "-c"]
    assert "< '/tmp/once/key-0'" in secret["args"][2]


def test_revoking_needs_no_key_material():
    assert [cmd["args"] for cmd in revoke_commands(opts, {"github": "acme/site"})] == [
        ["gh", "variable", "delete", "SERVER_IP", "--repo", "acme/site", "--env", "prod"],
        ["gh", "variable", "delete", "SERVER_USER", "--repo", "acme/site", "--env", "prod"],
        ["gh", "variable", "delete", "SSH_KNOWN_HOSTS", "--repo", "acme/site", "--env", "prod"],
        ["gh", "secret", "delete", "SSH_PRIVATE_KEY", "--repo", "acme/site", "--env", "prod"],
    ]


async def test_a_build_never_reaches_github():
    # wire_fn runs the same branch for build and create, so the event check in
    # the step is what keeps a build offline.
    calls, run_fn = recorder()
    await github_step({**opts, "blue/event": "build"}, run_fn)
    assert calls == []


async def test_the_token_travels_in_the_environment():
    calls, run_fn = recorder()
    await github_step({**opts, "blue/event": "create", "once/deploy-keys": [key]}, run_fn)
    assert calls
    # Every gh call carries it; the host-key read is an ssh call and has no
    # business with a GitHub token.
    assert all(c["env"] == {"GH_TOKEN": "gh_token"} for c in calls if c["args"][0] == "gh")


async def test_a_failed_publish_fails_the_step():
    _calls, run_fn = recorder(1)
    result = await github_step({**opts, "blue/event": "create", "once/deploy-keys": [key]}, run_fn)
    assert result["blue/exit"] == 1
    assert "acme/site" in result["blue/err"]


async def test_a_failed_revoke_does_not():
    # Delete has to be re-runnable, and a missing secret is the state it is
    # trying to reach.
    calls, run_fn = recorder(1)
    result = await github_step({**opts, "blue/event": "delete"}, run_fn)
    assert result["blue/exit"] == 0
    # And a repository named by two applications is revoked once, not twice.
    assert len(calls) == 4


def test_each_key_is_pinned_to_the_hosts_its_repository_serves():
    # The entry names every host, so the client never has to — and a key still
    # cannot reach an application belonging to another repository.
    keys = [
        {"hosts": ["www.example.com", "www.example.net"], "public": "ssh-ed25519 AAAA one"},
        {"hosts": ["app.example.com"], "public": "ssh-ed25519 BBBB two"},
    ]
    assert deploy_keys_content({**opts, "once/deploy-keys": keys}) == (
        'restrict,command="/usr/local/bin/deploy www.example.com www.example.net" ssh-ed25519 AAAA one\n'
        'restrict,command="/usr/local/bin/deploy app.example.com" ssh-ed25519 BBBB two\n'
    )


def test_a_build_renders_a_fixed_placeholder():
    # A fresh key per build would make the artifact nondeterministic and break
    # byte parity between the colours.
    first = placeholder_keys(opts)
    assert first == placeholder_keys(opts)
    # One key per repository, not per application.
    assert len(first) == 1
    assert first[0]["hosts"] == ["www.example.com", "www.example.net"]
    assert first[0]["public"].endswith("once-deploy-prod-acme-site")


def test_the_key_comment_carries_no_clock_reading():
    # And slugs the slash, because it sits in an authorized_keys comment.
    assert key_comment(opts, "acme/site") == "once-deploy-prod-acme-site"


def test_an_application_without_a_repository_gets_no_key_at_all():
    # Not a key on the box and nothing else — nothing.
    assert not any("app.example.com" in k["hosts"] for k in placeholder_keys(opts))


def test_a_host_key_becomes_a_known_hosts_line():
    # The trailing comment is the server's own hostname at key generation time
    # and means nothing to a client.
    assert known_hosts_line("203.0.113.10", "ssh-ed25519 AAAAC3Nz root@once\n") == (
        "203.0.113.10 ssh-ed25519 AAAAC3Nz"
    )
    assert known_hosts_line("203.0.113.10", "") is None
    assert known_hosts_line("203.0.113.10", "No such file or directory") is None


async def test_generated_private_keys_are_removed_when_scope_exits_before_publication():
    from pathlib import Path
    from package_once_blue import access, github
    directory = None
    async def keygen(args, **kwargs):
        path = Path(args[-1])
        path.write_text('temporary private key')
        Path(str(path) + '.pub').write_text('ssh-ed25519 fixture')
        return ExecResult(exit=0, out='', err='')
    async def body():
        nonlocal directory
        keys, error = await github.generate_keys(opts, keygen)
        assert error is None
        directory = Path(keys[0]['private-file']).parent
        assert directory.exists()
        # Compute/SMTP failures return an error result before the GitHub step.
        return {'blue/exit': 1, 'blue/err': 'compute failed'}
    assert (await access.scoped(body))['blue/exit'] == 1
    assert not directory.exists()


async def test_partial_key_generation_failure_cleans_directory_without_scope():
    from pathlib import Path
    from package_once_blue import github
    directory = None
    async def keygen(args, **kwargs):
        nonlocal directory
        path = Path(args[-1])
        directory = path.parent
        path.write_text('partial private key')
        return ExecResult(exit=1, out='', err='generation failed')
    keys, error = await github.generate_keys(opts, keygen)
    assert keys == [] and 'generation failed' in error
    assert not directory.exists()


async def test_key_generation_exception_cleans_directory_without_scope():
    from pathlib import Path
    import pytest
    from package_once_blue import github
    directory = None
    async def keygen(args, **kwargs):
        nonlocal directory
        directory = Path(args[-1]).parent
        raise RuntimeError('process failed')
    with pytest.raises(RuntimeError, match='process failed'):
        await github.generate_keys(opts, keygen)
    assert not directory.exists()
