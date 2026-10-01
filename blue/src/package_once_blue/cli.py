from __future__ import annotations

import asyncio
import sys
from blue.cli import find_up, run_cli

from .describe import describe_file
from .workflow import once_workflow
from . import access

USAGE = "Usage: blue <build|create|delete|describe|ssh> [-f|--file colors.yml] [--dry-run]"




def default_args(args: list[str]) -> list[str]:
    if any(arg in ("-f", "--file") or arg.startswith("--file=") for arg in args):
        return args
    return [*args, "-f", find_up("colors.yml") or "colors.yml"]


def _file(args: list[str]) -> str:
    for index, arg in enumerate(args):
        if arg.startswith("--file="):
            return arg.split("=", 1)[1]
        if arg in ("-f", "--file"):
            return args[index + 1] if index + 1 < len(args) else find_up("colors.yml") or "colors.yml"
    return find_up("colors.yml") or "colors.yml"


async def run(*input: str) -> dict:
    args = default_args(list(input))
    command = args[0] if args else None
    if command in ("help", "--help", "-h"):
        return {"blue/exit": 0, "blue/err": USAGE}
    if command == "describe":
        return await describe_file(_file(args))
    if command in ("build", "create", "delete", "ssh"):
        return await access.scoped(lambda: run_cli(once_workflow, args))
    return {"blue/exit": 2, "blue/err": USAGE}


def exec(args: list[str] | None = None) -> None:
    result = asyncio.run(run(*(sys.argv[1:] if args is None else args)))
    if result.get("blue/err"):
        print(result["blue/err"], file=sys.stdout if (result.get("blue/exit") or 0) == 0 else sys.stderr)
        if result.get("blue/trace"):
            print(result["blue/trace"], file=sys.stderr)
    raise SystemExit(result.get("blue/exit") or 0)
