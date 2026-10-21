"""Regression tests for local release evidence helpers."""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


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
    def test_module_discovery_excludes_manual_process_protocol(self) -> None:
        suite = load_tests(unittest.defaultTestLoader, unittest.TestSuite(), None)
        cases = [case for group in suite for case in group]
        self.assertTrue(any(isinstance(case, EndpointCoverageReportTest) for case in cases))
        self.assertTrue(any(isinstance(case, ReleaseVerifyTest) for case in cases))
        self.assertFalse(any(isinstance(case, ReleaseVerifyProtocolTest) for case in cases))
        explicit = unittest.defaultTestLoader.loadTestsFromTestCase(ReleaseVerifyProtocolTest)
        self.assertEqual(1, explicit.countTestCases())

    def test_release_helper_changes_select_pure_tooling_and_keep_manual_protocol_explicit(self) -> None:
        spec = importlib.util.spec_from_file_location("java_release_selection", SCRIPTS / "vendor/base_quality_selection.py")
        selector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selector)
        root = SCRIPTS.parent
        for profile in ("fast", "unit", "build"):
            for path in ("scripts/release_verify.py", "scripts/tests/test_release_verify.py", "scripts/endpoint_coverage_report.py"):
                with self.subTest(profile=profile, path=path):
                    plan = selector.plan(root, profile=profile, changed=[path])
                    self.assertEqual(["tooling"], plan["components"])
                    gates = {gate["id"]: gate for gate in plan["gates"]}
                    self.assertIn("tooling:release-verifier-unit", gates)
                    self.assertTrue(all(gate["component"] == "tooling" for gate in gates.values()))
                    gate = gates["tooling:release-verifier-unit"]
                    self.assertEqual("none", gate["execution_slot"])
                    self.assertEqual("unit", gate["test_tier"])
                    self.assertIn("scripts.tests.test_release_verify", gate["argv"])
                    self.assertNotIn("tooling:release-verifier-protocol", gates)
        manual = selector.plan(root, profile="integration", components=["tooling"])
        self.assertTrue(any(gate["id"] == "tooling:release-verifier-protocol"
                            and gate["test_tier"] == "integration" and not gate["cacheable"]
                            for gate in manual["gates"]))
        production = selector.plan(root, profile="build", changed=["backend/src/main/java/example/Production.java"])
        self.assertTrue(any(gate["id"] == "backend:build" for gate in production["gates"]))
        unknown = selector.plan(root, profile="build", changed=["unmapped-input.fixture"])
        self.assertTrue(any(gate["id"] == "backend:build" for gate in unknown["gates"]))

    def run_release(self, *arguments: str, fail_backend: bool = False):
        verify = load_module("release_verify_protocol", "release_verify.py")
        commands = []

        def inert_runner(command, **kwargs):
            commands.append(command)
            self.assertEqual(verify.REPO, kwargs["cwd"])
            failed = fail_backend and "--backend" in command
            return verify.subprocess.CompletedProcess(command, int(failed), "inert runner", "")

        with tempfile.TemporaryDirectory() as directory:
            verify.REPO = Path(directory)
            report = verify.REPO / "evidence.json"
            with patch.object(sys, "argv", ["release_verify.py", "--report", str(report), *arguments]), \
                    patch.object(verify.subprocess, "run", side_effect=inert_runner), \
                    redirect_stdout(StringIO()):
                status = verify.main()
            return status, json.loads(report.read_text()), commands

    def test_release_uses_one_complete_graph_per_compatible_wrapper(self) -> None:
        for skip_jars in (False, True):
            with self.subTest(skip_jars=skip_jars):
                status, report, commands = self.run_release(*(["--skip-boot-jars"] if skip_jars else []))
                self.assertEqual(0, status)
                self.assertEqual("COMPLETE", report["state"])
                graphs = [command for command in commands if command[0] == "bash"]
                self.assertEqual(2, len(graphs), graphs)
                backend, root = graphs
                self.assertEqual(["bash", "scripts/quality-gradle.sh", "--backend"], backend[:3])
                self.assertEqual(["bash", "scripts/quality-gradle.sh"], root[:2])
                self.assertIn(":jacocoTestReport", backend)
                self.assertIn(":telegram-bot:jacocoTestReport", root)
                self.assertIn(":cli:test", root)
                self.assertEqual(1, sum("scripts/check_docs_standard.py" in command
                                        and "--check-coverage" in command for command in commands))
                for graph in graphs:
                    self.assertIn("--no-daemon", graph)
                    self.assertIn("--max-workers=2", graph)
                    self.assertNotIn("-x", graph)
                self.assertNotIn(":slowTest", backend)
                for project in ("cli", "shared"):
                    for task in ("test", "integrationTest", "slowTest"):
                        self.assertEqual(1, root.count(":" + project + ":" + task))
                self.assertFalse(any("./gradlew" in command for command in commands))
                self.assertEqual(1, backend.count(":jacocoTestReport"))
                self.assertEqual(1, root.count(":telegram-bot:jacocoTestReport"))
                self.assertNotIn(":backend:jacocoTestReport", root)
                self.assertEqual(not skip_jars, ":bootJar" in backend)
                self.assertEqual(not skip_jars, ":telegram-bot:bootJar" in root)
                self.assertEqual(not skip_jars, ":cli:bootJar" in root)
                self.assertTrue(any("scripts/endpoint_coverage_report.py" in command for command in commands))
                self.assertTrue(any("scripts.tests.test_release_verify" in command for command in commands))

    def test_skip_slow_rejects_full_release_before_any_child_and_stale_metrics(self) -> None:
        status, report, commands = self.run_release("--skip-slow")
        self.assertEqual(1, status)
        self.assertEqual([], commands)
        self.assertEqual("INCOMPLETE", report["state"])
        self.assertEqual({}, report["metrics"])
        self.assertEqual(["FAIL"], [gate["status"] for gate in report["gates"]])
        self.assertIn("--skip-slow", report["gates"][0]["detail"])
        self.assertIn("coverage", report["gates"][0]["detail"])

    def test_failed_release_graph_is_not_reported_as_pass(self) -> None:
        status, report, commands = self.run_release(fail_backend=True)
        self.assertEqual(1, status)
        self.assertEqual(2, len([command for command in commands if command[0] == "bash"]))
        self.assertEqual("FAIL", report["gates"][0]["status"])

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
        with tempfile.TemporaryDirectory() as directory:
            verify.REPO = Path(directory)
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
            verify.REPO = Path(directory)
            report_path = Path(directory) / "evidence.json"
            verify.write_report(report_path, [], "RUNNING")
            persisted = json.loads(report_path.read_text())

        self.assertEqual("RUNNING", persisted["state"])
        self.assertEqual([], persisted["gates"])


class ReleaseVerifyProtocolTest(unittest.TestCase):
    """Manual process protocol; no JVM, package installation or real Gradle."""

    @unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required; WSL is not used")
    def test_real_entrypoint_dispatches_helper_to_each_recording_wrapper_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            scripts = repo / "scripts"
            scripts.mkdir()
            for filename in ("release_verify.py", "quality-gradle.sh"):
                (scripts / filename).write_bytes((SCRIPTS / filename).read_bytes().replace(b"\r\n", b"\n"))
            (scripts / "__init__.py").touch()
            (scripts / "endpoint_coverage_report.py").write_text("# inert inventory fixture\n")
            tests = scripts / "tests"
            tests.mkdir()
            (tests / "__init__.py").touch()
            (tests / "test_release_verify.py").write_text(
                "import unittest\nclass InertTest(unittest.TestCase):\n    def test_fixture(self): pass\n"
            )
            for wrapper in (repo / "gradlew", repo / "backend/gradlew"):
                wrapper.parent.mkdir(parents=True, exist_ok=True)
                wrapper.write_bytes(b'#!/bin/bash\nprintf "%s\\n" "$@" >> invocation.txt\n')
            verify = load_module("release_verify_native_bash_fixture", "release_verify.py")
            verify.REPO = repo
            native_run = subprocess.run

            def native_bash_runner(command, **kwargs):
                # Windows may resolve the bare name to WSL; this fixture permits Git Bash only.
                if command[0] == "bash":
                    command = [BASH, *command[1:]]
                return native_run(command, **kwargs)

            with patch.object(sys, "argv", ["release_verify.py", "--report", str(repo / "report.json")]), \
                    patch.dict(os.environ, {"SOURCE_DATE_EPOCH": "1792072450"}), \
                    patch.object(verify.subprocess, "run", side_effect=native_bash_runner), \
                    redirect_stdout(StringIO()):
                status = verify.main()
            self.assertEqual(0, status, (repo / "report.json").read_text())
            self.assertEqual(
                [":jacocoTestReport", ":bootJar", "--no-daemon", "--max-workers=2"],
                (repo / "backend/invocation.txt").read_text().splitlines(),
            )
            self.assertEqual(
                [":telegram-bot:jacocoTestReport", ":cli:test", ":cli:integrationTest", ":cli:slowTest",
                 ":shared:test", ":shared:integrationTest", ":shared:slowTest", ":telegram-bot:bootJar", ":cli:bootJar",
                 "--no-daemon", "--max-workers=2"],
                (repo / "invocation.txt").read_text().splitlines(),
            )
            report = json.loads((repo / "report.json").read_text())
            self.assertEqual("COMPLETE", report["state"])
            self.assertEqual(["backend-release", "module-release"], [gate["name"] for gate in report["gates"][:2]])


def load_tests(loader, tests, pattern):
    # CI/module discovery runs pure Python checks; the manual class is explicit-only.
    return unittest.TestSuite(loader.loadTestsFromTestCase(cls) for cls in (
        EndpointCoverageReportTest, ReleaseVerifyTest,
    ))


if __name__ == "__main__":
    unittest.main()
