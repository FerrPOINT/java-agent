#!/usr/bin/env python3
"""Verify backend auth and writable, shared named paths in Compose's canonical model."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILES = (
    "docker-compose.yml",
    "docker-compose.dev.yml",
    "docker-compose.local.yml",
    "docker-compose.prod.yml",
)


def load_compose(source: Path) -> dict:
    # Config needs no daemon. Avoid loading operator credentials or resolving env files.
    result = subprocess.run(
        ["docker", "compose", "--env-file", os.devnull, "-p", "java-agent-contract",
         "-f", str(source), "config", "--format", "json", "--no-interpolate",
         "--no-env-resolution"],
        check=True, capture_output=True, text=True, encoding="utf8",
    )
    return json.loads(result.stdout)


def service(model: dict, names: tuple[str, ...], source: Path) -> dict:
    for name in names:
        if name in model["services"]:
            return model["services"][name]
    raise AssertionError(f"{source.name}: {'/'.join(names)} service is missing")


def persistent_paths(model: dict, block: dict, label: str, source: Path) -> dict[str, str]:
    if "user" in block and block["user"] not in ("agent", "agent:agent", "10001", "10001:10001"):
        raise AssertionError(f"{source.name}: {label} must retain the image's agent identity")
    environment = block.get("environment", {})
    working_key = "AGENT_CORE_WORKING_DIRECTORY" if label == "backend" else "BOT_WORKING_DIRECTORY"
    for key, value in ((working_key, "/workspace"), ("AGENT_ATTACHMENTS_CACHE_ROOT", "/artifacts")):
        if environment.get(key) != value:
            raise AssertionError(f"{source.name}: {label} must set {key} to {value}")
    paths = {}
    for target in ("/workspace", "/artifacts"):
        mounts = [mount for mount in block.get("volumes", []) if mount.get("target") == target]
        if len(mounts) != 1:
            raise AssertionError(f"{source.name}: {label} must mount {target} exactly once")
        mount = mounts[0]
        name = mount.get("source")
        if mount.get("type") != "volume" or not name or name not in model.get("volumes", {}):
            raise AssertionError(f"{source.name}: {label} {target} must use a declared named volume")
        options = mount.get("volume", {})
        if mount.get("read_only") or options.get("nocopy") or options.get("subpath"):
            raise AssertionError(f"{source.name}: {label} {target} must be writable with image ownership")
        paths[target] = name
    return paths


def validate_compose(model: dict, source: Path, *, require_bot: bool) -> None:
    backend = service(model, ("backend", "agent"), source)
    backend_paths = persistent_paths(model, backend, "backend", source)
    if not require_bot:
        return
    bot = service(model, ("telegram-bot", "bot"), source)
    environment = bot.get("environment", {})
    if not environment.get("BOT_BACKEND_URL") or not environment.get("BOT_BACKEND_API_KEY"):
        raise AssertionError(f"{source.name}: bot must receive the backend URL and API key")
    if persistent_paths(model, bot, "bot", source) != backend_paths:
        raise AssertionError(f"{source.name}: backend and bot must share the same named volumes")


def main() -> None:
    for filename in COMPOSE_FILES:
        source = ROOT / filename
        validate_compose(load_compose(source), source, require_bot=filename != COMPOSE_FILES[0])
    print("compose backend auth and persistent path wiring: green")


if __name__ == "__main__":
    main()
