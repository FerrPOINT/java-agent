#!/usr/bin/env python3
"""Documentation & test-standards ratchet for java-agent.

Checks (per backend/telegram-bot/cli main sources):
  DOC001 class >=50 lines without class-level javadoc
  DOC002 banned filler phrases in javadoc/comments ("responsible for", ...)
  DOC003 @AgentTool with a too-short description (<40 chars) or banned filler
Exit 1 when violation counts exceed the ratchet baseline
(docs/standards/coverage-baseline.json -> "doc_violations" per module).
--update-baseline rewrites the baseline to the current counts (manual, audited).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("backend", "telegram-bot", "cli")
BASELINE = ROOT / "docs" / "standards" / "coverage-baseline.json"

CLASS_DECL_RE = re.compile(
    r"(?P<javadoc>(?:/\*\*.*?\*/\s*))?(?P<annos>(?:@[\w.]+(?:\([^)]*\))?\s*)*)"
    r"(?P<decl>(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*"
    r"(?:class|interface|record|enum)\s+\w+)",
    re.S,
)

BANNED_PHRASES = [
    r"responsible\s+for",
    r"provides\s+functionality",
    r"utility\s+class",
    r"helper\s+class",
    r"management\s+of",
    r"[Tt]his\s+method\s+is\s+used\s+to",
    r"This\s+class\s+is\s+used\s+to",
]

AGENT_TOOL_RE = re.compile(
    r"@AgentTool\s*\((.*?)\)\s*\n\s*(?:public\s+)?(?:final\s+|abstract\s+)*class",
    re.S,
)
DESC_RE = re.compile(r'description\s*=\s*"((?:[^"\\]|\\.)*)"(\s*\+\s*"((?:[^"\\]|\\.)*)")*', re.S)


def banned_hits(text: str) -> list[str]:
    hits = []
    for phrase in BANNED_PHRASES:
        if re.search(phrase, text, re.I):
            hits.append(phrase)
    return hits


def check_module(module: str) -> dict[str, int]:
    src = ROOT / module / "src" / "main" / "java"
    doc001: list[str] = []
    doc002: list[str] = []
    doc003: list[str] = []
    if not src.is_dir():
        return {"DOC001": 0, "DOC002": 0, "DOC003": 0}
    for path in sorted(src.rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = str(path.relative_to(ROOT))
        lines = text.count("\n") + 1
        for m in CLASS_DECL_RE.finditer(text):
            javadoc = m.group("javadoc") or ""
            if lines >= 50 and "/**" not in javadoc:
                doc001.append(f"{rel}:{m.group('decl').split()[-1]}")
        # banned phrases only inside comments
        comments = re.findall(r"/\*\*?(?:.*?)\*/", text, re.S) + re.findall(r"//[^\n]*", text)
        for c in comments:
            for hit in banned_hits(c):
                doc002.append(f"{rel}: /{hit}/")
        for tm in AGENT_TOOL_RE.finditer(text):
            desc_m = DESC_RE.search(tm.group(1))
            if not desc_m:
                doc003.append(f"{rel}: missing description")
                continue
            desc = "".join(g for g in desc_m.groups() if g) or ""
            if len(desc) < 40 or banned_hits(desc):
                doc003.append(f"{rel}: weak description ({len(desc)} chars)")
    counts = {"DOC001": len(doc001), "DOC002": len(doc002), "DOC003": len(doc003)}
    for code, items in (("DOC001", doc001), ("DOC002", doc002), ("DOC003", doc003)):
        for item in items[:5]:
            print(f"{code} {module}: {item}", file=sys.stderr)
        if len(items) > 5:
            print(f"{code} {module}: ... and {len(items) - 5} more", file=sys.stderr)
    return counts


def line_coverage(module: str) -> float | None:
    """LINE coverage % from the module's JaCoCo XML, or None when absent."""
    xml = ROOT / module / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    if not xml.is_file():
        return None
    import xml.etree.ElementTree as ET

    # The XML is a locally generated JaCoCo artifact, not untrusted input; stdlib parse is safe.
    root = ET.parse(xml).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered") or 0)
            missed = int(counter.get("missed") or 0)
            if covered + missed == 0:
                return None
            return round(100 * covered / (covered + missed), 2)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update-baseline", action="store_true")
    ap.add_argument(
        "--check-coverage",
        action="store_true",
        help="also enforce LINE coverage >= baseline (requires jacocoTestReport to have run)",
    )
    args = ap.parse_args()

    current: dict[str, dict[str, int]] = {}
    for module in MODULES:
        current[module] = check_module(module)

    if not BASELINE.exists():
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps({"doc_violations": current}, indent=2) + "\n")
        print(f"baseline initialized at {BASELINE.relative_to(ROOT)}")
        return 0

    baseline = json.loads(BASELINE.read_text()).get("doc_violations", {})
    failures = []
    print("module         DOC001 DOC002 DOC003   (baseline -> current)")
    for module in MODULES:
        b = baseline.get(module, {"DOC001": 0, "DOC002": 0, "DOC003": 0})
        c = current[module]
        mark = ""
        for code in c:
            if c[code] > b.get(code, 0):
                failures.append(f"{module} {code}: {b.get(code, 0)} -> {c[code]} (ratchet breach)")
                mark = "  << RATCHET BREACH"
        print(
            f"{module:<14} {b.get('DOC001', 0):>4}{b.get('DOC002', 0):>+6} {b.get('DOC003', 0):>6}"
            f"   ({b.get('DOC001', 0)}/{b.get('DOC002', 0)}/{b.get('DOC003', 0)} ->"
            f" {c['DOC001']}/{c['DOC002']}/{c['DOC003']}){mark}"
        )

    if args.update_baseline:
        payload: dict[str, object] = {"doc_violations": current}
        cov = {m: v for m in MODULES if (v := line_coverage(m)) is not None}
        if cov:
            payload["line_coverage"] = cov
        BASELINE.write_text(json.dumps(payload, indent=2) + "\n")
        print(f"baseline updated -> {BASELINE.relative_to(ROOT)}")
        return 0

    if args.check_coverage:
        cov_baseline = json.loads(BASELINE.read_text()).get("line_coverage", {})
        for module in MODULES:
            floor = cov_baseline.get(module)
            actual = line_coverage(module)
            if floor is None or actual is None:
                print(f"{module}: coverage not enforced (baseline={floor} actual={actual})")
                continue
            status = "OK" if actual >= floor else "REGRESSION"
            print(f"{module}: LINE {actual}% (floor {floor}%) {status}")
            if actual < floor:
                failures.append(f"{module} LINE coverage {actual}% < baseline {floor}%")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        print("Fix the violations or consciously update the baseline WITH new tests.", file=sys.stderr)
        return 1
    print("OK: no ratchet breaches")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
