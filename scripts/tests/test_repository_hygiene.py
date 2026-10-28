"""Exercise the hygiene CLI against disposable real Git repositories."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[1] / "check_repository_hygiene.py"


class RepositoryHygieneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="hygiene-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.env = os.environ.copy()
        for key in list(self.env):
            if key.startswith("GIT_"):
                del self.env[key]
        self.env.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull})
        self.git("init", "-q")
        self.git("config", "user.name", "Hygiene Test")
        self.git("config", "user.email", "hygiene@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.add("README.md")
        self.git("commit", "-qm", "test: initial snapshot")

    def git(self, *args: str) -> bytes:
        return subprocess.check_output(["git", *args], cwd=self.root, env=self.env, stderr=subprocess.PIPE)

    def add(self, name: str, contents: str = "fixture\n") -> None:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")
        self.git("add", "-f", "--", name)

    def run_check(self, *args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "-B", str(CHECKER), *args],
            cwd=cwd or self.root,
            env=self.env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )

    def test_force_added_ignored_artifacts_fail(self) -> None:
        self.add(".gitignore", "__pycache__/\nnode_modules/\n")
        forbidden = [
            "scripts/__pycache__/one.pyc",
            "vendor/node_modules/pkg/index.js",
            "frontend/.pnpm-store/v3/file",
            ".venv/bin/python",
            ".local/source.py",
            ".worktree/repo/main.py",
            ".worktrees/repo/main.py",
            "worktree/repo/main.py",
            "worktrees/repo/main.py",
            "backend/target/a",
            "backend/target-cache/a",
            "backend/target-cache188/a",
            "backend/target-stage/a",
            "backend/target-stable/a",
            "loose.pyo",
            "frontend/tsconfig.tsbuildinfo",
            "папка с пробелом/кэш.pyc",
        ]
        for name in forbidden:
            self.add(name, "SYNTHETIC_CONTENT_MUST_NOT_BE_PRINTED")
        result = self.run_check("--index")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual({f["path"] for f in json.loads(result.stdout)["findings"]}, set(forbidden))
        self.assertNotIn("SYNTHETIC_CONTENT_MUST_NOT_BE_PRINTED", result.stdout + result.stderr)

    def test_untracked_caches_and_intentional_assets_pass(self) -> None:
        for name in [
            "gradle/wrapper/gradle-wrapper.jar",
            "api/openapi.json",
            "frontend/src/generated/api.ts",
            "docs/screenshots/test.png",
            "docs/assets/screens/qa/run.log",
            "handoff/workspace-recovery/payload.source",
            "plugins/dashboard/dist/app.js",
            "src/workspace-navigation.tsx",
            "src/targeting.py",
            "src/target-cache-policy.py",
            "docs/worktrees.md",
        ]:
            self.add(name)
        (self.root / "__pycache__").mkdir()
        (self.root / "__pycache__/untracked.pyc").write_bytes(b"cache")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["ok"])

    def test_commit_and_index_are_distinct_and_staged_removal_passes(self) -> None:
        self.add("bad.pyc")
        self.git("commit", "-qm", "test: artifact")
        self.git("rm", "--cached", "bad.pyc")
        self.assertTrue((self.root / "bad.pyc").exists())
        self.assertEqual(self.run_check("--index").returncode, 0)
        self.assertEqual(self.run_check("--ref", "HEAD").returncode, 1)
        self.git("commit", "-qm", "test: remove artifact")
        self.assertEqual(self.run_check("--ref", "HEAD").returncode, 0)

    def test_no_mutation_and_nested_working_directory(self) -> None:
        self.add("nested/dirty.txt")
        (self.root / "nested/dirty.txt").write_text("unstaged", encoding="utf-8")
        before = (
            self.git("rev-parse", "HEAD"),
            self.git("diff", "--binary"),
            self.git("diff", "--cached", "--binary"),
            (self.root / ".git/index").read_bytes(),
        )
        for arguments in [("--index",), ("--ref", "HEAD")]:
            result = self.run_check(*arguments, cwd=self.root / "nested")
            self.assertEqual(result.returncode, 0, result.stderr)
        after = (
            self.git("rev-parse", "HEAD"),
            self.git("diff", "--binary"),
            self.git("diff", "--cached", "--binary"),
            (self.root / ".git/index").read_bytes(),
        )
        self.assertEqual(before, after)

    def test_invalid_ref_and_nonrepository_fail_closed(self) -> None:
        result = self.run_check("--ref", "missing-ref")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("missing-ref", result.stderr)
        self.assertEqual(self.run_check("--ref", "--help").returncode, 2)
        with tempfile.TemporaryDirectory(prefix="hygiene-outside-") as outside:
            self.assertEqual(self.run_check(cwd=Path(outside)).returncode, 2)

    def test_nested_directory_checks_paths_outside_it(self) -> None:
        self.add("nested/file.txt")
        self.add("outside.pyc")
        result = self.run_check("--index", cwd=self.root / "nested")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(json.loads(result.stdout)["findings"][0]["path"], "outside.pyc")

    def test_unmerged_index_fails_closed(self) -> None:
        oid = self.git("rev-parse", "HEAD:README.md").strip().decode("ascii")
        self.git("update-index", "--force-remove", "README.md")
        payload = f"100644 {oid} 1\tREADME.md\n100644 {oid} 2\tREADME.md\n"
        subprocess.run(
            ["git", "update-index", "--index-info"], cwd=self.root, env=self.env, input=payload.encode(), check=True
        )
        self.assertEqual(self.run_check("--index").returncode, 2)
        self.assertEqual(self.run_check("--ref", "HEAD").returncode, 0)

    def test_real_hooks_reject_commit_and_push_of_other_ref(self) -> None:
        source = CHECKER.parent.parent
        for name in [
            "scripts/check_repository_hygiene.py",
            "scripts/check_repository_hygiene_push.sh",
            ".githooks/pre-commit",
            ".githooks/pre-push",
        ]:
            destination = self.root / name
            destination.parent.mkdir(exist_ok=True)
            shutil.copyfile(source / name, destination)
            destination.chmod(0o755)
        self.add("bad.pyc")
        self.git("commit", "-qm", "test: legacy bad ref")
        self.git("branch", "legacy-artifact")
        self.git("rm", "--cached", "bad.pyc")
        self.git("commit", "-qm", "test: clean current HEAD")
        self.git("config", "core.hooksPath", ".githooks")
        self.env["PYTHON"] = sys.executable
        self.add("another.pyc")
        result = subprocess.run(
            ["git", "commit", "-qm", "test: blocked"], cwd=self.root, env=self.env, capture_output=True, check=False
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"python_bytecode", result.stdout + result.stderr)
        self.git("rm", "--cached", "another.pyc")
        remote = self.root / "remote.git"
        self.git("init", "--bare", "-q", str(remote))
        self.git("push", "-q", str(remote), "HEAD:refs/heads/clean")
        result = subprocess.run(
            ["git", "push", str(remote), "legacy-artifact"],
            cwd=self.root,
            env=self.env,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"python_bytecode", result.stdout + result.stderr)
        self.assertEqual(self.git("ls-remote", str(remote), "refs/heads/legacy-artifact"), b"")
        self.git("push", "-q", str(remote), ":refs/heads/clean")


if __name__ == "__main__":
    unittest.main()
