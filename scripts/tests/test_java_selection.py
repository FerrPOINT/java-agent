"""Pure path/command selection; never starts Git, Gradle or an application."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from scripts.select_java_modules import groups, root_unit_tasks
from scripts.vendor import base_quality_selection as selection

ROOT = Path(__file__).resolve().parents[2]


class JavaSelectionTests(unittest.TestCase):
    def plan(self, paths, profile='build'):
        return selection.plan(ROOT, profile=profile, changed=paths)

    def test_docs_and_no_changes_select_no_application_producer(self):
        self.assertEqual(self.plan([])['gates'], [])
        for path in ('docs/TESTING.md', 'README.md'):
            with self.subTest(path=path):
                value = self.plan([path])
                self.assertEqual(value['components'], ['docs'])
                self.assertFalse(any(g.get('kind') == 'artifact' for g in value['gates']))

    def test_each_main_component_selects_its_own_producer(self):
        for prefix, component in (('backend', 'backend'), ('telegram-bot', 'bot'), ('cli', 'cli')):
            value = self.plan([prefix + '/src/main/java/example/Changed.java'])
            self.assertEqual(value['components'], [component])
            self.assertEqual([g['id'] for g in value['gates'] if g.get('kind') == 'artifact'],
                             [component + ':build'])

    def test_each_test_only_change_keeps_production_artifacts(self):
        for prefix, component in (('backend', 'backend'), ('telegram-bot', 'bot'), ('cli', 'cli'), ('shared', 'shared')):
            value = self.plan([prefix + '/src/test/java/example/ChangedTest.java'])
            self.assertEqual(value['components'], [component])
            self.assertFalse(any(g.get('kind') == 'artifact' for g in value['gates']))
            self.assertIn(component + ':unit', [g['id'] for g in value['gates']])

    def test_shared_main_and_unknown_paths_keep_real_consumers(self):
        value = self.plan(['shared/src/main/java/example/Dto.java'])
        self.assertTrue({'backend','bot','cli','shared'} <= set(value['components']))
        for path in ('new-unclassified-build-input.txt', 'scripts/new_generator.py'):
            value = self.plan([path])
            self.assertEqual(set(value['components']), set(selection.load(ROOT)['components']))
            self.assertEqual(len([g for g in value['gates'] if g.get('kind') == 'artifact']), 3)

    def test_wrapper_and_preparation_changes_expand_consumers(self):
        value = self.plan(['gradle/wrapper/gradle-wrapper.properties'])
        self.assertTrue({'backend','bot','cli','shared','tooling'} <= set(value['components']))

    def test_typed_ci_root_tasks_do_not_add_backend_or_other_client(self):
        self.assertEqual(root_unit_tasks(['cli']), ':cli:test')
        self.assertEqual(root_unit_tasks(['bot']), ':telegram-bot:test')
        self.assertEqual(root_unit_tasks(['docs']), '')
        self.assertEqual(groups(['backend']), {'java_backend': True, 'java_root': False})
        self.assertEqual(root_unit_tasks(['shared']), ':shared:test')
        self.assertEqual(groups(['shared']), {'java_backend': False, 'java_root': True})
        shared_main = self.plan(['shared/src/main/java/example/Dto.java'])['components']
        self.assertTrue(groups(shared_main)['java_backend'])
        self.assertEqual(root_unit_tasks(shared_main), ':telegram-bot:test :cli:test :shared:test')

    def test_ci_test_only_never_publishes_a_new_backend_candidate(self):
        with patch.object(selection, 'git', return_value='backend/src/test/java/example/ChangedTest.java\n'):
            values = selection.ci_outputs('pull_request','a'*40,'b'*40,repo=ROOT)
        self.assertEqual(values['backend_build'], 'false')

    def test_manual_tiers_never_enter_ordinary_profiles(self):
        manifest = selection.load(ROOT)
        for gate in manifest['gates']:
            if set(gate['profiles']) & {'fast','unit','build'}:
                self.assertNotIn(gate.get('test_tier'), ('integration','slow','live'))

    def test_inventory_changes_select_validation_before_java_work(self):
        value = self.plan(['gradle/test-lanes.json'], profile='unit')
        self.assertIn('tooling:unit', [g['id'] for g in value['gates']])

    def test_actual_source_inventory_is_complete_before_compilation(self):
        from scripts.check_test_lanes import validate
        counts = validate(ROOT)
        self.assertEqual(set(counts), {'unit', 'integration', 'slow', 'live'})
        self.assertGreater(counts['unit'], 0)


if __name__ == '__main__':
    unittest.main()
