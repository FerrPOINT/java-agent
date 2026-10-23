"""Known helper test edits cannot select application producers."""
import shlex
import unittest
from unittest.mock import patch
from pathlib import Path
from scripts.vendor import base_quality_selection as selection

ROOT = Path(__file__).resolve().parents[2]
TEST_GATES = {'test_check_docs_standard': 'tooling:docs-unit', 'test_verify_readme': 'tooling:docs-unit', 'test_check_default_settings_reference': 'tooling:defaults-unit', 'test_parity_dashboard': 'tooling:selection-unit', 'test_select_java_modules': 'tooling:selection-unit', 'test_tooling_ownership': 'tooling:selection-unit'}


class PythonToolingOwnershipTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        head = selection.git(ROOT, 'rev-parse', 'HEAD')
        def current_head(repository, *arguments):
            if repository != ROOT or arguments != ('rev-parse', 'HEAD'):
                raise AssertionError('This pure path-selection fixture only supplies the current HEAD')
            return head
        cls.head_reader = patch.object(selection, 'git', side_effect=current_head)
        cls.addClassCleanup(cls.head_reader.stop)
        cls.head_reader.start()

    def test_known_tests_select_exact_lightweight_gate(self):
        manifest = selection.load(ROOT)
        for profile in ('fast', 'unit', 'build'):
            for name, expected in TEST_GATES.items():
                with self.subTest(profile=profile, name=name):
                    candidate = selection.plan(ROOT, profile=profile,
                        changed=['scripts/tests/' + name + '.py'], manifest=manifest)
                    self.assertEqual(candidate['components'], ['tooling'])
                    self.assertEqual([g['id'] for g in candidate['gates']], [expected])
                    gate = candidate['gates'][0]
                    self.assertEqual(gate['argv'][0], '{python}')
                    self.assertFalse(gate.get('kind') == 'artifact')
                    self.assertIn('scripts.tests.' + name, gate['argv'])

    def test_unknown_scripts_and_application_sources_keep_consumers(self):
        manifest = selection.load(ROOT)
        for path in ('scripts/new_generator.py', 'scripts/build.py'):
            with self.subTest(path=path):
                candidate = selection.plan(ROOT, profile='build', changed=[path], manifest=manifest)
                self.assertTrue(set(manifest['components']) <= set(candidate['components']))
                self.assertTrue(any(g.get('kind') == 'artifact' for g in candidate['gates']))
        candidate = selection.plan(ROOT, profile='build', changed=['backend/src/main/java/com/azhukov/agent/client/NoOpModelClient.java'], manifest=manifest)
        self.assertIn('backend', candidate['components'])
        self.assertTrue(any(g.get('kind') == 'artifact' for g in candidate['gates']))
        if 'frontend' in manifest['components']:
            candidate = selection.plan(ROOT, profile='build', changed=['frontend/src/product.tsx'], manifest=manifest)
            self.assertIn('frontend', candidate['components'])
            self.assertTrue(any(g.get('kind') == 'artifact' for g in candidate['gates']))

    def test_ci_runs_each_selected_module_once(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        commands = [shlex.split(line.strip().removeprefix('run: ')) for line in ci.splitlines()
                    if line.strip().removeprefix('run: ').startswith(('python3 -m unittest ', 'python3 -B -m unittest '))]
        for name in TEST_GATES:
            with self.subTest(module=name):
                self.assertEqual(sum(c.count('scripts.tests.' + name) for c in commands), 1)


if __name__ == '__main__':
    unittest.main()
