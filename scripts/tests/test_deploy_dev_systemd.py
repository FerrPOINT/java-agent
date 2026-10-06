"""Regression tests for the host systemd deployment helper."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "deploy-dev-systemd.sh"


class DeployDevSystemdTest(unittest.TestCase):
    def test_skip_build_publishes_one_release_and_verifies_both_services(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            install_dir = root / "install"
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

            backend = REPO / "backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
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


if __name__ == "__main__":
    unittest.main()
