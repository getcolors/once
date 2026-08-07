import json
from pathlib import Path

from blue.runtime import ExecResult
from blue.workflow import run
from package_once_blue.describe import _container_for_host, describe_report, image_repository_tag, parse_once_list
from package_once_blue.tools import ansible_once, render_fn
from blue.cli import read_pars
from package_once_blue.utils import apps_domains
from package_once_blue.validate import state_errors
from package_once_blue.workflow import once_workflow, start_step, wire_fn

valid = {
    "profile": "test",
    "workdir": ".once",
    "once": {"applications": [{"host": "www.example.com", "image": "example/app:latest"}]},
    "provider-compute": "digitalocean",
    "provider-smtp": "resend",
    "provider-dns": "cloudflare",
    "provider-backend": "local",
    "compute-prevent-destroy": True,
    "digitalocean-name": "once",
    "digitalocean-region": "ams3",
    "digitalocean-size": "s-1vcpu-1gb",
    "digitalocean-image": "ubuntu",
    "digitalocean-ssh-keys": "key-id",
}


def test_one_parameter_namespace_and_no_colour_keeps_one_of_its_own():
    assert read_pars({"port": 1}, {"COLORS_PAR_PORT": "3"})["port"] == 3
    assert (
        read_pars({"port": 1}, {"BLUE_PAR_PORT": "2", "ONCE_PAR_PORT": "2", "RED_PAR_PORT": "2"})[
            "port"
        ]
        == 1
    )


def test_zones_and_generated_application_dns_records():
    assert apps_domains({"once": {"applications": [{"host": "b.example.net"}, {"host": "a.example.com"}, {"host": "c.example.net"}]}}) == ["example.com", "example.net"]
    rendered = json.loads(render_fn("apps", {"ip": "203.0.113.10", "applications": [{"host": "www.example.com"}]}))
    assert list(rendered["resource"]["cloudflare_dns_record"].values()) == [{"content": "203.0.113.10", "name": "www.example.com", "proxied": True, "ttl": 1, "type": "A", "zone_id": '${data.cloudflare_zone.domains["example.com"].id}'}]


def test_yandex_dns_records_are_absolute_unproxied_and_carry_mx_priority_in_data():
    apps = json.loads(render_fn("apps", {"provider": "yandex", "ip": "203.0.113.10", "applications": [{"host": "www.example.com"}]}))
    assert list(apps["resource"]["yandex_dns_recordset"].values()) == [
        {"data": ["203.0.113.10"], "name": "www.example.com.", "ttl": 300, "type": "A", "zone_id": '${yandex_dns_zone.domains["example.com"].id}'}
    ]
    smtp = json.loads(render_fn("smtp", {
        "provider": "yandex",
        "domains": [{"zone": "example.com", "records": [
            {"name": "send.example.com", "record": "send", "type": "MX", "priority": 10, "value": "feedback-smtp.eu-west-1.amazonses.com"},
            {"name": "send.example.com", "record": "send", "type": "TXT", "value": "v=spf1 include:amazonses.com ~all"},
        ]}],
    }))
    records = list(smtp["resource"]["yandex_dns_recordset"].values())
    assert [record["data"] for record in records] == [["10 feedback-smtp.eu-west-1.amazonses.com."], ['"v=spf1 include:amazonses.com ~all"']]
    assert all(record["name"] == "send.example.com." for record in records)
    assert any("yandex-cloud-id" in error for error in state_errors({**valid, "provider-dns": "yandex"}))


def test_ansible_rendering_defers_secrets_and_is_color_portable():
    rendered = ansible_once(
        {
            "provider-smtp": "resend",
            "resend-password": "real-secret",
            "smtp_server": "smtp.resend.com",
            "smtp_port": 587,
            "smtp_username": "resend",
            "smtp_password": "real-secret",
            "once": {
                "applications": [
                    {
                        "host": "www.example.com",
                        "image": "app",
                        "env": {"DATABASE_URL": "app-database-url"},
                    }
                ]
            },
        }
    )
    assert "real-secret" not in rendered
    assert "COLORS_PAR_APP_DATABASE_URL" in rendered


async def test_validation_and_lifecycle_safety():
    assert state_errors(valid) == []
    assert (await start_step({**valid, "blue/event": "build"}, {}))["blue/exit"] == 0
    created = await start_step({**valid, "blue/event": "create"}, {})
    assert created["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in created["blue/err"]


def test_create_build_and_delete_use_inverse_graphs():
    assert wire_fn("once/start", {"blue/event": "build"})[1:] == ("once/tofu-compute", "once/tofu-smtp")
    # Credentials are withdrawn before anything is destroyed, and publishing
    # follows the configured host rather than the workstation.
    assert wire_fn("once/start", {"blue/event": "delete"})[1:] == ("once/github",)
    assert wire_fn("once/github", {"blue/event": "delete"})[1:] == ("once/ansible-cleanup",)
    assert wire_fn("once/ansible-remote", {"blue/event": "create"})[1:] == ("once/github",)


async def test_dry_run_needs_no_credentials_and_touches_nothing(tmp_path):
    workdir = tmp_path / "missing"
    result = await run(
        once_workflow,
        {**valid, "workdir": str(workdir), "blue/event": "create", "blue/dry-run": True},
    )
    assert result["blue/exit"] == 0
    assert not workdir.exists()


async def test_a_build_renders_the_complete_production_tree_without_tools(tmp_path):
    result = await run(once_workflow, {**valid, "workdir": str(tmp_path), "blue/event": "build"})
    assert result["blue/exit"] == 0
    assert len([path for path in (tmp_path / "test").rglob("*") if path.is_file()]) == 21


async def test_describe_helpers_are_process_free_with_an_injected_runner():
    assert parse_once_list("\x1b[32mwww.example.com (running)\x1b[0m") == [{"host": "www.example.com", "status": "running"}]
    assert image_repository_tag("registry:5000/acme/app") == {"repository": "registry:5000/acme/app", "tag": "latest", "image": "registry:5000/acme/app:latest"}

    async def runner(*_args, **_kwargs):
        return ExecResult(exit=1, out="", err="offline")

    report = await describe_report({**valid}, runner, False)
    assert report["compute"]["status"] == "absent"


def _labelled(host: str, image: str) -> dict:
    return {"Name": f"/once-app-{host.replace('.', '-')}", "Config": {"Image": image, "Labels": {"once": json.dumps({"name": "app", "image": image, "host": host})}}}


def test_container_matching_prefers_the_once_label_host():
    # the longer host is listed first, so a substring match returns it
    containers = [_labelled("www.example.com", "ghcr.io/org/site:latest"), _labelled("example.com", "ghcr.io/org/redirect:latest")]
    assert _container_for_host(containers, "example.com")["Config"]["Image"] == "ghcr.io/org/redirect:latest"
    assert _container_for_host(containers, "www.example.com")["Config"]["Image"] == "ghcr.io/org/site:latest"

    # the name carries no host, so the traefik rule is the only evidence; the
    # dot-substituted form stays ambiguous by nature, which is why the label decides
    unlabelled = [{"Name": "/app-1", "Config": {"Image": "ghcr.io/org/site:latest", "Labels": {"traefik.http.routers.app.rule": "Host(`www.example.com`)"}}}]
    assert _container_for_host(unlabelled, "www.example.com") is not None
    assert _container_for_host(unlabelled, "example.com") is None
