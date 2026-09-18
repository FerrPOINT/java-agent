#!/usr/bin/env python3
"""Validate Java Agent README invariants without network dependencies."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
IMAGE_RE = re.compile(r"!\[[^]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
HTML_IMAGE_RE = re.compile(r"<img\b[^>]*\bsrc=[\"']([^\"']+)[\"']", re.IGNORECASE)
HEADER_LINK_RE = re.compile(r"(?<!!)\[[^]]+\]\(#([^)\s]+)\)")
ANCHOR_RE = re.compile(r"<a\b[^>]*\bname=[\"']([^\"']+)[\"']", re.IGNORECASE)
PLACEHOLDER_RE = re.compile(r"\{\{[^}]+\}\}")
LOCAL_PATH_RE = re.compile(r"(?<![\w.-])/(?:opt|root|home|tmp|var)/")
CI_BADGE_RE = re.compile(r"actions/workflows/([^/?#]+\.ya?ml)(?:/badge\.svg|\?[^\s)]*)", re.IGNORECASE)


def local_target(raw: str) -> str | None:
    parsed = urlparse(raw)
    if parsed.scheme or raw.startswith("#") or raw.startswith("//"):
        return None
    return unquote(parsed.path)


def validate(root: Path) -> list[str]:
    readme = root / "README.md"
    text = readme.read_text(encoding="utf-8")
    findings: list[str] = []
    anchors = set(ANCHOR_RE.findall(text))

    for anchor in sorted(set(HEADER_LINK_RE.findall(text))):
        if anchor not in anchors:
            findings.append(f"RMD002: README.md: missing anchor #{anchor}")

    image_sources = IMAGE_RE.findall(text) + HTML_IMAGE_RE.findall(text)
    for source in sorted(set(image_sources)):
        target = local_target(source)
        if target and not (root / target).is_file():
            findings.append(f"RMD003: README.md: missing local asset {target}")

    if PLACEHOLDER_RE.search(text):
        findings.append("RMD005: README.md: unresolved placeholder")
    if LOCAL_PATH_RE.search(text):
        findings.append("RMD006: README.md: local filesystem path leaked")

    for workflow in sorted(set(CI_BADGE_RE.findall(text))):
        if not (root / ".github" / "workflows" / workflow).is_file():
            findings.append(f"RMD007: README.md: badge references missing workflow {workflow}")

    return findings


def main() -> int:
    findings = validate(ROOT)
    if findings:
        print("\n".join(findings), file=sys.stderr)
        return 1
    print("README validation: green")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
