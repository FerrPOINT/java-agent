#!/usr/bin/env python3
"""Verify that every shipped YAML setting is listed in the defaults reference."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "docs" / "default-settings.md"
CONFIG_FILES = (
    ROOT / "backend" / "src" / "main" / "resources" / "application.yml",
    ROOT / "telegram-bot" / "src" / "main" / "resources" / "application.yml",
    ROOT / "cli" / "src" / "main" / "resources" / "application.yml",
)
KEY = re.compile(r"^(?P<indent>\s*)(?P<key>[A-Za-z0-9][A-Za-z0-9_-]*):(?P<value>.*)$")
DOCUMENTED_PROPERTY = re.compile(r"`([A-Za-z0-9][A-Za-z0-9._\-\[\]]+)`")
FIELD = re.compile(
    r"^\s*private\s+(?!static\b)(?:final\s+)?[\w.<>,?\[\] ]+\s+(\w+)\s*=\s*(.+?);\s*(?://.*)?$"
)
CLASS = re.compile(r"\b(?:public\s+)?(?:static\s+)?class\s+(\w+)")
VALUE = re.compile(r'@Value\("\$\{([a-z0-9.-]+):[^}]*}\"\)')
JAVA_PROPERTY_FILES = (
    (ROOT / "backend" / "src" / "main" / "java" / "com" / "azhukov" / "agent" / "config" / "AgentProperties.java", "AgentProperties"),
    (ROOT / "telegram-bot" / "src" / "main" / "java" / "com" / "azhukov" / "agent" / "bot" / "config" / "BotProperties.java", "BotProperties"),
    (ROOT / "cli" / "src" / "main" / "java" / "com" / "azhukov" / "agent" / "cli" / "CliProperties.java", "CliProperties"),
)
VALUE_SOURCE_DIRS = (
    ROOT / "backend" / "src" / "main" / "java",
    ROOT / "telegram-bot" / "src" / "main" / "java",
    ROOT / "cli" / "src" / "main" / "java",
)


def yaml_leaf_paths(path: Path) -> set[str]:
    """Extract scalar YAML paths without a YAML parser dependency in CI."""
    result: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw_line in path.read_text().splitlines():
        if not raw_line or raw_line.lstrip().startswith(("#", "---", "...", "- ")):
            continue
        match = KEY.match(raw_line)
        if match is None:
            continue
        indent = len(match.group("indent"))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = match.group("key")
        value = match.group("value").strip()
        if value:
            leaf_path = ".".join([part for _, part in stack] + [key])
            result.add(leaf_path)
            # List items share the parent setting path and do not create new keys.
            if value.startswith("["):
                stack.append((indent, key))
        else:
            stack.append((indent, key))
    return result


def java_fallback_fields(path: Path, root_class: str) -> set[str]:
    """Extract literal property-class defaults with nested class-qualified paths."""
    result: set[str] = set()
    stack: list[tuple[int, str]] = []
    depth = 0
    active = False
    for line in path.read_text().splitlines():
        class_match = CLASS.search(line)
        opens, closes = line.count("{"), line.count("}")
        if class_match:
            name = class_match.group(1)
            if name == root_class:
                active = True
                stack = [(depth + opens - closes, name)]
            elif active:
                stack = [item for item in stack if item[0] <= depth]
                stack.append((depth + opens - closes, name))
        if active:
            field_match = FIELD.match(line)
            if field_match and stack:
                field, value = field_match.groups()
                if not value.strip().startswith("new ") or "List.of" in value or "Map.of" in value:
                    result.add(".".join(name for _, name in stack) + "." + field)
        depth += opens - closes
        if active:
            stack = [item for item in stack if item[0] <= depth]
    return result


def value_fallback_properties() -> set[str]:
    """Find direct Spring defaults that do not bind through a property class."""
    result: set[str] = set()
    for directory in VALUE_SOURCE_DIRS:
        for path in directory.rglob("*.java"):
            result.update(VALUE.findall(path.read_text()))
    return result


def expected_settings() -> set[str]:
    yaml_paths = set().union(*(yaml_leaf_paths(path) for path in CONFIG_FILES))
    java_paths = set().union(
        *(java_fallback_fields(path, root_class) for path, root_class in JAVA_PROPERTY_FILES)
    )
    return yaml_paths | java_paths | value_fallback_properties()


def documented_properties() -> set[str]:
    return set(DOCUMENTED_PROPERTY.findall(REFERENCE.read_text()))


def verify() -> list[str]:
    missing = sorted(expected_settings() - documented_properties())
    if missing:
        return ["Missing YAML defaults in docs/default-settings.md:", *(f"- {key}" for key in missing)]
    return []


def main() -> int:
    errors = verify()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    count = len(expected_settings())
    print(f"Default settings reference covers {count} shipped defaults.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
