"""Known helper test edits cannot select application producers."""
import shlex
import json
import unittest
from unittest.mock import patch
from pathlib import Path
from scripts.vendor import base_quality_selection as selection

ROOT = Path(__file__).resolve().parents[2]
TEST_GATES = {'test_check_docs_standard': 'tooling:docs-unit', 'test_verify_readme': 'tooling:docs-unit', 'test_check_default_settings_reference': 'tooling:defaults-unit', 'test_parity_dashboard': 'tooling:selection-unit', 'test_select_java_modules': 'tooling:selection-unit', 'test_tooling_ownership': 'tooling:selection-unit'}


class SelectedClientManifestTests(unittest.TestCase):
    def test_bot_build_declares_the_actual_packaged_jar(self):
        manifest=selection.load(ROOT)
        gates={gate['id']:gate for gate in manifest['gates']}
        descriptor=json.loads((ROOT/'java-artifacts.json').read_bytes())['applications']['bot']
        gate=gates['bot:build']
        self.assertEqual(gate['outputs'],[descriptor['module']+'/build/libs/'+descriptor['filename']])
        self.assertEqual(gate['argv'],['bash','scripts/quality-gradle.sh','--quality-root',
            '--no-daemon','--max-workers=2',':telegram-bot:bootJar'])
        self.assertEqual(manifest['native_execution']['gates']['bot:build'],{'component':'bot','role':'build'})
        self.assertTrue(gate['cacheable'])

    def test_bot_and_shared_opt_in_without_promoting_backend(self):
        gates={gate['id']:gate for gate in selection.load(ROOT)['gates']}
        for name in ['bot:unit','bot:build','shared:unit']:
            self.assertTrue(gates[name]['cacheable'],name)
        self.assertFalse(gates['backend:unit']['cacheable'])
        self.assertFalse(gates['backend:build']['cacheable'])

    def test_native_selected_graph_has_only_bot_shared_units_and_one_jar(self):
        with patch.object(selection,'git',return_value='a'*40):
            plan=selection.plan(ROOT,'build',components=['bot','shared'],execution_platform='win32',
                native_docker_context='desktop-linux')
        self.assertEqual({gate['id'] for gate in plan['gates']},{'bot:unit','bot:build','shared:unit'})
        self.assertEqual(plan['native_execution']['artifact_components'],['bot'])
        self.assertEqual(set(plan['native_execution']['components']),{'bot','shared'})

    def test_bot_artifact_inputs_exclude_sibling_application_and_test_sources(self):
        gate=next(gate for gate in selection.load(ROOT)['gates'] if gate['id']=='bot:build')
        self.assertTrue(selection.matches('telegram-bot/src/main/java/Bot.java',gate['inputs']))
        self.assertTrue(selection.matches('shared/src/main/java/Dto.java',gate['inputs']))
        for path in ['cli/src/main/java/Cli.java','backend/src/main/java/Api.java',
                     'telegram-bot/src/test/java/BotTest.java','docs/guide.md']:
            self.assertFalse(selection.matches(path,gate['inputs']),path)


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
