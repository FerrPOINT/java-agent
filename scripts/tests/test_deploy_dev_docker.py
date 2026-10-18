"""Execute workspace routing and refusals with process doubles, without a Docker daemon."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "deploy-dev-docker.sh"
BASH = (r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash"))


class DeployDevDockerTest(unittest.TestCase):
    def run_launcher(self, *, project="sdlc2", profile_project="sdlc2", initialized=True,
                     runtime="runtime.json", missing=(), arguments=()):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workspace = root / "stand with spaces"
            workspace.mkdir()
            (workspace / "workspace.ps1").write_text("# Launcher fixture\n", encoding="utf8")
            (workspace / "runtime.json").write_text("{}", encoding="utf8")
            (workspace / "workspace.local.json").write_text(json.dumps({
                "project": profile_project, "initialized": initialized, "runtime": runtime,
            }), encoding="utf8")
            bindir = root / "bin"
            bindir.mkdir()
            calls = root / "calls"
            for command in ("docker", "systemctl", "curl"):
                path = bindir / command
                path.write_bytes(("#!/usr/bin/env bash\n"
                    f"printf '{command}\\n' >> \"$DEPLOY_CALLS\"\nexit 89\n").encode())
                path.chmod(0o755)
            pwsh = bindir / "pwsh"
            pwsh.write_bytes(b'#!/usr/bin/env bash\nprintf "pwsh\\n" >> "$DEPLOY_CALLS"\nprintf "%s\\n" "$@" >> "$DEPLOY_CALLS"\nexit 23\n')
            pwsh.chmod(0o755)
            environment = os.environ | {
                "PATH": str(bindir) + os.pathsep + os.environ["PATH"],
                "SDLC_WORKSPACE_DIR": workspace.as_posix(), "SDLC_PROJECT": project,
                "SDLC_PYTHON": Path(sys.executable).as_posix(), "DEPLOY_CALLS": calls.as_posix(),
                "MSYS2_ARG_CONV_EXCL": "*",
            }
            for key in missing:
                environment.pop(key, None)
            completed = subprocess.run([str(BASH), str(SCRIPT), *arguments],
                cwd=REPO, env=environment, capture_output=True, text=True, encoding="utf8", timeout=20)
            observed = calls.read_text(encoding="utf8").splitlines() if calls.exists() else []
            return completed, observed, workspace.as_posix()

    def test_routes_only_selected_initialized_workspace_and_propagates_exit(self):
        for project in ("sdlc1", "sdlc2", "sdlc3"):
            with self.subTest(project=project):
                completed, calls, workspace = self.run_launcher(project=project, profile_project=project)
                self.assertEqual(23, completed.returncode, completed.stderr)
                self.assertEqual(["pwsh", "-NoProfile", "-File", workspace + "/workspace.ps1",
                    "Start", "-Service", "java-agent-backend"], calls)

    def test_rejects_foreign_project_before_any_command(self):
        completed, calls, _ = self.run_launcher(profile_project="sdlc1")
        self.assertNotEqual(0, completed.returncode)
        self.assertEqual([], calls)

    def test_rejects_uninitialized_and_nonboolean_state_before_any_command(self):
        for value in (False, "true", 0):
            with self.subTest(initialized=value):
                completed, calls, _ = self.run_launcher(initialized=value)
                self.assertNotEqual(0, completed.returncode)
                self.assertEqual([], calls)

    def test_rejects_missing_or_foreign_runtime_before_any_command(self):
        for value in ("missing.json", "../foreign.json", "", None):
            with self.subTest(runtime=value):
                completed, calls, _ = self.run_launcher(runtime=value)
                self.assertNotEqual(0, completed.returncode)
                self.assertEqual([], calls)

    def test_rejects_unknown_project_before_any_command(self):
        completed, calls, _ = self.run_launcher(project="sdlc-common", profile_project="sdlc-common")
        self.assertNotEqual(0, completed.returncode)
        self.assertEqual([], calls)

    def test_requires_explicit_selection_before_any_command(self):
        for key in ("SDLC_WORKSPACE_DIR", "SDLC_PROJECT"):
            with self.subTest(key=key):
                completed, calls, _ = self.run_launcher(missing=(key,))
                self.assertNotEqual(0, completed.returncode)
                self.assertEqual([], calls)

    def test_rejects_argument_overrides_before_any_command(self):
        completed, calls, _ = self.run_launcher(arguments=("-Service", "fleet-backend"))
        self.assertNotEqual(0, completed.returncode)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
