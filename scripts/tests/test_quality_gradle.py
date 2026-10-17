"""Exercise the public helper boundary without starting Gradle or a JVM."""

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
        for directory, version in ((self.root, "9.6.1"), (self.root / "backend", "9.8.0")):
            directory.mkdir(exist_ok=True)
            (directory / "gradlew").write_text(
                'printf "%s\\n" "EPOCH=$SOURCE_DATE_EPOCH" "DIRECTORY=$PWD" "$@"\n', newline="\n")
            wrapper = directory / "gradle/wrapper/gradle-wrapper.properties"
            wrapper.parent.mkdir(parents=True)
            wrapper.write_text("distributionUrl=https\\://services.gradle.org/distributions/gradle-" + version + "-bin.zip\n")
        self.jdk = self.root / "jdk"
        self.jdk.mkdir()
        (self.jdk / "release").write_text('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="25.0.4.1"\nOS_NAME="Linux"\nOS_ARCH="x86_64"\n')

    def invoke(self, *arguments, epoch="1792072450"):
        environment = {**os.environ, "SOURCE_DATE_EPOCH": epoch, "JAVA_HOME": self.jdk.as_posix()}
        # Git Bash translates its standard JAVA_HOME environment to POSIX itself.
        result = subprocess.run([BASH, self.helper.as_posix(), *arguments], env=environment,
                                check=False, capture_output=True, text=True)
        return result, result.stdout.splitlines()

    def test_native_ordinary_graphs_enable_strict_cache_without_losing_arguments(self):
        for arguments in (("--backend", ":test", ":bootJar", "--no-daemon", "--max-workers=2"),
                          (":telegram-bot:test", ":cli:test", ":shared:test", ":telegram-bot:bootJar", ":cli:bootJar", "--offline")):
            with self.subTest(arguments=arguments):
                result, lines = self.invoke(*arguments)
                self.assertEqual(result.returncode, 0, result.stderr)
                actual = lines[2:]
                expected = list(arguments[1:] if arguments[0] == "--backend" else arguments)
                self.assertEqual(actual, expected + ["--configuration-cache", "--configuration-cache-problems=fail"])
                self.assertEqual(lines[0], "EPOCH=1792072450")
                self.assertEqual(lines[1].endswith("/backend"), arguments[0] == "--backend")

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
