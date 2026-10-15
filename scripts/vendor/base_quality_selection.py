"""Canonical Base selector; product copies are generated, immutable tool artifacts."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

SCHEMA = "base-quality-product/v1"


def inside(root, relative):
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Paths must stay inside the product")
    result = (root / path).resolve()
    if not result.is_relative_to(root):
        raise ValueError("Path leaves the product")
    return result


def digest(value):
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def git(root, *args):
    return (
        subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)
        .decode("utf-8")
        .strip()
    )


def matches(name, patterns):
    return any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)


def load(root):
    manifest = json.loads(
        inside(root, ".quality-gate.json").read_text(encoding="utf-8-sig")
    )
    if manifest.get("schema") != SCHEMA or not isinstance(
        manifest.get("components"), dict
    ):
        raise ValueError("Unsupported quality manifest")
    if not manifest["components"] or not isinstance(manifest.get("gates"), list):
        raise ValueError("Components and gate list are required")
    for component, patterns in manifest["components"].items():
        if (
            not re.fullmatch(r"[a-zA-Z0-9_-]+", component)
            or not isinstance(patterns, list)
            or not patterns
        ):
            raise ValueError("Components require explicit path patterns")
        if not all(
            isinstance(pattern, str) and pattern and ".." not in Path(pattern).parts
            for pattern in patterns
        ):
            raise ValueError("Invalid component path pattern")
    identifiers = set()
    for gate in manifest["gates"]:
        identifier = gate["id"]
        if (
            not re.fullmatch(r"[a-zA-Z0-9:_-]+", identifier)
            or identifier in identifiers
        ):
            raise ValueError("Invalid or duplicate gate id")
        identifiers.add(identifier)
        if not gate.get("profiles") or set(gate["profiles"]) - {
            "fast",
            "integrated",
            "build",
        }:
            raise ValueError("Unsupported gate profile")
        if not isinstance(gate.get("inputs"), list) or not gate["inputs"]:
            raise ValueError("Gate requires explicit inputs")
        if gate["component"] not in manifest["components"]:
            raise ValueError("Unknown gate component")
        if not gate.get("argv") or not all(
            isinstance(arg, str) and "\0" not in arg for arg in gate["argv"]
        ):
            raise ValueError("Command must be an argument array")
        inside(root, gate.get("cwd", "."))
        for name in gate.get("outputs", []):
            inside(root, name)
        if gate.get("cacheable") and (
            gate.get("requires_env") or gate.get("external_state")
        ):
            raise ValueError("External-state gates cannot reuse receipts")
    return manifest


def changed_files(root, base):
    files = subprocess.check_output(
        [
            "git",
            "-C",
            str(root),
            "diff",
            "--name-only",
            "--no-renames",
            "-z",
            base,
            "--",
        ]
    )
    files += subprocess.check_output(
        ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "-z"]
    )
    return sorted({name.decode("utf-8") for name in files.split(b"\0") if name})


def components_for_paths(manifest, files, components=None):
    selected = set(components or [])
    reasons = {}
    if selected - manifest["components"].keys():
        raise ValueError("Unknown selected component")
    for name in files:
        owners = [
            component
            for component, paths in manifest["components"].items()
            if matches(name, paths)
        ]
        if not owners:
            owners = list(manifest["components"])
        for owner in owners:
            selected.add(owner)
            reasons.setdefault(owner, []).append(name)
        for impact in manifest.get("impacts", []):
            if matches(name, impact["paths"]):
                for owner in impact["components"]:
                    if owner not in manifest["components"]:
                        raise ValueError("Unknown impact component")
                    selected.add(owner)
                    reasons.setdefault(owner, []).append(name)
    return sorted(selected), reasons


def plan(root, profile="fast", base="HEAD", changed=None, components=None):
    manifest = load(root)
    files = (
        ([] if components else changed_files(root, base))
        if changed is None
        else changed
    )
    selected, reasons = components_for_paths(manifest, files, components)
    gates = [
        gate
        for gate in manifest["gates"]
        if gate["component"] in selected and profile in gate["profiles"]
    ]
    if files and not components:
        # A test-only change still selects checks, but cannot invalidate application inputs.
        gates = [
            gate
            for gate in gates
            if gate.get("kind") != "artifact"
            or any(
                (
                    matches(name, gate["inputs"])
                    and not matches(name, gate.get("exclude_inputs", []))
                )
                or not any(
                    matches(name, paths) for paths in manifest["components"].values()
                )
                for name in files
            )
        ]
    return {
        "schema": "base-quality-plan/v1",
        "product": manifest["product"],
        "head": git(root, "rev-parse", "HEAD"),
        "profile": profile,
        "components": sorted(selected),
        "changed": files,
        "reasons": reasons,
        "gates": gates,
        "manifest_sha256": digest(manifest),
    }


def ci_components(root, event, base, head, manifest_root=None):
    manifest = load(manifest_root or root)
    if event not in ("pull_request", "push") or (
        event == "push" and (not base or base == "0" * 40)
    ):
        return list(manifest["components"])
    if not re.fullmatch(r"[0-9a-f]{40}", base or "") or not re.fullmatch(
        r"[0-9a-f]{40}", head or ""
    ):
        raise ValueError(
            "Pull request classification requires exact source base/head SHAs"
        )
    # Direct comparison needs only the two exact commits, not an entire history.
    # Diverged target changes may overselect, but can never omit a source change.
    files = git(root, "diff", "--name-only", "--no-renames", base, head).splitlines()
    return components_for_paths(manifest, files)[0]


def classify(event, base, head, *, repo, manifest_root=None):
    components = ci_components(repo, event, base, head, manifest_root)
    return {
        "backend": str(
            any(c in components for c in ("backend", "shared", "bot", "cli"))
        ).lower(),
        "frontend": str("frontend" in components).lower(),
    }


def ci_outputs(event, base, head, *, repo, manifest_root=None):
    values = classify(event, base, head, repo=repo, manifest_root=manifest_root)
    manifest = load(manifest_root or repo)
    if event in ("pull_request", "push") and base and base != "0" * 40:
        files = git(
            repo, "diff", "--name-only", "--no-renames", base, head
        ).splitlines()
        # Browser tests and unknown paths remain conservative. Only declared unit-test
        # inputs can omit the production artifact and its unchanged browser checks.
        unit_only = bool(files) and all(
            matches(name, manifest.get("frontend_unit_inputs", [])) for name in files
        )
        backend_unit_only = bool(files) and all(
            matches(name, manifest.get("backend_unit_inputs", [])) for name in files
        )
    else:
        unit_only = False
        backend_unit_only = False
    values["frontend_build"] = str(
        values["frontend"] == "true" and not unit_only
    ).lower()
    values["backend_build"] = str(
        values["backend"] == "true" and not backend_unit_only
    ).lower()
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    values = ci_outputs(
        os.environ.get("EVENT_NAME", ""),
        os.environ.get("BASE_SHA", ""),
        os.environ.get("HEAD_SHA", ""),
        repo=args.root.resolve(),
    )
    output = "".join(name + "=" + value + "\n" for name, value in values.items())
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
            stream.write(output)
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
