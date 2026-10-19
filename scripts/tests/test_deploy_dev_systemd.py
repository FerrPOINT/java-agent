"""Regression tests for the host systemd deployment helper."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "deploy-dev-systemd.sh"
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


class DeployDevSystemdTest(unittest.TestCase):
    def test_skip_build_publishes_one_release_and_verifies_both_services(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            install_dir = root / "install"
            systemd_dir = root / "systemd"
            systemd_dir.mkdir()
            for component in ("backend", "bot"):
                (systemd_dir / f"java-agent-{component}.service").write_text(
                    f"ExecStart=/usr/bin/java -jar {install_dir}/lib/java-agent-{component}-latest.jar\n"
                )
            fake_systemctl = root / "systemctl"
            fake_curl = root / "curl"
            calls = root / "calls.log"
            fake_systemctl.write_text(
                "#!/usr/bin/env bash\n"
                "printf 'systemctl %s\\n' \"$*\" >> \"$DEPLOY_CALLS\"\n"
            )
            fake_curl.write_text(
                "#!/usr/bin/env bash\n"
                "printf 'curl %s\\n' \"$*\" >> \"$DEPLOY_CALLS\"\n"
                "printf '{\"status\":\"UP\"}\\n'\n"
            )
            fake_systemctl.chmod(0o755)
            fake_curl.chmod(0o755)

            backend = REPO / "backend/build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar"
            bot = REPO / "telegram-bot/build/libs/telegram-bot-0.0.1-SNAPSHOT.jar"
            backend.parent.mkdir(parents=True, exist_ok=True)
            bot.parent.mkdir(parents=True, exist_ok=True)
            backend_bytes = backend.read_bytes() if backend.exists() else b"backend-test-artifact"
            bot_bytes = bot.read_bytes() if bot.exists() else b"bot-test-artifact"
            created_backend = not backend.exists()
            created_bot = not bot.exists()
            if created_backend:
                backend.write_bytes(backend_bytes)
            if created_bot:
                bot.write_bytes(bot_bytes)
            try:
                environment = os.environ | {
                    "INSTALL_DIR": str(install_dir),
                    "SYSTEMD_DIR": str(systemd_dir),
                    "SYSTEMCTL": str(fake_systemctl),
                    "CURL": str(fake_curl),
                    "DEPLOY_CALLS": str(calls),
                    "DEPLOY_RELEASE_ID": "test-release",
                }
                completed = subprocess.run(
                    [str(SCRIPT), "--skip-build"],
                    cwd=REPO,
                    env=environment,
                    text=True,
                    capture_output=True,
                    check=False,
                )
            finally:
                if created_backend:
                    backend.unlink()
                if created_bot:
                    bot.unlink()

            self.assertEqual(0, completed.returncode, completed.stderr)
            release = install_dir / "releases/test-release"
            self.assertEqual(release, (install_dir / "latest").resolve())
            self.assertEqual(release / "backend.jar", (install_dir / "lib/java-agent-backend-latest.jar").resolve())
            self.assertEqual(release / "telegram-bot.jar", (install_dir / "lib/java-agent-bot-latest.jar").resolve())
            self.assertEqual("test-release\n", (release / "VERSION").read_text())
            self.assertEqual(backend_bytes, (release / "backend.jar").read_bytes())
            self.assertEqual(bot_bytes, (release / "telegram-bot.jar").read_bytes())
            self.assertEqual(
                [
                    "systemctl stop java-agent-bot.service",
                    "systemctl restart java-agent-backend.service",
                    "curl -fsS --max-time 3 http://127.0.0.1:8090/actuator/health/readiness",
                    "curl -fsS --max-time 5 http://127.0.0.1:8090/actuator/health/readiness",
                    "systemctl restart java-agent-bot.service",
                    "curl -fsS --max-time 3 http://127.0.0.1:8091/actuator/health",
                    "curl -fsS --max-time 5 http://127.0.0.1:8091/actuator/health",
                    "systemctl is-active java-agent-backend.service java-agent-bot.service",
                ],
                calls.read_text().splitlines(),
            )


@unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required; WSL is not used")
class DeployBuildProtocolTest(unittest.TestCase):
    """Recording wrappers stop at the artifact guard, before deployment or JVM work."""

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "source"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        for filename in ("deploy-dev-systemd.sh", "quality-gradle.sh"):
            (scripts / filename).write_bytes((REPO / "scripts" / filename).read_bytes().replace(b"\r\n", b"\n"))
        for directory in (self.root, self.root / "backend"):
            directory.mkdir(exist_ok=True)
            wrapper = directory / "gradlew"
            wrapper.write_text(
                '#!/usr/bin/env bash\n'
                'printf "GRAPH=%s EPOCH=%s\\n" "${PWD##*/}" "$SOURCE_DATE_EPOCH"\n'
                'printf "ARG=%s\\n" "$@"\n'
                'if [[ "${PWD##*/}" == backend ]]; then\n'
                '  [[ "${FAIL_GRAPH:-}" != backend ]] || exit 51\n'
                '  if [[ "${MOVE_HEAD:-}" == true ]]; then\n'
                '    GIT_AUTHOR_DATE="@1792072550 +0000" GIT_COMMITTER_DATE="@1792072550 +0000" '
                'git -C .. -c user.name=Fixture -c user.email=fixture@example.test commit --allow-empty -qm moved\n'
                '  fi\n'
                'else\n'
                '  [[ "${FAIL_GRAPH:-}" != root ]] || exit 52\n'
                'fi\n',
                encoding="utf-8", newline="\n",
            )
            wrapper.chmod(0o755)
            properties = directory / "gradle/wrapper/gradle-wrapper.properties"
            properties.parent.mkdir(parents=True)
            relative = directory.relative_to(self.root)
            properties.write_bytes((REPO / relative / "gradle/wrapper/gradle-wrapper.properties").read_bytes())
        self.jdk = self.root / "jdk"
        self.jdk.mkdir()
        (self.jdk / "release").write_text(
            'IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="25.0.4.1"\nOS_NAME="Linux"\nOS_ARCH="x86_64"\n'
        )

    def invoke(self, *arguments: str, **overrides: str):
        environment = {
            **os.environ, "JAVA_AGENT_ROOT": self.root.as_posix(), "JAVA_HOME": self.jdk.as_posix(),
            "INSTALL_DIR": (self.root / "never-install").as_posix(),
            "DEPLOY_RELEASE_ID": "inert-build-protocol", "SOURCE_DATE_EPOCH": "1792072450",
            "FAIL_GRAPH": "", "MOVE_HEAD": "", **overrides,
        }
        result = subprocess.run(
            [BASH, (self.root / "scripts/deploy-dev-systemd.sh").as_posix(), *arguments],
            cwd=self.root, env=environment, text=True, capture_output=True, timeout=10, check=False,
        )
        records = []
        for line in result.stdout.splitlines():
            if line.startswith("GRAPH="):
                graph, epoch = line[6:].split(" EPOCH=")
                records.append({"graph": graph, "epoch": epoch, "arguments": []})
            elif line.startswith("ARG="):
                records[-1]["arguments"].append(line[4:])
        self.assertFalse((self.root / "never-install").exists(), "No install stage may run in this fixture")
        return result, records

    def test_build_routes_backend_and_bot_once_through_the_existing_helper(self) -> None:
        result, records = self.invoke()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("Missing build artifact:", result.stderr)
        self.assertEqual(["backend", "source"], [record["graph"] for record in records])
        self.assertEqual(["1792072450", "1792072450"], [record["epoch"] for record in records])
        options = ["--no-daemon", "--max-workers=2", "--configuration-cache", "--configuration-cache-problems=fail"]
        self.assertEqual([":bootJar", *options], records[0]["arguments"])
        self.assertEqual([":telegram-bot:bootJar", *options], records[1]["arguments"])

    def declared_artifacts(self):
        import json

        applications = json.loads((REPO / "java-artifacts.json").read_text(encoding="utf-8"))["applications"]
        return {name: self.root / applications[name]["module"] / "build/libs" / applications[name]["filename"]
                for name in ("backend", "bot")}

    def observe_artifact_guard(self):
        # Execute the actual artifact-consumer prefix, with an inspected stop
        # before directory creation, copying, symlinks or service commands.
        source = SCRIPT.read_text(encoding="utf-8")
        boundary = '\nreleases_dir="$INSTALL_DIR/releases"\n'
        self.assertEqual(1, source.count(boundary))
        prefix, suffix = source.split(boundary)
        self.assertTrue(prefix.rstrip().endswith("done"))
        self.assertIn('for artifact in "$backend_jar" "$bot_jar"; do', prefix)
        self.assertTrue(suffix.startswith('release_dir="$releases_dir/$RELEASE_ID"\n'))
        for forbidden in ('mkdir ', 'cp ', 'ln ', '"$SYSTEMCTL"', '"$CURL"'):
            self.assertNotIn(forbidden, prefix)
        (self.root / "scripts/deploy-dev-systemd.sh").write_text(
            prefix + '\nprintf "BACKEND_ARTIFACT=%s\\n" "$backend_jar"\n', encoding="utf-8", newline="\n",
        )
        return self.invoke("--skip-build")

    def test_consumer_paths_match_the_canonical_artifact_contract(self) -> None:
        import re

        source = SCRIPT.read_text(encoding="utf-8")
        for name, path in self.declared_artifacts().items():
            variable = "backend_jar" if name == "backend" else "bot_jar"
            self.assertIn(f'{variable}="$ROOT/{path.relative_to(self.root).as_posix()}"', source)
        settings = (REPO / "backend/settings.gradle").read_text(encoding="utf-8")
        project = re.search(r"^rootProject.name = '([^']+)'", settings, re.MULTILINE).group(1)
        self.assertTrue(self.declared_artifacts()["backend"].name.startswith(project + "-"))

    def test_canonical_fresh_jar_is_selected_over_a_different_stale_legacy_jar(self) -> None:
        artifacts = self.declared_artifacts()
        for path in artifacts.values():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fresh-canonical-artifact")
        legacy = self.root / "backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
        legacy.write_bytes(b"different-stale-legacy-artifact")
        result, records = self.observe_artifact_guard()
        self.assertEqual(0, result.returncode, result.stderr)
        selected = Path(next(line.removeprefix("BACKEND_ARTIFACT=") for line in result.stdout.splitlines()
                             if line.startswith("BACKEND_ARTIFACT=")))
        self.assertEqual(artifacts["backend"], selected)
        self.assertEqual(b"fresh-canonical-artifact", selected.read_bytes())
        self.assertNotEqual(legacy.read_bytes(), selected.read_bytes())
        self.assertEqual([], records)

    def test_missing_declared_artifact_rejects_legacy_or_partial_pairs(self) -> None:
        artifacts = self.declared_artifacts()
        for missing in ("backend", "bot"):
            with self.subTest(missing=missing):
                for path in artifacts.values():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"fresh-canonical-artifact")
                (self.root / "backend/build/libs/backend-0.0.1-SNAPSHOT.jar").write_bytes(b"stale-legacy-artifact")
                artifacts[missing].unlink()
                result, records = self.observe_artifact_guard()
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("Missing build artifact: " + artifacts[missing].as_posix(), result.stderr)
                self.assertNotIn("BACKEND_ARTIFACT=", result.stdout)
                self.assertEqual([], records)

    def test_systemd_changes_select_only_the_bounded_tooling_owner(self) -> None:
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "systemd_quality_selection", REPO / "scripts/vendor/base_quality_selection.py",
        )
        selector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selector)
        paths = ("scripts/deploy-dev-systemd.sh", "scripts/tests/test_deploy_dev_systemd.py")
        owner = "tooling:systemd-build-protocol"
        for profile in ("fast", "unit", "build"):
            for path in paths:
                with self.subTest(profile=profile, path=path):
                    selected = selector.plan(REPO, profile=profile, changed=[path])
                    self.assertEqual(["tooling"], selected["components"])
                    self.assertTrue(all(gate["component"] == "tooling" for gate in selected["gates"]))
                    gate = next(gate for gate in selected["gates"] if gate["id"] == owner)
                    self.assertEqual("none", gate["execution_slot"])
                    self.assertFalse(gate["cacheable"])
                    self.assertEqual(["{python}", "-B", "-m", "unittest",
                                      "scripts.tests.test_deploy_dev_systemd.DeployBuildProtocolTest", "-q"], gate["argv"])
                    self.assertTrue(set(paths).issubset(gate["inputs"]))
                    self.assertIn("scripts/vendor/base_quality_selection.py", gate["inputs"])
                    self.assertTrue({"java-artifacts.json", "backend/settings.gradle"}.issubset(gate["inputs"]))
        release = selector.plan(REPO, profile="release", components=["tooling"])
        self.assertFalse(any(gate["id"] == owner for gate in release["gates"]))
        workflow = (REPO / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertEqual(1, workflow.count("scripts.tests.test_deploy_dev_systemd"))
        self.assertNotIn("scripts.tests.test_deploy_dev_systemd.DeployBuildProtocolTest", workflow)
        production = selector.plan(REPO, profile="build", changed=["backend/src/main/java/example/Production.java"])
        self.assertTrue(any(gate["id"] == "backend:build" for gate in production["gates"]))

    def test_failed_build_stops_before_the_next_graph_or_deployment(self) -> None:
        for graph, status, expected in (("backend", 51, ["backend"]), ("root", 52, ["backend", "source"])):
            with self.subTest(graph=graph):
                result, records = self.invoke(FAIL_GRAPH=graph)
                self.assertEqual(status, result.returncode, result.stderr)
                self.assertEqual(expected, [record["graph"] for record in records])
                self.assertNotIn("Missing build artifact:", result.stderr)

    def test_epoch_is_frozen_before_head_changes_between_wrappers(self) -> None:
        environment = {**os.environ, "GIT_AUTHOR_DATE": "@1792072450 +0000",
                       "GIT_COMMITTER_DATE": "@1792072450 +0000"}
        for arguments in (["init", "-q"], ["add", "."],
                          ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "initial"]):
            subprocess.run(["git", *arguments], cwd=self.root, env=environment, capture_output=True, check=True)
        result, records = self.invoke(SOURCE_DATE_EPOCH="", MOVE_HEAD="true")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(["1792072450", "1792072450"], [record["epoch"] for record in records])
        self.assertEqual("1792072550", subprocess.check_output(
            ["git", "show", "-s", "--format=%ct", "HEAD"], cwd=self.root, text=True,
        ).strip(), "Fixture must move HEAD between wrappers")

    def test_invalid_epoch_stops_before_any_wrapper(self) -> None:
        result, records = self.invoke(SOURCE_DATE_EPOCH="invalid")
        self.assertEqual(64, result.returncode, result.stderr)
        self.assertIn("SOURCE_DATE_EPOCH must be candidate Unix seconds", result.stderr)
        self.assertEqual([], records)

    def test_skip_build_keeps_artifact_checks_without_wrappers_or_epoch_validation(self) -> None:
        result, records = self.invoke("--skip-build", SOURCE_DATE_EPOCH="invalid")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("Missing build artifact:", result.stderr)
        self.assertEqual([], records)


if __name__ == "__main__":
    unittest.main()
