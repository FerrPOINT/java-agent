#!/usr/bin/env python3
"""Inventory controller mappings and static references in Java/Python E2E tests.

The report is intentionally conservative: a static reference does not prove a
route's behavior. It identifies mappings with no obvious test reference so the
next E2E wave can add a real lifecycle assertion rather than claiming coverage.
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent
API_RELATIVE = Path("backend/src/main/java/com/azhukov/agent/api")
TEST_RELATIVES = (
    Path("backend/src/test/java"),
    Path("telegram-bot/src/test/java"),
    Path("cli/src/test/java"),
    Path("e2e"),
)
API_ROOT = REPO / API_RELATIVE
TEST_ROOTS = tuple(REPO / relative for relative in TEST_RELATIVES)
OUTPUT = REPO / "build" / "endpoint-coverage.json"
MAPPING_RE = re.compile(r"@(?:Get|Post|Put|Patch|Delete)Mapping\s*(?:\((?P<args>[^)]*)\))?")
STRING_RE = re.compile(r'["\']([^"\']+)["\']')


def mapping_paths(args: str | None) -> list[str]:
    if not args:
        return [""]
    values = STRING_RE.findall(args)
    return values or [""]


def normalized_path(path: str) -> str:
    if not path:
        return "/"
    return "/" + path.strip("/")


def controller_mappings(source: str) -> list[dict[str, str]]:
    class_prefixes = [""]
    class_match = re.search(r"@RequestMapping\s*\((?P<args>[^)]*)\)\s*(?:public\s+)?class\s", source)
    if class_match:
        class_prefixes = mapping_paths(class_match.group("args"))
    mappings: list[dict[str, str]] = []
    for match in MAPPING_RE.finditer(source):
        annotation = match.group(0).split("(", 1)[0].removeprefix("@").removesuffix("Mapping")
        for prefix in class_prefixes:
            for suffix in mapping_paths(match.group("args")):
                mappings.append({
                    "method": annotation.upper(),
                    "path": normalized_path(prefix.strip("/") + "/" + suffix.strip("/")),
                })
    return mappings


def all_text(roots: tuple[Path, ...]) -> str:
    chunks: list[str] = []
    for root in roots:
        if not root.exists():
            continue
        for file in root.rglob("*"):
            if file.is_file() and file.suffix in {".java", ".py", ".yaml", ".yml"}:
                chunks.append(file.read_text(errors="replace"))
    return "\n".join(chunks)


def build_report(repo: Path = REPO) -> dict[str, Any]:
    api_root = repo / API_RELATIVE
    test_roots = tuple(repo / relative for relative in TEST_RELATIVES)
    test_text = all_text(test_roots)
    endpoints: list[dict[str, str]] = []
    for controller in sorted(api_root.glob("*.java")):
        for mapping in controller_mappings(controller.read_text(errors="replace")):
            mapping["controller"] = controller.relative_to(repo).as_posix()
            mapping["static_reference"] = mapping["path"] != "/" and mapping["path"] in test_text
            endpoints.append(mapping)
    counts = Counter(item["controller"] for item in endpoints)
    return {
        "scope": "Static inventory only; behavioral coverage requires an executed E2E assertion and persisted side-effect.",
        "totals": {
            "mappings": len(endpoints),
            "static_references": sum(1 for item in endpoints if item["static_reference"]),
            "without_static_reference": sum(1 for item in endpoints if not item["static_reference"]),
            "controllers": len(counts),
        },
        "controllers": dict(sorted(counts.items())),
        "mappings": endpoints,
    }


def main() -> int:
    report = build_report()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n")
    totals = report["totals"]
    print(
        "mappings={mappings} static_references={static_references} "
        "without_static_reference={without_static_reference} report={report}".format(
            **totals, report=OUTPUT
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
