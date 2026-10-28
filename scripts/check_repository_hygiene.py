#!/usr/bin/env python3
"""Reject tracked disposable artifacts; canonical source belongs to services-base.

Products carry byte-identical standalone copies, independent of SDK/UI pins.
Only Git path metadata is read. No file content, history or working file is changed.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import PurePosixPath

DISPOSABLE_DIRECTORIES = frozenset(
    {
        "__pycache__",
        "node_modules",
        ".pnpm-store",
        ".venv",
        ".local",
        ".worktree",
        ".worktrees",
        "worktree",
        "worktrees",
    }
)
RUST_OUTPUT = re.compile(r"target(?:-cache[0-9]*|-stage|-stable)?\Z")


def violation(path: str) -> str | None:
    """Match directory boundaries, preserving intentional evidence and assets."""
    parts = PurePosixPath(path).parts
    for directory in parts[:-1]:
        if directory in DISPOSABLE_DIRECTORIES:
            return "disposable_directory"
        if RUST_OUTPUT.fullmatch(directory):
            return "rust_build_output"
    if parts and parts[-1].endswith((".pyc", ".pyo")):
        return "python_bytecode"
    if parts and parts[-1].endswith(".tsbuildinfo"):
        return "typescript_build_info"
    return None


def git(*args: str) -> bytes:
    result = subprocess.run(["git", *args], capture_output=True, check=False)
    if result.returncode:
        # Do not relay Git stderr: local remote/configuration can contain secrets.
        raise RuntimeError("Cannot read the selected Git snapshot")
    return result.stdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    snapshot = parser.add_mutually_exclusive_group()
    snapshot.add_argument("--index", action="store_true", help="check staged paths (default)")
    snapshot.add_argument("--ref", metavar="COMMIT", help="check an existing commit")
    args = parser.parse_args(argv)
    try:
        git("rev-parse", "--show-toplevel")
        if args.ref is not None:
            commit = git("rev-parse", "--verify", "--end-of-options", args.ref + "^{commit}").strip().decode("ascii")
            paths = git("ls-tree", "-rz", "--name-only", "--full-tree", commit)
        else:
            if git("ls-files", "--unmerged", "-z", "--", ":/"):
                raise RuntimeError("Resolve the unmerged Git index before checking")
            paths = git("ls-files", "--cached", "--full-name", "-z", "--", ":/")
        findings = []
        for raw in sorted(set(paths.split(b"\0")) - {b""}):
            path = os.fsdecode(raw)
            reason = violation(path)
            if reason:
                findings.append({"path": path, "reason": reason})
        # JSON escapes filenames safely, including newlines and terminal controls.
        print(json.dumps({"ok": not findings, "findings": findings}, ensure_ascii=True))
        return 1 if findings else 0
    except (OSError, RuntimeError, UnicodeError):
        print("Repository hygiene check failed: cannot read a complete Git snapshot", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
