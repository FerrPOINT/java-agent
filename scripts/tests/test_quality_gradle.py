"""Exercise the public helper boundary without starting Gradle or a JVM."""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


@unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required; WSL is not used")
class QualityGradleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        helper = self.root / "scripts/quality-gradle.sh"
        helper.parent.mkdir()
        helper.write_bytes((ROOT / "scripts/quality-gradle.sh").read_bytes().replace(b"\r\n", b"\n"))
        self.helper = helper
        adapter = self.root / "scripts/quality-make.sh"
        adapter.write_bytes((ROOT / "scripts/quality-make.sh").read_bytes().replace(b"\r\n", b"\n"))
        self.adapter = adapter
        for directory, version in ((self.root, "9.6.1"), (self.root / "backend", "9.8.1")):
            directory.mkdir(exist_ok=True)
            (directory / "gradlew").write_text(
                'printf "%s\\n" "EPOCH=$SOURCE_DATE_EPOCH" "DIRECTORY=$PWD" "$@"\n'
                'if [ "${FAIL_GRAPH:-}" = backend ] && [ "${PWD##*/}" = backend ]; then exit 51; fi\n'
                'if [ "${FAIL_GRAPH:-}" = root ] && [ "${PWD##*/}" != backend ]; then exit 52; fi\n',
                newline="\n")
            wrapper = directory / "gradle/wrapper/gradle-wrapper.properties"
            wrapper.parent.mkdir(parents=True)
            wrapper.write_text("distributionUrl=https\\://services.gradle.org/distributions/gradle-" + version + "-bin.zip\n")
        self.jdk = self.root / "jdk"
        self.jdk.mkdir()
        (self.jdk / "release").write_text('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="25.0.4.1"\nOS_NAME="Linux"\nOS_ARCH="x86_64"\n')

    def test_dependency_snapshot_is_explicit_and_does_not_add_unit_or_jar_tasks(self):
        arguments = ['--backend', ':qualityDependencyInputs', '--offline',
                     '--configuration-cache', '--configuration-cache-problems=fail']
        result, lines = self.invoke(*arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(lines[2:], arguments[1:])

    def invoke(self, *arguments, epoch="1792072450"):
        environment = {**os.environ, "SOURCE_DATE_EPOCH": epoch, "JAVA_HOME": self.jdk.as_posix()}
        # Git Bash translates its standard JAVA_HOME environment to POSIX itself.
        result = subprocess.run([BASH, self.helper.as_posix(), *arguments], env=environment,
                                check=False, capture_output=True, text=True)
        return result, result.stdout.splitlines()

    def test_native_ordinary_graphs_enable_strict_cache_without_losing_arguments(self):
        for arguments in (("--backend", ":test", ":bootJar", "--no-daemon", "--max-workers=2"),
                          ("--no-daemon", "--max-workers=2", ":telegram-bot:test", ":cli:test", ":shared:test"),
                          (":telegram-bot:test", ":cli:test", ":shared:test", ":telegram-bot:bootJar", ":cli:bootJar", "--offline")):
            with self.subTest(arguments=arguments):
                result, lines = self.invoke(*arguments)
                self.assertEqual(result.returncode, 0, result.stderr)
                actual = lines[2:]
                expected = list(arguments[1:] if arguments[0] == "--backend" else arguments)
                self.assertEqual(actual, expected + ["--configuration-cache", "--configuration-cache-problems=fail"])
                self.assertEqual(lines[0], "EPOCH=1792072450")
                self.assertEqual(lines[1].endswith("/backend"), arguments[0] == "--backend")

    def test_backend_patch_wrapper_gets_strict_default_only_for_qualified_role_and_version(self):
        backend = self.root / 'backend/gradle/wrapper/gradle-wrapper.properties'
        root = self.root / 'gradle/wrapper/gradle-wrapper.properties'
        arguments = ['--backend', ':test', ':bootJar', '--no-daemon', '--max-workers=2']
        for version in ("9.8.0", "9.8.1"):
            backend.write_text(f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n')
            result, lines = self.invoke(*arguments)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(lines[2:], arguments[1:] + ['--configuration-cache', '--configuration-cache-problems=fail'])
        backend.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip\n')
        result, lines = self.invoke(*arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(lines[2:], arguments[1:])
        root.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip\n')
        root_arguments = [':cli:test', '--no-daemon', '--max-workers=2']
        result, lines = self.invoke(*root_arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(lines[2:], root_arguments)

    def test_release_preparation_unknown_and_explicit_cache_options_are_preserved(self):
        cases = [("--backend", "releaseCheck"), ("--backend", "integrationTest", "slowTest"),
                 ("--backend", "-I", "fixture.gradle", "qaResolveDependencies"),
                 ("--backend", "test", "--tests", "example.OneTest"),
                 ("test",), (":backend:test",), (":cli:test", "--no-configuration-cache"),
                 (":cli:test", "--configuration-cache"),
                 (":cli:test", "--configuration-cache-problems=warn")]
        for arguments in cases:
            with self.subTest(arguments=arguments):
                result, lines = self.invoke(*arguments)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(lines[2:], list(arguments[1:] if arguments[0] == "--backend" else arguments))

    def test_changed_toolchain_does_not_inherit_an_old_qualification(self):
        release = (self.jdk / "release").read_text()
        for file, text in ((self.jdk / "release", release.replace('JAVA_VERSION="25.0.4.1"', 'JAVA_VERSION="26"')),
                           (self.jdk / "release", release.replace('OS_NAME="Linux"', 'OS_NAME="Windows"')),
                           (self.jdk / "release", release.replace('OS_ARCH="x86_64"', 'OS_ARCH="aarch64"')),
                           (self.jdk / "release", release.replace('IMPLEMENTOR="Eclipse Adoptium"', 'IMPLEMENTOR="Other Vendor"')),
                           (self.root / "backend/gradle/wrapper/gradle-wrapper.properties", "# previous: gradle-9.8.0-bin.zip\ndistributionUrl=gradle-9.9.0-bin.zip\n"),
                           (self.root / "backend/gradle/wrapper/gradle-wrapper.properties", "distributionUrl=gradle-9.9.0-bin.zip\n")):
            original = file.read_text()
            file.write_text(text)
            result, lines = self.invoke("--backend", "test")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(lines[2:], ["test"])
            file.write_text(original)

    def test_invalid_epoch_stops_before_the_wrapper(self):
        result, lines = self.invoke("--backend", "test", epoch="not-unix-seconds")
        self.assertEqual(result.returncode, 64)
        self.assertEqual(lines, [])

    def invoke_make_adapter(self, action, module, *arguments, **overrides):
        environment = {**os.environ, "SOURCE_DATE_EPOCH": "1792072450", "JAVA_HOME": self.jdk.as_posix(), **overrides}
        result = subprocess.run([BASH, self.adapter.as_posix(), action, module, *arguments], env=environment,
                                capture_output=True, text=True, timeout=10)
        blocks = result.stdout.split("EPOCH=")[1:]
        records = []
        for block in blocks:
            epoch, directory, *arguments = block.splitlines()
            records.append({"epoch": epoch, "backend": directory.endswith("/backend"), "arguments": arguments})
        return result, records

    def test_make_adapter_selects_only_requested_units_and_preserves_strict_helper(self):
        for module, tasks in (("backend", [":test"]), ("cli", [":cli:test"]),
                              ("bot", [":telegram-bot:test"]), ("shared", [":shared:test"])):
            for action in ("build", "test"):
                with self.subTest(module=module, action=action):
                    result, records = self.invoke_make_adapter(action, module)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(len(records), 1)
                    self.assertEqual(records[0]["backend"], module == "backend")
                    self.assertEqual(records[0]["arguments"], tasks + [
                        "--no-daemon", "--max-workers=2", "--configuration-cache", "--configuration-cache-problems=fail"])

    def test_make_adapter_all_uses_two_sequential_graphs_without_duplicate_units(self):
        for action in ("build", "test", "jar"):
            with self.subTest(action=action):
                result, records = self.invoke_make_adapter(action, "all")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([record["backend"] for record in records], [True, False])
                self.assertEqual({record["epoch"] for record in records}, {"1792072450"})
                expected_backend = [":bootJar"] if action == "jar" else [":test"]
                expected_root = ([":telegram-bot:bootJar", ":cli:bootJar", ":shared:test"] if action == "jar"
                                 else [":telegram-bot:test", ":cli:test", ":shared:test"])
                options = ["--no-daemon", "--max-workers=2", "--configuration-cache", "--configuration-cache-problems=fail"]
                self.assertEqual(records[0]["arguments"], expected_backend + options)
                self.assertEqual(records[1]["arguments"], expected_root + options)

    def test_make_adapter_scoped_jar_keeps_units_on_bootjar_and_shared_library(self):
        for module, tasks in (("backend", [":bootJar"]), ("cli", [":cli:bootJar"]),
                              ("bot", [":telegram-bot:bootJar"]), ("shared", [":shared:test", ":shared:jar"])):
            with self.subTest(module=module):
                result, records = self.invoke_make_adapter("jar", module)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(records), 1)
                actual_tasks = [argument for argument in records[0]["arguments"] if argument.startswith(":")]
                self.assertEqual(actual_tasks, tasks)

    def test_make_adapter_manual_coverage_keeps_only_existing_report_consumers(self):
        for module, expected in (("backend", [(True, ":jacocoTestReport")]),
                                 ("bot", [(False, ":telegram-bot:jacocoTestReport")]),
                                 ("all", [(True, ":jacocoTestReport"), (False, ":telegram-bot:jacocoTestReport")])):
            with self.subTest(module=module):
                result, records = self.invoke_make_adapter("coverage", module)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([(record["backend"], record["arguments"][0]) for record in records], expected)
                for record in records:
                    self.assertEqual(record["arguments"][1:], ["--no-daemon", "--max-workers=2"])

    def test_make_adapter_invalid_selection_and_failure_never_start_another_graph(self):
        for action, module in (("test", "typo"), ("typo", "all"), ("coverage", "cli"), ("coverage", "shared")):
            with self.subTest(action=action, module=module):
                result, records = self.invoke_make_adapter(action, module)
                self.assertEqual(result.returncode, 64, result.stderr)
                self.assertEqual(records, [])
        result, records = self.invoke_make_adapter("jar", "all", FAIL_GRAPH="backend")
        self.assertEqual(result.returncode, 51, result.stderr)
        self.assertEqual([record["backend"] for record in records], [True])
        result, records = self.invoke_make_adapter("jar", "all", FAIL_GRAPH="root")
        self.assertEqual(result.returncode, 52, result.stderr)
        self.assertEqual([record["backend"] for record in records], [True, False])
        result, records = self.invoke_make_adapter("test", "all", SOURCE_DATE_EPOCH="invalid")
        self.assertEqual(result.returncode, 64, result.stderr)
        self.assertEqual(records, [])

    def test_make_adapter_combines_manual_reports_with_requested_units_or_jars(self):
        expected = {
            ("test", "all"): [[":jacocoTestReport"],
                              [":telegram-bot:jacocoTestReport", ":cli:test", ":shared:test"]],
            ("jar", "all"): [[":bootJar", ":jacocoTestReport"],
                             [":telegram-bot:bootJar", ":telegram-bot:jacocoTestReport", ":cli:bootJar", ":shared:test"]],
            ("test", "backend"): [[":jacocoTestReport"]],
            ("jar", "bot"): [[":telegram-bot:bootJar", ":telegram-bot:jacocoTestReport"]],
        }
        for (action, module), tasks in expected.items():
            with self.subTest(action=action, module=module):
                result, records = self.invoke_make_adapter(action, module, "--coverage")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([row["arguments"] for row in records],
                                 [graph + ["--no-daemon", "--max-workers=2"] for graph in tasks])
                self.assertEqual({row["epoch"] for row in records}, {"1792072450"})
                self.assertEqual([row["backend"] for row in records],
                                 [True, False] if module == "all" else [module == "backend"])

    def test_combined_make_reports_reject_unsupported_scope_and_preserve_failure(self):
        for module in ("cli", "shared"):
            result, records = self.invoke_make_adapter("jar", module, "--coverage")
            self.assertEqual(result.returncode, 64, result.stderr)
            self.assertEqual(records, [])
        result, records = self.invoke_make_adapter("test", "all", "--typo")
        self.assertEqual(result.returncode, 64)
        self.assertEqual(records, [])
        result, records = self.invoke_make_adapter("jar", "all", "--coverage", FAIL_GRAPH="backend")
        self.assertEqual(result.returncode, 51, result.stderr)
        self.assertEqual([row["backend"] for row in records], [True])

    def test_make_adapter_freezes_implicit_epoch_before_the_two_graphs(self):
        environment = {**os.environ, "GIT_AUTHOR_DATE": "@1792072450 +0000",
                       "GIT_COMMITTER_DATE": "@1792072450 +0000"}
        for arguments in (["init", "-q"], ["add", "."],
                          ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "initial"]):
            subprocess.run(["git", *arguments], cwd=self.root, env=environment, capture_output=True, check=True)
        wrapper = self.root / "backend/gradlew"
        with wrapper.open("a", newline="\n") as stream:
            stream.write(
                'GIT_AUTHOR_DATE="@1792072550 +0000" GIT_COMMITTER_DATE="@1792072550 +0000" '
                'git -C .. -c user.name=Fixture -c user.email=fixture@example.test commit --allow-empty -qm moved\n')
        result, records = self.invoke_make_adapter("jar", "all", SOURCE_DATE_EPOCH="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([record["epoch"] for record in records], ["1792072450", "1792072450"])
        actual_epoch = subprocess.check_output(["git", "show", "-s", "--format=%ct", "HEAD"], cwd=self.root, text=True).strip()
        self.assertEqual(actual_epoch, "1792072550", "Fixture must move HEAD between graphs")

    @unittest.skipUnless(shutil.which("make"), "GNU Make is required for real Makefile dispatch")
    def test_real_make_coalesces_requested_targets_and_guards_partial_install(self):
        (self.root / "Makefile").write_bytes((ROOT / "Makefile").read_bytes())
        environment = {**os.environ, "SOURCE_DATE_EPOCH": "1792072450", "JAVA_HOME": self.jdk.as_posix()}
        for goals in (("build", "test"), ("build", "test", "jar"), ("jar",)):
            with self.subTest(goals=goals):
                result = subprocess.run([shutil.which("make"), "--no-print-directory", "-j8", *goals,
                                         "MODULE=cli", "SHELL=" + BASH], cwd=self.root, env=environment,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.count("EPOCH="), 1, result.stdout)
                self.assertIn(":cli:bootJar" if "jar" in goals else ":cli:test", result.stdout)
                self.assertNotIn("/backend", result.stdout)
        for goal in ("install", "release"):
            with self.subTest(goal=goal):
                result = subprocess.run([shutil.which("make"), "--no-print-directory", goal, "MODULE=cli", "SHELL=" + BASH],
                                        cwd=self.root, env=environment, capture_output=True, text=True, timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("require MODULE=all", result.stderr)
                self.assertNotIn("EPOCH=", result.stdout)
                self.assertFalse((self.root / ".version").exists())

    @unittest.skipUnless(shutil.which("make"), "GNU Make is required for real Makefile dispatch")
    def test_real_make_combined_coverage_goals_use_one_graph_per_wrapper(self):
        (self.root / "Makefile").write_bytes((ROOT / "Makefile").read_bytes())
        environment = {**os.environ, "SOURCE_DATE_EPOCH": "1792072450", "JAVA_HOME": self.jdk.as_posix()}
        for goals in (("test", "coverage"), ("coverage", "build", "test"),
                      ("jar", "coverage"), ("coverage", "jar", "test")):
            with self.subTest(goals=goals):
                result = subprocess.run([shutil.which("make"), "--no-print-directory", "-j8", *goals,
                                         "MODULE=all", "SHELL=" + BASH], cwd=self.root, env=environment,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.count("EPOCH="), 2, result.stdout)
                # The bot task ends with the backend name; count complete lines.
                lines = result.stdout.splitlines()
                self.assertEqual(lines.count(":jacocoTestReport"), 1, result.stdout)
                self.assertEqual(lines.count(":telegram-bot:jacocoTestReport"), 1, result.stdout)
                self.assertIn(":shared:test\n", result.stdout)
                self.assertIn(":cli:bootJar\n" if "jar" in goals else ":cli:test\n", result.stdout)

    def test_make_source_changes_select_fast_tooling_without_application_builds(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("java_quality_selection", ROOT / "scripts/vendor/base_quality_selection.py")
        selector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selector)
        descriptor = json.loads((ROOT / ".quality-gate.json").read_text(encoding="utf-8"))
        gate = next(item for item in descriptor["gates"] if item["id"] == "tooling:gradle-helper-unit")
        self.assertIn("scripts/vendor/base_quality_selection.py", gate["inputs"])
        for path in ("Makefile", "scripts/quality-make.sh", "scripts/tests/test_quality_gradle.py"):
            with self.subTest(path=path):
                selected = selector.plan(ROOT, profile="build", changed=[path])
                self.assertTrue(any(gate["id"] == "tooling:gradle-helper-unit" for gate in selected["gates"]))
                self.assertTrue(all(gate["component"] == "tooling" for gate in selected["gates"]))
        production = selector.plan(ROOT, profile="build", changed=["backend/src/main/java/example/Production.java"])
        self.assertTrue(any(gate["id"] == "backend:build" for gate in production["gates"]))


@unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required; WSL is not used")
class ContainerGradleWrapperTests(unittest.TestCase):
    def test_all_runtime_builders_bound_gradle_workers_and_accept_wrapper_newlines(self):
        payload = b'#!/bin/sh\nprintf \'%s\\n\' "$@" > invoked.txt\n'
        variants = (("Dockerfile", ["bootJar", "--no-daemon", "--build-cache"]),
                    ("Dockerfile.slim", ["bootJar", "--no-daemon", "--build-cache"]),
                    ("telegram-bot/Dockerfile", [":telegram-bot:bootJar", "--build-cache", "--no-daemon"]),
                    ("cli/Dockerfile", [":cli:bootJar", "--build-cache", "--no-daemon"]))
        for name, arguments in variants:
            recipe = (ROOT / name).read_text(encoding="utf-8")
            commands = [re.sub(r"^RUN(?: --mount=\S+)* ", "", line)
                        for line in recipe.splitlines()
                        if line.startswith("RUN ") and
                        ("chmod +x gradlew" in line or "./gradlew " in line)]
            self.assertTrue(commands, name)
            for newline in (b"\n", b"\r\n"):
                with self.subTest(recipe=name, newline=newline), tempfile.TemporaryDirectory() as temporary:
                    directory = Path(temporary)
                    wrapper = directory / "gradlew"
                    wrapper.write_bytes(payload.replace(b"\n", newline))
                    result = subprocess.run([BASH, "-c", "set -e\n" + "\n".join(commands)], cwd=directory,
                                            capture_output=True, text=True, check=False)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(wrapper.read_bytes(), payload)
                    self.assertEqual((directory / "invoked.txt").read_text().splitlines(),
                                     arguments + ["--max-workers=2"])


if __name__ == "__main__":
    unittest.main()


class StandalonePrerequisiteTests(unittest.TestCase):
    def test_linux_clients_keep_one_wrapper_and_backend_unit_owner(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "java_grouping_selection", ROOT / "scripts/vendor/base_quality_selection.py")
        selection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selection)
        candidate = selection.plan(ROOT, "build", changed=[
            "shared/src/main/java/example/Production.java"], execution_platform="linux")
        gates = {gate["id"]: gate for gate in candidate["gates"]}
        grouped = gates["root:unit"]
        self.assertEqual(grouped["member_gates"], ["bot:unit", "cli:unit", "shared:unit"])
        self.assertEqual(grouped["argv"][-3:], [":telegram-bot:test", ":cli:test", ":shared:test"])
        self.assertNotIn("--backend", grouped["argv"])
        self.assertFalse(grouped["cacheable"])
        self.assertEqual(gates["backend:build"]["covers_units"], ["backend:unit"])
        order = list(gates)
        self.assertLess(order.index("root:unit"), order.index("backend:build"))
        self.assertLess(order.index("backend:build"), order.index("backend:unit"))

    def test_independent_units_and_static_precede_artifact(self):
        import importlib.util
        from unittest.mock import patch

        spec = importlib.util.spec_from_file_location(
            "java_prerequisite_selection", ROOT / "scripts/vendor/base_quality_selection.py")
        selection = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selection)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def gate(identifier, component, tier):
                return {
                    "id": identifier, "component": component, "test_tier": tier,
                    "profiles": ["build"], "argv": ["fixture-never-executed"], "cwd": ".",
                    "inputs": ["source.txt"], "cacheable": False,
                    **({"kind": "artifact", "outputs": [".local/ready"]} if tier == "artifact" else {}),
                }

            manifest = {
                "schema": "base-quality-product/v1", "product": "fixture",
                "components": {"app": ["source.txt"], "docs": ["docs/**"]},
                "gates": [gate("bundle:build", "app", "artifact"),
                          gate("source:unit", "app", "unit"), gate("docs:static", "docs", "static")],
            }
            (root / ".quality-gate.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "source.txt").write_text("never compiled\n", encoding="utf-8")
            with patch.object(selection, "git", return_value="a" * 40):
                candidate = selection.plan(root, "build", components=["app", "docs"])
            self.assertEqual([gate["id"] for gate in candidate["gates"]],
                             ["source:unit", "docs:static", "bundle:build"])
