import unittest
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from scripts.select_java_modules import groups


class JavaModuleSelectionTests(unittest.TestCase):
    def test_public_selector_emits_only_affected_root_tasks_and_shared_consumers(self):
        script = Path(__file__).resolve().parents[1] / 'select_java_modules.py'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            copied = root / 'scripts/select_java_modules.py'
            copied.parent.mkdir()
            copied.write_bytes(script.read_bytes())
            vendor = root / 'scripts/vendor/base_quality_selection.py'
            vendor.parent.mkdir()
            cases = [(['cli'], ':cli:test', 'false'),
                     (['bot'], ':telegram-bot:test', 'false'),
                     (['shared'], ':telegram-bot:test :cli:test :shared:test', 'true'),
                     (['backend'], '', 'true'), (['docs'], '', 'false'),
                     (['cli', 'bot', 'cli'], ':telegram-bot:test :cli:test', 'false')]
            for selected, tasks, backend in cases:
                with self.subTest(components=selected):
                    vendor.write_text('def ci_components(*args): return ' + repr(selected) + '\n'
                                      'def ci_outputs(*args, **kwargs): return {"backend_build":"true"}\n')
                    environment = dict(os.environ)
                    environment.pop('GITHUB_OUTPUT', None)
                    result = subprocess.run([sys.executable, '-B', str(copied)], env=environment,
                                            capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    values = dict(line.split('=', 1) for line in result.stdout.splitlines())
                    self.assertEqual(values.get('java_root_tasks'), tasks)
                    self.assertEqual(values['java_backend'], backend)
                    self.assertEqual(values['java_root'], str(bool(tasks)).lower())

    def test_cli_and_bot_do_not_run_backend_and_shared_propagates(self):
        for component in ("cli", "bot"):
            self.assertEqual(groups([component]), {"java_backend": False, "java_root": True})
        self.assertEqual(groups(["backend"]), {"java_backend": True, "java_root": False})
        self.assertEqual(groups(["shared"]), {"java_backend": True, "java_root": True})
        self.assertEqual(groups(["docs"]), {"java_backend": False, "java_root": False})
        self.assertEqual(groups(["tooling"]), {"java_backend": False, "java_root": False})
