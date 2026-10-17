"""Exercise deployment ordering with command stubs, without Docker or services."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))


@unittest.skipUnless(BASH and Path(BASH).is_file(), "native Bash is required")
class DeployDevDockerTests(unittest.TestCase):
    def invoke(self, mode="ok"):
        temporary = tempfile.TemporaryDirectory(prefix="java deploy fixture ")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        script = root / "scripts/deploy-dev-docker.sh"
        script.parent.mkdir()
        script.write_bytes((ROOT / "scripts/deploy-dev-docker.sh").read_bytes().replace(b"\r\n", b"\n"))
        calls = root / "calls.txt"
        stub = root / "commands.sh"
        stub.write_text(r'''
record() { printf '%s\n' "$@" >> "$DEPLOY_CALLS"; printf '\036\n' >> "$DEPLOY_CALLS"; }
docker() {
    record docker "$@"
    if [ "$1" = inspect ]; then
        [ "$DEPLOY_MODE" != missing-db ] || return 1
        [ "${2:-}" != -f ] || printf 'healthy\n'
        return 0
    fi
    if [ "$1" = exec ]; then
        [ "$3" != test ]
        return
    fi
    if [ "$1" = compose ]; then
        case " $* " in
            *' build backend bot '*) [ "$DEPLOY_MODE" != build-fails ]; return ;;
            *' run --rm --no-deps volume-init '*) [ "$DEPLOY_MODE" != init-fails ]; return ;;
            *' ps -q backend '*) printf 'backend-fixture\n' ;;
            *' ps -q bot '*) printf 'bot-fixture\n' ;;
        esac
    fi
    return 0
}
systemctl() { record systemctl "$@"; }
curl() { record curl "$@"; }
sleep() { return 0; }
''', newline="\n")
        environment = {**os.environ, "BASH_ENV": stub.as_posix(),
                       "DEPLOY_CALLS": calls.as_posix(), "DEPLOY_MODE": mode,
                       "COMPOSE_PROJECT_NAME": "operator-selected-project"}
        result = subprocess.run([BASH, script.as_posix()], env=environment,
                                capture_output=True, text=True, timeout=15, check=False)
        rows = [row.strip().splitlines() for row in calls.read_text().split("\x1e") if row.strip()]
        return result, rows

    def test_build_once_then_initialize_then_stop_and_start_without_rebuild(self):
        result, rows = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        build = next(i for i, row in enumerate(rows) if row[-3:] == ["build", "backend", "bot"])
        # Compare the four terminal argv rather than the project's temporary path.
        initialize = [i for i, row in enumerate(rows) if row[-4:] == ["run", "--rm", "--no-deps", "volume-init"]]
        self.assertEqual(len(initialize), 1)
        stopped = next(i for i, row in enumerate(rows) if row[:2] == ["systemctl", "stop"])
        started = next(i for i, row in enumerate(rows) if row[-4:] == ["up", "-d", "--no-build", "--remove-orphans"])
        self.assertLess(build, initialize[0])
        self.assertLess(initialize[0], stopped)
        self.assertLess(stopped, started)
        self.assertEqual(sum("build" in row for row in rows), 1)
        self.assertFalse(any(row[:2] in (["docker", "run"], ["docker", "create"]) for row in rows))
        self.assertFalse(any("java_agent_state:/volume" in argument for row in rows for argument in row))

    def test_build_or_init_failure_never_stops_services_or_starts_runtime(self):
        for mode in ("build-fails", "init-fails"):
            with self.subTest(mode=mode):
                result, rows = self.invoke(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(row[0] == "systemctl" or "up" in row for row in rows))
                if mode == "build-fails":
                    self.assertFalse(any("volume-init" in row for row in rows))

    def test_missing_database_refuses_before_network_build_or_volume_work(self):
        result, rows = self.invoke("missing-db")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refusing to create a replacement database", result.stderr)
        self.assertEqual(rows, [["docker", "inspect", "java-agent-pg"]])
