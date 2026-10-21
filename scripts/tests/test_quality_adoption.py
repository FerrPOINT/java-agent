"""Fast source/selection contracts; native Gradle API remains a manual gate."""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import unittest
from unittest.mock import patch

from scripts import check_test_lanes as lanes
from scripts import ci_quality_plan as ci
from scripts.vendor import base_quality_selection as selector

ROOT = Path(__file__).resolve().parents[2]


class QualityAdoptionTests(unittest.TestCase):
    def plan(self, *paths):
        with patch.object(selector, 'git', return_value='a' * 40):
            return selector.plan(ROOT, profile='build', changed=list(paths))

    def test_docs_and_empty_change_do_not_prepare_a_jvm(self):
        for paths in ((), ('docs/11-test-coverage-plan.md',)):
            selected = ci.tasks_for_plan(self.plan(*paths))
            self.assertEqual(selected, {'backend': [], 'clients': []})

    def test_windows_clients_native_plan_uses_one_existing_owner_and_only_selected_jar(self):
        for component in ('cli', 'bot'):
            with self.subTest(component=component), patch.object(selector, 'git', return_value='a' * 40):
                planned = selector.plan(ROOT, profile='build', components=[component],
                    execution_platform='win32', native_docker_context='fixture')
                native = planned['native_execution']
                self.assertEqual(native['adapter'], 'java-compose-v1')
                self.assertEqual(native['components'], [component])
                self.assertEqual(native['artifact_components'], [component])
                self.assertEqual(set(native['gates']), {component + ':unit', component + ':build'})
                self.assertTrue(all(gate['argv'][2] == '--quality-root' for gate in planned['gates']))
                self.assertTrue(all(gate['cacheable'] is (component == 'cli') for gate in planned['gates']))

    def test_cli_native_consent_preserves_one_uncached_linux_unit_invocation(self):
        with patch.object(selector, 'git', return_value='a' * 40):
            planned = selector.plan(ROOT, profile='unit', components=['cli', 'shared'], execution_platform='linux')
        self.assertNotIn('native_execution', planned)
        self.assertEqual(len(planned['gates']), 1)
        gate = planned['gates'][0]
        self.assertFalse(gate['cacheable'])
        self.assertEqual(set(gate['member_gates']), {'cli:unit', 'shared:unit'})
        self.assertEqual(gate['argv'][-2:], [':cli:test', ':shared:test'])

    def test_manual_java_lanes_do_not_dispatch_automatic_native_owner(self):
        for profile in ('integration', 'slow', 'release'):
            with self.subTest(profile=profile), patch.object(selector, 'git', return_value='a' * 40):
                planned = selector.plan(ROOT, profile=profile, components=['cli'], execution_platform='win32')
                self.assertNotIn('native_execution', planned)

    def test_reviewed_tooling_is_scoped_and_unknown_scripts_expand_the_product(self):
        selected=self.plan('scripts/check_default_settings_reference.py')
        self.assertEqual(selected['components'],['tooling'])
        gate=next(g for g in selected['gates'] if g['id']=='tooling:partition-source-unit')
        self.assertIn('scripts.tests.test_check_default_settings_reference',gate['argv'])
        unknown=ci.tasks_for_plan(self.plan('scripts/unreviewed_helper.py'))
        self.assertIn(':bootJar',unknown['backend'])
        self.assertTrue({':cli:bootJar',':telegram-bot:bootJar'}<=set(unknown['clients']))

    def test_cli_bot_backend_build_only_their_producers(self):
        for module,task,group in (('cli', ':cli:bootJar', 'clients'),
                                  ('telegram-bot', ':telegram-bot:bootJar', 'clients'),
                                  ('backend', ':bootJar', 'backend')):
            with self.subTest(module=module):
                selected = ci.tasks_for_plan(self.plan(module + '/src/main/java/example/Rule.java'))
                self.assertIn(task, selected[group])
                other = 'backend' if group == 'clients' else 'clients'
                self.assertEqual(selected[other], [])
                self.assertFalse(any('bootJar' in item and item != task for item in selected[group]))

    def test_test_only_runs_units_without_application_jar(self):
        for module in ('backend', 'cli', 'telegram-bot', 'shared'):
            with self.subTest(module=module):
                selected = ci.tasks_for_plan(self.plan(module + '/src/test/java/example/RuleTest.java'))
                tasks = selected['backend'] + selected['clients']
                self.assertTrue(tasks)
                self.assertFalse(any('bootJar' in task for task in tasks))
                if module == 'shared':
                    self.assertEqual(tasks, [':shared:test'])

    def test_shared_main_and_unknown_input_keep_real_consumers(self):
        for path in ('shared/src/main/java/example/Dto.java', 'new-input.fixture'):
            selected = ci.tasks_for_plan(self.plan(path))
            self.assertIn(':bootJar', selected['backend'])
            self.assertTrue({':cli:bootJar', ':telegram-bot:bootJar'} <= set(selected['clients']))
            for tasks in selected.values():
                self.assertEqual(len(tasks), len(set(tasks)))

    def test_wrong_comparison_fails_before_task_selection(self):
        with patch.object(selector, 'git', return_value='b' * 40):
            with self.assertRaisesRegex(ValueError, 'checkout'):
                ci.ci_plan(ROOT, 'pull_request', 'c' * 40, 'a' * 40)
        for event,base,head in (('workflow_dispatch','a'*40,'b'*40),
                                ('pull_request','0'*40,'b'*40),('push','missing','b'*40)):
            with patch.object(selector, 'git', return_value=head):
                with self.assertRaises(ValueError): ci.ci_plan(ROOT,event,base,head)

    def test_ci_task_translation_refuses_manual_or_unexpected_commands(self):
        for tier,task in (('integration',':cli:integrationTest'),('artifact',':backend:bootRun')):
            with self.assertRaises(ValueError):
                ci.tasks_for_plan({'gates':[{'argv':['bash','scripts/quality-gradle.sh',task],'test_tier':tier}]})

    def test_exact_canonical_selector_packet(self):
        receipt=json.loads((ROOT/'scripts/vendor/base-quality-tooling.json').read_text(encoding='utf-8'))
        pin=(ROOT/'.quality-tooling-revision').read_text(encoding='utf-8').strip()
        self.assertEqual(pin,'5acb67507558261fe35c99a94534696897639e9c')
        self.assertEqual(receipt['revision'],pin)
        self.assertEqual(receipt['sha256'],hashlib.sha256((ROOT/'scripts/vendor/base_quality_selection.py').read_bytes()).hexdigest())
        self.assertEqual(set(receipt),{'schema','repository','revision','source','sha256'})

    def test_actual_ci_python_launchers_reject_untyped_or_injected_tasks(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8')
        blocks=re.findall(r"python3 - <<'PY'\n(.*?)^          PY$",workflow,re.M|re.S)
        launchers=[block for block in blocks if 'SELECTED_TASKS' in block]
        self.assertEqual(len(launchers),2)
        for block in launchers:
            code='\n'.join(line[10:] for line in block.splitlines())
            backend='--backend' in code
            tasks=[':bootJar'] if backend else [':cli:test',':cli:bootJar']
            with patch.dict(os.environ,{'SELECTED_TASKS':json.dumps(tasks)}), patch('subprocess.run') as run:
                exec(compile(code,'<actual-ci-launcher>','exec'),{})
                self.assertEqual(run.call_args.args[0],['bash','scripts/quality-gradle.sh',
                    '--backend' if backend else '--quality-root',*tasks,'--no-daemon','--max-workers=2'])
                self.assertTrue(run.call_args.kwargs['check'])
            for value in ([],tasks+tasks,[':cli:bootRun'],['; echo injected'],{},[None]):
                with patch.dict(os.environ,{'SELECTED_TASKS':json.dumps(value)}), patch('subprocess.run') as run:
                    with self.assertRaises(SystemExit):exec(compile(code,'<actual-ci-launcher>','exec'),{})
                    run.assert_not_called()

    def test_complete_actual_source_membership_has_no_copied_peer_timings(self):
        inventory=json.loads((ROOT/'gradle/test-lanes.json').read_text(encoding='utf-8'))
        layout=lanes.compilation_layout(ROOT)
        self.assertEqual(sum(len(paths) for groups in layout.values() for paths in groups.values()),
                         len(inventory['files'])+len(inventory['support']))
        self.assertGreater(lanes.validate(ROOT)['unit'],0)
        self.assertNotIn('measured_slow',inventory)
        token='backend/src/test/java/com/azhukov/agent/gateway/telegram/TelegramLongPollingServiceTokenLogTest.java'
        self.assertEqual(inventory['files'][token],'integration')
        self.assertEqual(inventory['support']['backend/src/test/java/com/azhukov/agent/tools/browser/CdpTestWebSocket.java'],'shared')

    def test_partition_preserves_resources_processors_and_unit_before_jar(self):
        topology=(ROOT/'gradle/test-topology.gradle').read_text(encoding='utf-8')
        membership=(ROOT/'gradle/test-membership.gradle').read_text(encoding='utf-8')
        self.assertIn("manualSources.runtimeClasspath += files(tasks.named('processTestResources'))",topology)
        self.assertIn("'AnnotationProcessor'",topology)
        self.assertIn("tasks.named('bootJar') { dependsOn tasks.named('test') }",topology)
        self.assertIn("configureSources(sourceSets.test, 'unit')",membership)
        self.assertIn("configureSources(manualSources, 'manual')",membership)
        for module in ('backend','telegram-bot','cli','shared'):
            body=(ROOT/module/'build.gradle').read_text(encoding='utf-8')
            self.assertIn("file('../gradle/test-lanes.gradle')",body)
            self.assertNotIn('finalizedBy jacocoTestReport',body)
        backend=(ROOT/'backend/build.gradle').read_text(encoding='utf-8')
        self.assertIn('maxParallelForks = 1',backend)
        self.assertIn('forkEvery = 750',backend)
        self.assertIn('minimum = 0.7938',backend)
        self.assertNotIn("testImplementation 'org.testcontainers:",backend)
        for name in lanes.compilation_layout(ROOT)['backend']['unit']:
            self.assertNotIn('org.testcontainers', (ROOT/name).read_text(encoding='utf-8'))

    def test_manual_coverage_owns_complete_report_graph(self):
        for module in ('backend','telegram-bot'):
            body=(ROOT/module/'build.gradle').read_text(encoding='utf-8')
            self.assertIn('dependsOn test, integrationTest, slowTest',body)
            self.assertIn('executionData(test, integrationTest, slowTest)',body)
        self.assertNotIn('check.dependsOn jacocoTestCoverageVerification',(ROOT/'backend/build.gradle').read_text(encoding='utf-8'))

    def test_only_cli_has_explicit_native_consent_and_compiler_cache_stays_disabled(self):
        manifest=selector.load(ROOT)
        self.assertEqual(manifest['native_execution']['adapter'],'java-compose-v1')
        self.assertEqual(set(manifest['native_execution']['gates']),
                         {'backend:unit','backend:build','cli:unit','cli:build',
                          'bot:unit','bot:build','shared:unit'})
        for gate in manifest['gates']:
            if 'scripts/quality-gradle.sh' in gate['argv']:
                self.assertIs(gate['cacheable'], gate['id'] in ('cli:unit', 'cli:build'))
        helper=(ROOT/'scripts/quality-gradle.sh').read_text(encoding='utf-8')
        self.assertEqual(helper.count('qualified_wrappers=()'),2)
        self.assertIn('Java3 compiler cache requires native peer qualification',helper)

    def test_both_pinned_wrappers_and_default_full_graph_remain(self):
        for path,version in (('gradle/wrapper/gradle-wrapper.properties','9.6.1'),
                             ('backend/gradle/wrapper/gradle-wrapper.properties','9.8.0')):
            self.assertIn('gradle-'+version+'-bin.zip',(ROOT/path).read_text(encoding='utf-8'))
        self.assertIn("include 'shared', 'backend', 'telegram-bot', 'cli'",(ROOT/'settings.gradle').read_text(encoding='utf-8'))
        self.assertIn('qualityRootGraph',(ROOT/'settings.gradle').read_text(encoding='utf-8'))

    def test_docker_keeps_runtime_payload_and_requires_reviewed_units(self):
        for name in ('Dockerfile','Dockerfile.slim','cli/Dockerfile','telegram-bot/Dockerfile'):
            body=(ROOT/name).read_text(encoding='utf-8')
            builder=body.split(' AS builder\n',1)[1].split('\nFROM ',1)[0]
            self.assertIn('--max-workers=2',builder)
            self.assertNotIn('-x test',builder)
            self.assertIn('SOURCE_DATE_EPOCH',builder)
            if name.startswith('Dockerfile'):
                self.assertIn('gradle/test-lanes.json',builder)
                self.assertIn('COPY LICENSE NOTICE THIRD_PARTY_NOTICES.md',builder)
            else:
                component='cli' if name.startswith('cli/') else 'bot'
                self.assertIn('-PqualityRootGraph=clients-selected-v1',builder)
                self.assertIn('-PqualityRootComponents='+component,builder)
                self.assertNotIn('COPY backend/src',builder)
        self.assertIn('.local/**',(ROOT/'.dockerignore').read_text(encoding='utf-8'))

    def test_all_changed_python_sources_parse(self):
        for name in ('ci_quality_plan.py','check_test_lanes.py','gradle-context.py','release_verify.py'):
            ast.parse((ROOT/'scripts'/name).read_text(encoding='utf-8'),filename=name)


if __name__=='__main__':
    unittest.main()
