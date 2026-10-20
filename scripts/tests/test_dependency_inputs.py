"""Snapshot task packaging and independent validation entrypoint contracts."""
import json
from pathlib import Path
import re
import shlex
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DependencyInputContracts(unittest.TestCase):
    def test_windows_native_adapter_declares_only_ordinary_unit_and_backend_jar_gates(self):
        manifest = json.loads((ROOT / '.quality-gate.json').read_text(encoding='utf-8'))
        native = manifest['native_execution']
        self.assertEqual(native['adapter'], 'java-compose-v1')
        self.assertEqual(native['host_platforms'], ['win32'])
        self.assertEqual(set(native['gates']), {'backend:unit', 'backend:build', 'cli:unit', 'bot:unit', 'shared:unit'})
        gates = {gate['id']: gate for gate in manifest['gates']}
        for name, request in native['gates'].items():
            gate = gates[name]
            self.assertEqual(gate['component'], request['component'])
            self.assertFalse(gate['cacheable'])
            self.assertFalse(gate.get('requires_env'))
            self.assertFalse(gate.get('external_state'))
            self.assertLessEqual(set(gate['profiles']), {'fast', 'unit', 'build'})
            self.assertTrue(gate['tools'][0]['recheck'])
        for name in ('backend:integration', 'backend:slow', 'backend:release'):
            self.assertNotIn(name, native['gates'])
            self.assertTrue(gates[name]['requires_env'])

    def test_qa_artifact_descriptor_matches_entrypoints_names_and_notice_contract(self):
        from fnmatch import fnmatchcase
        descriptor = json.loads((ROOT / 'java-artifacts.json').read_text(encoding='utf-8'))
        self.assertEqual(descriptor['schema'], 'base-java-artifacts/v1')
        self.assertEqual(set(descriptor['applications']), {'backend', 'cli', 'bot'})
        preparation = json.loads((ROOT / 'gradle/java-preparation.json').read_text(encoding='utf-8'))
        for patterns in preparation['inputs'].values():
            self.assertFalse(any(fnmatchcase('java-artifacts.json', pattern) for pattern in patterns))
        for component, app in descriptor['applications'].items():
            script = (ROOT / app['module'] / 'build.gradle').read_text(encoding='utf-8')
            self.assertIn("attributes 'Start-Class': '" + app['start_class'] + "'", script)
            version = re.search(r"(?m)^version = '([^']+)'", script)[1]
            name = app['module']
            if component == 'backend':
                name = re.search(r"rootProject.name = '([^']+)'", (ROOT / 'backend/settings.gradle').read_text())[1]
            self.assertEqual(app['filename'], name + '-' + version + '.jar')
        notices = (ROOT / 'gradle/ferrpoint-notices.gradle').read_text(encoding='utf-8')
        for name in descriptor['notices']:
            self.assertIn("'" + name + "'", notices)
        for name, value in descriptor['manifest'].items():
            if name != 'Main-Class':
                self.assertIn("'" + value + "'", notices)

    def test_native_preparation_descriptor_matches_real_shared_project_consumers(self):
        descriptor = json.loads((ROOT / 'gradle/java-preparation.json').read_text(encoding='utf-8'))
        self.assertEqual(descriptor['schema'], 'base-java-preparation/v2')
        self.assertEqual(set(descriptor['inputs']), {'root', 'backend'})
        for name, directory in (('backend', 'backend'), ('shared', 'shared'), ('cli', 'cli'), ('bot', 'telegram-bot')):
            script = (ROOT / directory / 'build.gradle').read_text(encoding='utf-8')
            projects = re.findall(r"project\(':([a-z-]+)'\)", script)
            self.assertEqual(descriptor['dependencies'][name], projects)
        for patterns in descriptor['inputs'].values():
            self.assertFalse(any('/src/' in pattern or pattern == '**' for pattern in patterns))
        self.assertNotIn('backend/gradle/**', descriptor['inputs']['root'])
        self.assertNotIn('gradle/**', descriptor['inputs']['backend'])

    def test_every_module_can_load_the_same_script_in_source_and_flat_layouts(self):
        canonical = ROOT / 'gradle/quality-inputs.gradle'
        self.assertTrue(canonical.is_file())
        for module in ('backend', 'shared', 'cli', 'telegram-bot'):
            with self.subTest(module=module):
                script = (ROOT / module / 'build.gradle').read_text(encoding='utf-8')
                names = re.findall(r"file\(['\"]([^'\"]*quality-inputs\.gradle)['\"]\)", script)
                self.assertEqual(set(names), {'../gradle/quality-inputs.gradle', 'gradle/quality-inputs.gradle'})
                self.assertEqual((ROOT / module / '../gradle/quality-inputs.gradle').resolve(), canonical)

    def test_flat_backend_recipes_stage_the_script_before_running_the_wrapper(self):
        for name in ('Dockerfile', 'Dockerfile.slim'):
            with self.subTest(recipe=name):
                lines = (ROOT / name).read_text(encoding='utf-8').splitlines()
                copied = []
                for number, line in enumerate(lines):
                    if line.startswith('COPY '):
                        parts = shlex.split(line)
                        if parts[-1] == 'gradle/' and 'gradle/quality-inputs.gradle' in parts[1:-1]:
                            copied.append(number)
                self.assertEqual(len(copied), 1)
                first_wrapper = next(i for i, line in enumerate(lines) if 'gradlew' in line and '&&' in line)
                self.assertLess(copied[0], first_wrapper)

    def test_contract_checks_are_registered_without_a_native_builder(self):
        manifest = json.loads((ROOT / '.quality-gate.json').read_text(encoding='utf-8'))
        gate = next(g for g in manifest['gates'] if g['id'] == 'tooling:dependency-inputs-unit')
        self.assertEqual(gate['execution_slot'], 'none')
        self.assertEqual(gate['test_tier'], 'unit')
        helper = next(g for g in manifest['gates'] if g['id'] == 'tooling:gradle-helper-unit')
        self.assertEqual(helper['execution_slot'], 'none')
        self.assertIn('scripts.tests.test_dependency_inputs', gate['argv'])
        for name in ('gradle/quality-inputs.gradle', 'gradle/java-preparation.json', 'java-artifacts.json', 'backend/build.gradle', 'shared/build.gradle',
                     'cli/build.gradle', 'telegram-bot/build.gradle', 'Dockerfile', 'Dockerfile.slim'):
            self.assertIn(name, gate['inputs'])


if __name__ == '__main__':
    unittest.main()
