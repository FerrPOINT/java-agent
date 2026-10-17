"""Snapshot task packaging and independent validation entrypoint contracts."""
import json
from pathlib import Path
import re
import shlex
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DependencyInputContracts(unittest.TestCase):
    def test_native_preparation_descriptor_matches_real_shared_project_consumers(self):
        descriptor = json.loads((ROOT / 'gradle/java-preparation.json').read_text(encoding='utf-8'))
        self.assertEqual(descriptor['schema'], 'base-java-preparation/v1')
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
        for name in ('gradle/quality-inputs.gradle', 'gradle/java-preparation.json', 'backend/build.gradle', 'shared/build.gradle',
                     'cli/build.gradle', 'telegram-bot/build.gradle', 'Dockerfile', 'Dockerfile.slim'):
            self.assertIn(name, gate['inputs'])


if __name__ == '__main__':
    unittest.main()
