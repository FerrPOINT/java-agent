#!/usr/bin/env python3
"""parity-dashboard.py — auto-generate the Hermes-parity status snapshot.

One command refreshes the numbers the java-agent-porting skill references:
test counts per module, deployed version, migration count, latest commits,
CI status. Output: docs/parity-dashboard.md (or stdout with --stdout).
"""
from __future__ import annotations

import datetime
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULES = ("backend", "telegram-bot", "cli")
RUNTIME_VERSION_FILE = Path("/opt/java-agent/VERSION")
MIGRATIONS_DIR = REPO / "backend/src/main/resources/db/migration"


def sh(argv: list[str]) -> str:
    # argv lists are hard-coded literals defined in this file; list-form exec,
    # no shell interpolation — no injection surface by construction.
    return subprocess.run(argv, capture_output=True, text=True, cwd=REPO).stdout.strip()


def count_tests(module: str) -> int:
    total = 0
    for f in (REPO / module / "build/test-results").rglob("*.xml"):
        try:
            head = f.read_text(errors="ignore")[:2000]
            m = re.search(r'tests="(\d+)"', head)
            if m:
                total += int(m.group(1))
        except OSError:
            pass
    return total


def main() -> None:
    today = datetime.date.today().isoformat()
    lines = [f"# Parity Dashboard (auto-generated {today})", ""]

    # Deployed version
    ver = RUNTIME_VERSION_FILE.read_text().strip() if RUNTIME_VERSION_FILE.exists() else "unknown"
    services = sh(["systemctl", "is-active", "java-agent-backend", "java-agent-bot"]).replace("\n", "/")
    lines += [f"- **Deployed**: {ver} ({services or 'unknown'})",
              f"- **Migrations**: {len(list(MIGRATIONS_DIR.glob('V*.sql')))}"]

    # Tests
    counts = {m: count_tests(m) for m in MODULES}
    lines += [f"- **Tests (last run)**: {sum(counts.values())} "
              + " / ".join(f"{m} {n}" for m, n in counts.items())]

    # Last 10 commits
    lines += ["", "## Recent commits", ""]
    log = sh(["git", "log", "--oneline", "-10"])
    for row in log.splitlines():
        lines.append(f"- {row}")

    # CI
    ci = sh(["gh", "run", "list", "--limit", "1", "--json", "conclusion",
             "--jq", ".[0].conclusion"])
    lines += ["", f"**CI (last)**: {ci or 'n/a'}", ""]

    out = "\n".join(lines)
    if "--stdout" in sys.argv:
        print(out)
    else:
        target = REPO / "docs/parity-dashboard.md"
        target.write_text(out)
        print(f"written: {target}")


if __name__ == "__main__":
    main()
