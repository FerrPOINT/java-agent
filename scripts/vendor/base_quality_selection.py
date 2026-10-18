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
JAVA_NATIVE_MODULES = {'backend': 'backend', 'shared': 'shared', 'cli': 'cli', 'bot': 'telegram-bot'}


def local_ui_dependency(package):
    """One declared product dependency selects its exact Base source layout."""
    if not isinstance(package, dict) or not isinstance(package.get('dependencies'), dict):
        raise ValueError('Explicit local Base UI dependency metadata is required')
    dependencies = package['dependencies']
    layouts = {'@sdlc/ui': 'frontend', '@pdlc/ui': 'base-modules/ui'}
    selected = [name for name in layouts if name in dependencies]
    if len(selected) != 1:
        raise ValueError('One explicit local Base UI dependency is required')
    name = selected[0]
    path = layouts[name]
    if dependencies[name] != 'file:../../services-base/' + path:
        raise ValueError('Base UI dependency name and source layout disagree')
    return name, path


def validate_native_execution(manifest):
    """Explicit ordinary Java equivalence; unsupported commands stay unsupported."""
    value = manifest.get('native_execution')
    if value is None:
        return
    if (not isinstance(value, dict) or set(value) != {'adapter', 'host_platforms', 'gates'}
            or manifest.get('product') != 'java-agent'
            or value['adapter'] != 'java-compose-v1' or value['host_platforms'] != ['win32']
            or not isinstance(value['gates'], dict) or not value['gates']):
        raise ValueError('Unsupported native execution declaration')
    gates = {gate['id']: gate for gate in manifest['gates']}
    for identifier, request in value['gates'].items():
        if not isinstance(request, dict) or set(request) != {'component', 'role'}:
            raise ValueError('Native gates require explicit component and role')
        component, role = request['component'], request['role']
        if (not isinstance(component, str) or not isinstance(role, str)
                or component not in JAVA_NATIVE_MODULES or role not in ('unit', 'build')
                or (component == 'shared' and role == 'build') or identifier != component + ':' + role):
            raise ValueError('Unsupported native Java component/task')
        gate = gates.get(identifier)
        task = 'test' if role == 'unit' else 'bootJar'
        flags = ['--no-daemon', '--max-workers=2']
        expected = (['bash', 'scripts/quality-gradle.sh', '--backend', ':' + task, *flags]
                    if component == 'backend' else
                    ['bash', 'scripts/quality-gradle.sh', *flags, ':' + JAVA_NATIVE_MODULES[component] + ':' + task])
        if (gate is None or gate.get('component') != component or gate['argv'] != expected
                or set(gate['profiles']) - {'fast', 'unit', 'build'}
                or gate.get('platforms') != ['linux'] or gate.get('cwd', '.') != '.'
                or gate.get('execution_slot') not in (None, 'host') or type(gate.get('cacheable')) is not bool
                or gate.get('source_date_epoch') != 'git-head'
                or gate.get('environment_inputs') != ['SOURCE_DATE_EPOCH']
                or any(gate.get(name) for name in ('requires_env', 'external_state', 'prepare_frontend', 'sdk_revision'))
                or gate.get('artifact_outputs') or gate.get('artifact_input_contract')
                or gate.get('test_tier') != ('unit' if role == 'unit' else 'artifact')
                or (role == 'build' and (gate.get('kind') != 'artifact' or len(gate.get('outputs', [])) != 1))
                or (role == 'unit' and gate.get('kind') not in (None, 'test'))):
            raise ValueError('Native Java declaration differs from its ordinary gate contract: ' + identifier)
        tools = gate.get('tools', [])
        context = ['{python}', 'scripts/gradle-context.py'] + (['--backend'] if component == 'backend' else [])
        if (len(tools) != 1 or tools[0].get('argv') != ['java', '-version']
                or not isinstance(tools[0].get('pattern'), str) or not tools[0]['pattern']
                or tools[0].get('context_argv') != context or tools[0].get('cwd', '.') != '.'
                or not tools[0].get('context_pattern') or tools[0].get('recheck') is not True):
            raise ValueError('Native Java requires the selected read-only JDK context: ' + identifier)


def native_execution_plan(manifest, gates, execution_platform, docker_context=None):
    """Pure dispatch plan. No tool probes, filesystem preparation or Docker calls."""
    value = manifest.get('native_execution')
    if value is None:
        return None
    validate_native_execution(manifest)
    if execution_platform not in value['host_platforms']:
        return None
    requests = {gate['id']: value['gates'][gate['id']] for gate in gates if gate['id'] in value['gates']}
    if not requests:
        return None
    if docker_context is not None and (not isinstance(docker_context, str)
                                      or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', docker_context)):
        raise ValueError('Expected an explicit Docker context name for native execution')
    components = [name for name in JAVA_NATIVE_MODULES if any(row['component'] == name for row in requests.values())]
    artifacts = [name for name in components if any(row == {'component': name, 'role': 'build'} for row in requests.values())]
    return {'adapter': value['adapter'], 'host_platform': execution_platform,
            'docker_context': docker_context, 'profile': 'build' if artifacts else 'unit',
            'components': components, 'artifact_components': artifacts,
            'gates': requests}


def validate_gate_slot(gate):
    mode = gate.get("execution_slot")
    if mode not in (None, "host", "none", "command"):
        raise ValueError("Unsupported execution slot policy")
    if mode == "none" and (gate.get("test_tier") not in ("unit", "static")
                           or gate.get("prepare_frontend") or gate.get("kind") == "artifact"):
        raise ValueError("Only independent unit/static commands may omit the execution slot")
    if mode == "command" and (gate.get("prepare_frontend") or gate.get("artifact_outputs")):
        raise ValueError("Command-owned gates own their preparation and artifact namespace")


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
    path = inside(root, ".quality-gate.json")
    if not path.is_file():
        raise ValueError(
            "Missing product-owned .quality-gate.json; adopt the reviewed test-tier "
            "manifest in this source checkout before running quality profiles. "
            "No legacy full suite is selected automatically."
        )
    manifest = json.loads(path.read_text(encoding="utf-8-sig"))
    if manifest.get("schema") != SCHEMA or not isinstance(
        manifest.get("components"), dict
    ):
        raise ValueError("Unsupported quality manifest")
    if not manifest["components"] or not isinstance(manifest.get("gates"), list):
        raise ValueError("Components and gate list are required")
    if manifest.get("test_policy") and (root / ".github/workflows/ci.yml").is_file():
        # A literal canonical header is a lint contract, not a general YAML parser.
        # Fail closed on any extra event; CRLF is normalized by read_text.
        release = inside(root, ".github/workflows/release.yml")
        content = release.read_text(encoding="utf-8")
        marker = "\non:\n"
        header = content.split(marker, 1)[1].split("\n\n", 1)[0] if marker in content else ""
        if header.strip() != "workflow_dispatch:":
            raise ValueError("Release workflow must use only the canonical manual workflow_dispatch header")
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
        validate_gate_slot(gate)
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
            "unit",
            "integration",
            "slow",
            "release",
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
        for tool in gate.get("tools", []):
            if "context_argv" in tool:
                if (not isinstance(tool["context_argv"], list) or not tool["context_argv"]
                        or any(not isinstance(value, str) or not value or "\0" in value for value in tool["context_argv"])):
                    raise ValueError("Tool context command must be an argument array")
            if "context_pattern" in tool:
                if not isinstance(tool["context_pattern"], str) or not tool["context_pattern"]:
                    raise ValueError("Tool context pattern must be nonempty")
                try:
                    re.compile(tool["context_pattern"])
                except re.error as error:
                    raise ValueError("Invalid tool context pattern") from error
            if "cwd" in tool:
                if not isinstance(tool["cwd"], str) or not tool["cwd"]:
                    raise ValueError("Tool cwd must be a product-relative path")
                inside(root, tool["cwd"])
            if "reuse_pattern" in tool:
                if not isinstance(tool["reuse_pattern"], str) or not tool["reuse_pattern"]:
                    raise ValueError("Tool reuse pattern must be nonempty")
                try:
                    re.compile(tool["reuse_pattern"])
                except re.error as error:
                    raise ValueError("Invalid tool reuse pattern") from error
            if "recheck" in tool and type(tool["recheck"]) is not bool:
                raise ValueError("Tool recheck must be boolean")
        for name in gate.get("outputs", []):
            inside(root, name)
        if "artifact_outputs" in gate:
            if not isinstance(gate["artifact_outputs"], list) or not gate["artifact_outputs"]:
                raise ValueError("Managed artifact outputs require a nonempty path list")
            if gate.get("kind") != "artifact" or gate.get("outputs"):
                raise ValueError("Managed artifact outputs require an artifact gate")
            for name in gate["artifact_outputs"]:
                if not isinstance(name, str) or not name:
                    raise ValueError("Managed artifact output must be a relative path")
                inside(root, name)
        if gate.get("artifact_toolchain") not in (None, "pinned-image"):
            raise ValueError("Unsupported artifact toolchain qualification")
        if "source_date_epoch" in gate and (
            gate["source_date_epoch"] != "git-head"
            or not isinstance(gate.get("environment_inputs"), list)
            or "SOURCE_DATE_EPOCH" not in gate["environment_inputs"]
        ):
            raise ValueError("source_date_epoch requires git-head and an explicit SOURCE_DATE_EPOCH input")
        if "artifact_input_contract" in gate and (
            gate["artifact_input_contract"] != "git-source-v1"
            or gate.get("kind") != "artifact"
        ):
            raise ValueError("Unsupported artifact input contract")
        if gate.get("artifact_input_contract") and gate.get("prepare_frontend") and (
            set(manifest.get("frontend_context_inputs", [])) - set(gate["inputs"])
        ):
            raise ValueError("Frontend artifact input contract omits additional context inputs")
        if gate.get("cacheable") and (
            gate.get("requires_env") or gate.get("external_state")
        ):
            raise ValueError("External-state gates cannot reuse receipts")
        if manifest.get("test_policy"):
            tier = gate.get("test_tier")
            if tier not in ("unit", "integration", "slow", "static", "artifact"):
                raise ValueError("Every gate requires an explicit test tier")
            if (set(gate["profiles"]) & {"fast", "unit", "build"}
                    and (tier in ("integration", "slow") or gate.get("external_state"))):
                raise ValueError("Automatic build profiles cannot run boundary or slow tests")
    gradle_unit_groups(manifest["gates"])
    covered_unit_owners(manifest["gates"])
    validate_native_execution(manifest)
    return manifest


def gradle_unit_groups(gates):
    """Join selected trailing Gradle tasks, preserving the complete unit contract."""
    identifiers = {gate["id"] for gate in gates}
    groups = {}
    for gate in gates:
        if "gradle_unit_group" not in gate:
            continue
        group = gate["gradle_unit_group"]
        if (not isinstance(group, str) or not re.fullmatch(r"[a-zA-Z0-9:_-]+", group)
                or group in identifiers
                or gate.get("test_tier") != "unit" or gate.get("kind") == "artifact"
                or gate.get("cacheable") or gate.get("external_state") or gate.get("requires_env")
                or gate.get("outputs") or gate.get("artifact_outputs") or gate.get("prepare_frontend")
                or gate.get("execution_slot") == "command"
                or len(gate["argv"]) < 2
                or not re.fullmatch(r"(?::[a-zA-Z0-9_-]+)*:test", gate["argv"][-1])
                or any(arg.startswith(":") for arg in gate["argv"][:-1])):
            raise ValueError("Gradle unit group requires an uncached isolated unit and one trailing :test task")
        groups.setdefault(group, []).append(gate)
    merged = {}
    for group, members in groups.items():
        first = members[0]
        contract = {key: value for key, value in first.items() if key not in ("id", "component", "argv")}
        if any({key: value for key, value in member.items() if key not in ("id", "component", "argv")} != contract
               or member["argv"][:-1] != first["argv"][:-1] for member in members[1:]):
            raise ValueError("Gradle unit group has incompatible commands or execution contracts: " + group)
        merged[group] = {
            **first, "id": group if len(members) > 1 else first["id"],
            "argv": first["argv"][:-1] + list(dict.fromkeys(member["argv"][-1] for member in members)),
            "member_gates": [member["id"] for member in members],
        }
    result = []
    for gate in gates:
        group = gate.get("gradle_unit_group")
        if group:
            if group in merged:
                result.append(merged.pop(group))
        else:
            result.append(gate)
    return result


def covered_unit_owners(gates):
    """A product may certify units only through an explicitly compatible producer."""
    index = {gate["id"]: gate for gate in gates}
    owners = {}
    for producer in gates:
        if "covers_units" not in producer:
            continue
        names = producer["covers_units"]
        if (not isinstance(names, list) or not names or not all(isinstance(name, str) for name in names)
                or len(set(names)) != len(names) or producer.get("kind") != "artifact"
                or producer.get("test_tier") != "artifact"
                or not (producer.get("outputs") or producer.get("artifact_outputs"))):
            raise ValueError("Invalid unit coverage producer or members")
        for name in names:
            unit = index.get(name)
            if (unit is None or name in owners or unit.get("test_tier") != "unit"
                    or unit.get("kind") not in (None, "test") or not unit.get("outputs")
                    or unit["component"] != producer["component"]
                    or set(producer["profiles"]) - set(unit["profiles"])
                    or unit.get("gradle_unit_group")
                    or any(gate.get(field) for gate in (producer, unit)
                           for field in ("external_state", "requires_env", "prepare_frontend", "sdk_revision"))
                    or unit.get("execution_slot") == "command" or producer.get("execution_slot") == "command"
                    or any(unit.get(field, default) != producer.get(field, default) for field, default in
                           (("cwd", "."), ("platforms", []), ("tools", []),
                            ("environment_inputs", []), ("source_date_epoch", None),
                            ("execution_slot", None), ("timeout_seconds", 1800)))):
                raise ValueError("Incompatible unit coverage contract: " + name)
            owners[name] = producer["id"]
    return owners


def order_unit_coverage(gates):
    owners = covered_unit_owners(gates)
    index = {gate["id"]: gate for gate in gates}
    ordered = []
    visited = set()

    def visit(gate):
        if gate["id"] in visited:
            return
        if gate["id"] in owners:
            visit(index[owners[gate["id"]]])
        visited.add(gate["id"])
        ordered.append(gate)

    for gate in gates:
        visit(gate)
    return ordered


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


def plan(
    root, profile="fast", base="HEAD", changed=None, components=None, manifest=None,
    *, only_components=None, execution_platform=None, native_docker_context=None,
):
    manifest = load(root) if manifest is None else manifest
    if only_components and (components or set(only_components) - manifest["components"].keys()):
        raise ValueError("Affected component filter requires known components and no explicit selection")
    files = (
        ([] if components else changed_files(root, base))
        if changed is None
        else changed
    )
    selected, reasons = components_for_paths(manifest, files, components)
    if only_components:
        selected = [component for component in selected if component in only_components]
        reasons = {component: paths for component, paths in reasons.items() if component in selected}
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
    native = native_execution_plan(manifest, gates, execution_platform, native_docker_context)
    if native:
        # Keep the original native gate identities for per-gate JUnit/JAR proof.
        # The native owner, rather than the host Gradle command, groups wrappers.
        ordinary = gradle_unit_groups([gate for gate in gates if gate['id'] not in native['gates']])
        replacements = {name: gate for gate in ordinary for name in gate.get('member_gates', [gate['id']])}
        projected, seen = [], set()
        for gate in gates:
            replacement = gate if gate['id'] in native['gates'] else replacements[gate['id']]
            if replacement['id'] not in seen:
                projected.append(replacement)
                seen.add(replacement['id'])
        ordered = order_unit_coverage(projected)
    else:
        ordered = order_unit_coverage(gradle_unit_groups(gates))
    result = {
        "schema": "base-quality-plan/v1",
        "product": manifest["product"],
        "head": git(root, "rev-parse", "HEAD"),
        "profile": profile,
        "components": sorted(selected),
        "changed": files,
        "reasons": reasons,
        "gates": ordered,
        "manifest_sha256": digest(manifest),
        "additional_acceptance": manifest.get("additional_acceptance"),
    }
    if native:
        result['native_execution'] = native
    return result


def plan_summary(candidate):
    """Bound human-facing output without changing the executable plan."""
    files = candidate["changed"]
    result = {
        "schema": "base-quality-plan-summary/v1",
        **{name: candidate[name] for name in ("product", "head", "profile", "components", "manifest_sha256")},
        "changed_count": len(files),
        "changed_sample": files[:20],
        "changed_omitted": max(0, len(files) - 20),
        "reasons": {
            component: {"matched_files": len(paths), "sample": paths[:3]}
            for component, paths in candidate["reasons"].items()
        },
        "gates": [
            {name: gate[name] for name in ("id", "component", "test_tier", "member_gates") if name in gate}
            for gate in candidate["gates"]
        ],
        "additional_acceptance": candidate.get("additional_acceptance"),
        "full_plan": "Omit --summary; root launcher: -PlanOnly -DetailedPlan",
    }
    if candidate.get('native_execution'):
        result['native_execution'] = candidate['native_execution']
    return result


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
        # Decide each producer from its own affected paths. An unrelated backend
        # change must not turn a frontend test-only change into an asset rebuild.
        # Unknown paths and explicit shared impacts still expand through the map.
        _, reasons = components_for_paths(manifest, files)
        frontend_files = set(reasons.get("frontend", []))
        backend_files = {name for component in ("backend", "shared", "bot", "cli")
                         for name in reasons.get(component, [])}
        unit_only = bool(frontend_files) and all(
            matches(name, manifest.get("frontend_unit_inputs", [])) for name in frontend_files
        )
        backend_unit_only = bool(backend_files) and all(
            matches(name, manifest.get("backend_unit_inputs", [])) for name in backend_files
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
