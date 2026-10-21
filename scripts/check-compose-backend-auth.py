#!/usr/bin/env python3
"""Verify bot deployments pass backend auth and persistent shared paths in every Compose profile."""

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILES = (
    "docker-compose.yml",
    "docker-compose.dev.yml",
    "docker-compose.local.yml",
    "docker-compose.prod.yml",
)
BOT_COMPOSE_FILES = COMPOSE_FILES[1:]


def telegram_bot_block(content: str, source: Path) -> str:
    match = re.search(r"(?ms)^  (?:telegram-bot|bot):\n(?P<body>.*?)(?=^  \S|\Z)", content)
    if match:
        return match.group("body")
    raise AssertionError(f"{source.name}: bot service is missing")


def assert_persistent_backend_paths(content: str, source: Path) -> None:
    backend = re.search(r"(?ms)^  (?:backend|agent):\n(?P<body>.*?)(?=^  \S|\Z)", content)
    if not backend:
        raise AssertionError(f"{source.name}: backend service is missing")
    block = backend.group("body")
    if "WORKING_DIRECTORY: /workspace" not in block:
        raise AssertionError(f"{source.name}: backend must use /workspace")
    if "/workspace" not in block:
        raise AssertionError(f"{source.name}: backend must mount /workspace")
    if "ATTACHMENTS_CACHE_ROOT: /artifacts" not in block:
        raise AssertionError(f"{source.name}: backend must use /artifacts")
    if "/artifacts" not in block:
        raise AssertionError(f"{source.name}: backend must mount /artifacts")


def assert_persistent_shared_paths(content: str, source: Path) -> None:
    assert_persistent_backend_paths(content, source)
    bot = telegram_bot_block(content, source)
    for label, block in (("bot", bot),):
        if "WORKING_DIRECTORY: /workspace" not in block:
            raise AssertionError(f"{source.name}: {label} must use /workspace")
        if "/workspace" not in block:
            raise AssertionError(f"{source.name}: {label} must mount /workspace")
        if "ATTACHMENTS_CACHE_ROOT: /artifacts" not in block:
            raise AssertionError(f"{source.name}: {label} must use /artifacts")
        if "/artifacts" not in block:
            raise AssertionError(f"{source.name}: {label} must mount /artifacts")


def main() -> None:
    for filename in COMPOSE_FILES:
        source = ROOT / filename
        assert_persistent_backend_paths(source.read_text(), source)
    for filename in BOT_COMPOSE_FILES:
        source = ROOT / filename
        block = telegram_bot_block(source.read_text(), source)
        if "BOT_BACKEND_URL:" not in block:
            raise AssertionError(f"{filename}: bot backend URL is missing")
        if "BOT_BACKEND_API_KEY:" not in block:
            raise AssertionError(
                f"{filename}: bot must receive the backend API key when API auth is enabled"
            )
        assert_persistent_shared_paths(source.read_text(), source)
    print("compose backend auth and persistent path wiring: green")


if __name__ == "__main__":
    main()
