#!/usr/bin/env python3
"""Verify bot deployments pass backend auth through every Compose profile."""

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BOT_COMPOSE_FILES = (
    "docker-compose.dev.yml",
    "docker-compose.local.yml",
    "docker-compose.prod.yml",
)


def telegram_bot_block(content: str, source: Path) -> str:
    match = re.search(r"(?ms)^  (?:telegram-bot|bot):\n(?P<body>.*?)(?=^  \S|\Z)", content)
    if match:
        return match.group("body")
    raise AssertionError(f"{source.name}: bot service is missing")


def main() -> None:
    for filename in BOT_COMPOSE_FILES:
        source = ROOT / filename
        block = telegram_bot_block(source.read_text(), source)
        if "BOT_BACKEND_URL:" not in block:
            raise AssertionError(f"{filename}: bot backend URL is missing")
        if "BOT_BACKEND_API_KEY:" not in block:
            raise AssertionError(
                f"{filename}: bot must receive the backend API key when API auth is enabled"
            )
    print("compose backend auth wiring: green")


if __name__ == "__main__":
    main()
