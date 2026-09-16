#!/usr/bin/env python3
"""Run local release gates and write a machine-readable evidence report.

Default gates are deterministic and local: JVM tests, JaCoCo reports, boot JARs,
and static endpoint-to-test-reference inventory. HTTP/CLI/Docker E2E require an
explicit flag because they need a running backend or Docker.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET  # Parses locally generated Gradle XML reports only.
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent
DEFAULT_REPORT = REPO / "build" / "release-verification.json"
MODULES = ("backend", "telegram-bot", "cli")


@dataclass
class GateResult:
    name: str
    status: str
    command: list[str] | None
    duration_seconds: float
    detail: str


def run_command(name: str, command: list[str], timeout: int) -> GateResult:
    started = dt.datetime.now(dt.timezone.utc)
    environment = dict(os.environ)
    # Hermes can export a root user-bus path even when that runtime directory is
    # absent. Gradle's forked workers then fail before running any test.
    if not Path("/run/user/0/bus").exists():
        environment.pop("DBUS_SESSION_BUS_ADDRESS", None)
        environment.pop("XDG_RUNTIME_DIR", None)
    try:
        completed = subprocess.run(
            command,
            cwd=REPO,
            env=environment,
            text=True,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        duration = (dt.datetime.now(dt.timezone.utc) - started).total_seconds()
        detail = (completed.stdout + completed.stderr).strip()
        if len(detail) > 12_000:
            detail = detail[-12_000:]
        return GateResult(
            name=name,
            status="PASS" if completed.returncode == 0 else "FAIL",
            command=command,
            duration_seconds=round(duration, 3),
            detail=detail,
        )
    except subprocess.TimeoutExpired as error:
        duration = (dt.datetime.now(dt.timezone.utc) - started).total_seconds()
        return GateResult(
            name=name,
            status="FAIL",
            command=command,
            duration_seconds=round(duration, 3),
            detail=f"timed out after {timeout}s: {error}",
        )


def not_run(name: str, reason: str) -> GateResult:
    return GateResult(name, "NOT_RUN", None, 0.0, reason)


def test_summary(module: str, task: str = "test") -> dict[str, int]:
    summary = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    result_dir = REPO / module / "build" / "test-results" / task
    for xml_file in result_dir.glob("TEST-*.xml"):
        try:
            root = ET.parse(xml_file).getroot()  # Reads only Gradle-generated XML under the local build directory.
        except (ET.ParseError, OSError):
            continue
        for key in summary:
            summary[key] += int(root.get(key, "0"))
    return summary


def line_coverage(module: str) -> dict[str, Any] | None:
    report = REPO / module / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    if not report.exists():
        return None
    try:
        root = ET.parse(report).getroot()  # Reads only Gradle-generated XML under the local build directory.
    except (ET.ParseError, OSError):
        return None
    line = next((counter for counter in root.findall("counter") if counter.get("type") == "LINE"), None)
    if line is None:
        return None
    covered = int(line.get("covered", "0"))
    missed = int(line.get("missed", "0"))
    total = covered + missed
    return {
        "covered": covered,
        "missed": missed,
        "percent": round(covered * 100 / total, 2) if total else 0.0,
    }


def collect_metrics() -> dict[str, Any]:
    return {
        "tests": {module: test_summary(module) for module in MODULES},
        "slow_tests": {"backend": test_summary("backend", "slowTest")},
        "line_coverage": {
            module: coverage
            for module in MODULES
            if (coverage := line_coverage(module)) is not None
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--skip-slow", action="store_true")
    parser.add_argument("--skip-boot-jars", action="store_true")
    parser.add_argument("--run-http-e2e", action="store_true")
    parser.add_argument("--run-cli-e2e", action="store_true")
    parser.add_argument("--run-docker-e2e", action="store_true")
    return parser.parse_args()


def write_report(report_path: Path, gates: list[GateResult], state: str) -> None:
    report = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "repository": str(REPO),
        "state": state,
        "gates": [asdict(gate) for gate in gates],
        "metrics": collect_metrics(),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n")


def main() -> int:
    args = parse_args()
    gradle = ["./gradlew", "--no-daemon"]
    gates: list[GateResult] = []

    def record(gate: GateResult) -> None:
        gates.append(gate)
        write_report(args.report, gates, "RUNNING")

    write_report(args.report, gates, "RUNNING")
    record(run_command(
        "module-tests",
        gradle + [":backend:test", ":telegram-bot:test", ":cli:test"],
        args.timeout,
    ))
    record(run_command(
        "coverage-reports",
        gradle + [
            ":backend:jacocoTestReport",
            ":telegram-bot:jacocoTestReport",
            "-x",
            ":backend:test",
            "-x",
            ":telegram-bot:test",
        ],
        args.timeout,
    ))
    record(run_command(
        "endpoint-coverage-inventory",
        [sys.executable, "scripts/endpoint_coverage_report.py"],
        args.timeout,
    ))
    if args.skip_slow:
        record(not_run("slow-postgresql-integration", "disabled by --skip-slow"))
    else:
        record(run_command("slow-postgresql-integration", gradle + [":backend:slowTest"], args.timeout))
    if args.skip_boot_jars:
        record(not_run("boot-jars", "disabled by --skip-boot-jars"))
    else:
        record(run_command(
            "boot-jars",
            gradle + [":backend:bootJar", ":telegram-bot:bootJar", ":cli:bootJar"],
            args.timeout,
        ))
    record(run_command(
        "release-runner-self-test",
        [sys.executable, "-m", "unittest", "scripts.tests.test_release_verify"],
        args.timeout,
    ))

    optional_gates = (
        ("http-e2e", args.run_http_e2e, [sys.executable, "e2e/run_e2e.py", "--evidence-dir", "build/http-e2e-evidence"], "requires a running local backend; pass --run-http-e2e"),
        ("cli-e2e", args.run_cli_e2e, [sys.executable, "e2e/run_cli_e2e.py"], "requires a running local backend and CLI JAR; pass --run-cli-e2e"),
        ("docker-compose-e2e", args.run_docker_e2e, ["./scripts/e2e-docker-compose-test.sh"], "requires Docker; pass --run-docker-e2e"),
    )
    for name, enabled, command, reason in optional_gates:
        record(run_command(name, command, args.timeout) if enabled else not_run(name, reason))

    write_report(args.report, gates, "COMPLETE")
    for gate in gates:
        print(f"{gate.status:7} {gate.name} ({gate.duration_seconds:.1f}s)")
    print(f"report: {args.report}")
    return 1 if any(gate.status == "FAIL" for gate in gates) else 0


if __name__ == "__main__":
    raise SystemExit(main())
