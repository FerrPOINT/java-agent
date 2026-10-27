"""Epoch policy at the real Make adapter boundary; no JVM or Gradle."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")


@unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required")
class MakeEpochTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        (scripts / "quality-make.sh").write_bytes((ROOT / "scripts/quality-make.sh").read_bytes().replace(b"\r\n", b"\n"))
        (scripts / "quality-gradle.sh").write_text(
            'printf "CALL\\nEPOCH=%s\\n" "$SOURCE_DATE_EPOCH"\n'
            'if [[ "${MOVE_HEAD:-}" == 1 && "${1:-}" == --backend ]]; then\n'
            ' GIT_AUTHOR_DATE="@1792072550 +0000" GIT_COMMITTER_DATE="@1792072550 +0000" '
            'git -C "$(dirname "$0")/.." -c user.name=Fixture -c user.email=fixture@example.test '
            'commit --allow-empty -qm moved\nfi\n', newline="\n")

    def invoke(self, *arguments, **overrides):
        environment = {key: value for key, value in os.environ.items() if key != "SOURCE_DATE_EPOCH"}
        result = subprocess.run([BASH, "scripts/quality-make.sh", *arguments], cwd=self.root,
                                env={**environment, **overrides}, text=True, capture_output=True)
        return result, [line.removeprefix("EPOCH=") for line in result.stdout.splitlines() if line.startswith("EPOCH=")]

    def initialize_git(self):
        environment = {**os.environ, "GIT_AUTHOR_DATE": "@1792072450 +0000", "GIT_COMMITTER_DATE": "@1792072450 +0000"}
        for arguments in (["init", "-q"], ["add", "."], ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "initial"]):
            subprocess.run(["git", *arguments], cwd=self.root, env=environment, capture_output=True, check=True)

    def test_development_uses_stable_epoch_without_a_git_checkout(self):
        result, epochs = self.invoke("jar", "all")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(epochs, ["0", "0"])

    def test_docs_and_test_commits_do_not_change_development_epoch(self):
        self.initialize_git()
        before, first = self.invoke("jar", "backend")
        (self.root / "README.md").write_text("new docs")
        (self.root / "example-test.txt").write_text("new test")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True, capture_output=True)
        subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "docs and tests"],
                       cwd=self.root, env={**os.environ, "GIT_AUTHOR_DATE": "@1792072550 +0000", "GIT_COMMITTER_DATE": "@1792072550 +0000"}, check=True, capture_output=True)
        after, second = self.invoke("jar", "backend")
        self.assertEqual((before.returncode, after.returncode), (0, 0), before.stderr + after.stderr)
        self.assertEqual((first, second), (["0"], ["0"]))

    def test_explicit_candidate_epoch_is_preserved_without_git_lookup(self):
        for flags in (("--candidate-epoch",), ("--coverage", "--candidate-epoch"), ("--candidate-epoch", "--coverage")):
            with self.subTest(flags=flags):
                result, epochs = self.invoke("jar", "all", *flags, SOURCE_DATE_EPOCH="1792072400")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(epochs, ["1792072400", "1792072400"])

    def test_implicit_candidate_epoch_is_frozen_before_both_wrappers(self):
        self.initialize_git()
        result, epochs = self.invoke("jar", "all", "--candidate-epoch", MOVE_HEAD="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(epochs, ["1792072450", "1792072450"])
        self.assertEqual(subprocess.check_output(["git", "show", "-s", "--format=%ct", "HEAD"], cwd=self.root, text=True).strip(), "1792072550")

    def test_invalid_options_fail_before_delegating(self):
        for arguments in (("jar", "all", "--typo"), ("jar", "all", "--coverage", "--coverage"),
                          ("jar", "all", "--candidate-epoch", "--candidate-epoch"),
                          ("test", "all", "--candidate-epoch"), ("jar", "cli", "--coverage", "--candidate-epoch")):
            with self.subTest(arguments=arguments):
                result, epochs = self.invoke(*arguments, SOURCE_DATE_EPOCH="1792072400")
                self.assertEqual(result.returncode, 64, result.stderr)
                self.assertEqual(epochs, [])

    @unittest.skipUnless(shutil.which("make"), "GNU Make dispatch is qualified on Linux")
    def test_public_make_uses_versioned_epoch_only_for_install_or_release(self):
        (self.root / "Makefile").write_bytes((ROOT / "Makefile").read_bytes())
        (self.root / ".version").write_text("0.1.0")
        environment = {key: value for key, value in os.environ.items() if key != "SOURCE_DATE_EPOCH"}
        result = subprocess.run(["make", "--no-print-directory", "jar", "MODULE=backend"], cwd=self.root, env=environment, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("EPOCH=0", result.stdout)
        for goal in ("install", "release"):
            result = subprocess.run(["make", "--no-print-directory", "-n", goal], cwd=self.root, env=environment, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("--candidate-epoch", result.stdout)


if __name__ == "__main__":
    unittest.main()
