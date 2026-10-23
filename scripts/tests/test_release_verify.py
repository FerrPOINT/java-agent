"""Regression tests for local release evidence helpers."""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    finally:
        sys.modules.pop(name, None)
    return module


class EndpointCoverageReportTest(unittest.TestCase):
    def test_controller_mappings_combines_class_and_method_paths(self) -> None:
        report = load_module("endpoint_coverage_report", "endpoint_coverage_report.py")
        source = '''
            @RequestMapping("/api/v1")
            public class SampleController {
                @GetMapping("/items")
                public void get() {}
                @PostMapping(path = "/items")
                public void create() {}
            }
        '''

        mappings = report.controller_mappings(source)

        self.assertEqual(
            [
                {"method": "GET", "path": "/api/v1/items"},
                {"method": "POST", "path": "/api/v1/items"},
            ],
            mappings,
        )

    def test_build_report_marks_tested_path_reference(self) -> None:
        report = load_module("endpoint_coverage_report", "endpoint_coverage_report.py")
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            controller = repo / "backend/src/main/java/com/azhukov/agent/api/SampleController.java"
            test = repo / "e2e/scenarios/sample.yaml"
            controller.parent.mkdir(parents=True)
            test.parent.mkdir(parents=True)
            controller.write_text(
                '@RequestMapping("/api") public class SampleController { '
                '@GetMapping("/items") public void get() {} }'
            )
            test.write_text("path: /api/items\n")

            original_api_relative = report.API_RELATIVE
            original_test_relatives = report.TEST_RELATIVES
            try:
                report.API_RELATIVE = Path("backend/src/main/java/com/azhukov/agent/api")
                report.TEST_RELATIVES = (Path("e2e"),)
                result = report.build_report(repo)
            finally:
                report.API_RELATIVE = original_api_relative
                report.TEST_RELATIVES = original_test_relatives

        self.assertEqual(1, result["totals"]["mappings"])
        self.assertTrue(result["mappings"][0]["static_reference"])


class ReleaseVerifyTest(unittest.TestCase):
    def run_release_cli(self, *flags):
        verify = load_module("release_verify_lanes", "release_verify.py")
        commands = []

        def completed(name, command, timeout):
            commands.append(command)
            return verify.GateResult(name, "PASS", command, 0.0, "test fixture")

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "evidence.json"
            argv = ["release_verify.py", "--report", str(target), "--skip-boot-jars", *flags]
            with patch.object(sys, "argv", argv), patch.object(verify, "run_command", side_effect=completed):
                self.assertEqual(verify.main(), 0)
            report = json.loads(target.read_text())
        return commands, report

    def test_default_release_cli_leaves_slow_lane_unexecuted(self):
        commands, report = self.run_release_cli()
        self.assertFalse(any(":backend:slowTest" in command or ":backend:fullCheck" in command for command in commands))
        gate = next(item for item in report["gates"] if item["name"] == "slow-postgresql-integration")
        self.assertEqual(gate["status"], "NOT_RUN")
        self.assertIn("--run-slow", gate["detail"])

    def test_slow_lane_requires_explicit_cli_opt_in(self):
        commands, _ = self.run_release_cli("--run-slow")
        self.assertEqual(sum(":backend:fullCheck" in command for command in commands), 1)

    def test_line_coverage_returns_percent(self) -> None:
        verify = load_module("release_verify", "release_verify.py")
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            report = repo / "backend/build/reports/jacoco/test/jacocoTestReport.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<report><counter type="LINE" missed="1" covered="4"/></report>')
            original_repo = verify.REPO
            try:
                verify.REPO = repo
                coverage = verify.line_coverage("backend")
            finally:
                verify.REPO = original_repo

        self.assertEqual({"covered": 4, "missed": 1, "percent": 80.0}, coverage)

    def test_collected_metrics_are_json_serializable(self) -> None:
        verify = load_module("release_verify_metrics", "release_verify.py")
        metrics = verify.collect_metrics()
        self.assertIsInstance(json.dumps(metrics), str)

    def test_run_command_clears_root_dbus_environment(self) -> None:
        verify = load_module("release_verify_env", "release_verify.py")
        original_environ = os.environ.copy()
        try:
            os.environ["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=/run/user/0/bus"
            os.environ["XDG_RUNTIME_DIR"] = "/run/user/0"
            result = verify.run_command(
                "environment",
                [
                    sys.executable,
                    "-c",
                    "import os; assert 'DBUS_SESSION_BUS_ADDRESS' not in os.environ; "
                    "assert 'XDG_RUNTIME_DIR' not in os.environ",
                ],
                10,
            )
        finally:
            os.environ.clear()
            os.environ.update(original_environ)

        self.assertEqual("PASS", result.status)

    def test_write_report_marks_incomplete_run(self) -> None:
        verify = load_module("release_verify_report", "release_verify.py")
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "evidence.json"
            verify.write_report(report_path, [], "RUNNING")
            persisted = json.loads(report_path.read_text())

        self.assertEqual("RUNNING", persisted["state"])
        self.assertEqual([], persisted["gates"])


if __name__ == "__main__":
    unittest.main()
