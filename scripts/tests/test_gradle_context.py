"""Read-only execution-input proof without Gradle or a real JVM."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'gradle-context.py'
SPEC = importlib.util.spec_from_file_location('gradle_context', SCRIPT)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class GradleContextTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.jdk = self.root / 'jdk'
        self.java = self.jdk / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        self.java.parent.mkdir(parents=True)
        self.java.write_text('selected Java binary')
        self.modules = self.jdk / 'lib/modules'
        self.modules.parent.mkdir()
        self.modules.write_bytes(b'AAAA')
        self.home = self.root / 'user-gradle'
        self.home.mkdir()
        self.environment = {'JAVA_HOME': str(self.jdk), 'GRADLE_USER_HOME': str(self.home),
                            'PATH': str(self.root / 'wrong-java')}
        for directory, version in ((self.root, '9.6.1'), (self.root / 'backend', '9.8.0')):
            wrapper = directory / 'gradle/wrapper'
            wrapper.mkdir(parents=True)
            (wrapper / 'gradle-wrapper.properties').write_text('distributionUrl=gradle-' + version)
            (wrapper / 'gradle-wrapper.jar').write_bytes(b'wrapper')

    def snapshot(self, backend=True, **options):
        with patch.object(probe, 'java_details', return_value={
            'java_version': '25.0.4.1', 'home': self.jdk,
            'executable': self.java, 'vendor_sha256': 'vendor-digest',
        }):
            return probe.snapshot(self.root, backend, self.environment, **options)

    def compile_cache_environment(self):
        policy = self.root / 'base policy/java-qa-compile-cache.init.gradle'
        policy.parent.mkdir(exist_ok=True)
        policy.write_bytes(b'// owned Base policy\n')
        return {'BASE_JAVA_COMPILE_CACHE_ACTIVE': '1',
                'BASE_JAVA_COMPILE_CACHE_PROFILE': 'root-clients-v1',
                'BASE_JAVA_COMPILE_CACHE_NAMESPACE': 'a' * 64,
                'BASE_JAVA_COMPILE_CACHE_POLICY_SHA256': probe.content(policy),
                'BASE_JAVA_COMPILE_CACHE_POLICY': str(policy)}

    def test_optional_compiler_context_preserves_legacy_wire_and_stable_preparation_inputs(self):
        before = self.snapshot(backend=False)
        self.assertNotIn('compiler_cache', before)
        self.assertEqual(before['inputs_sha256'], probe.digest(before['inputs']))
        self.environment.update(self.compile_cache_environment())
        active = self.snapshot(backend=False)
        self.assertEqual(active['inputs'], before['inputs'])
        self.assertEqual(active['compiler_cache'], {
            'schema': 'base-java-compiler-cache-context/v1', 'profile': 'root-clients-v1',
            'namespace': 'a' * 64, 'policy': self.environment['BASE_JAVA_COMPILE_CACHE_POLICY'],
            'policy_sha256': self.environment['BASE_JAVA_COMPILE_CACHE_POLICY_SHA256']})
        self.assertEqual(active['inputs_sha256'], probe.digest({
            'inputs': active['inputs'], 'compiler_cache': active['compiler_cache']}))
        self.assertFalse(active['reuse_qualified'])
        self.assertNotIn('BASE_JAVA_COMPILE_CACHE_ACTIVE', probe.ENVIRONMENT)

    def test_compiler_policy_old_timestamp_bytes_namespace_and_path_bind_full_snapshot_only(self):
        self.environment.update(self.compile_cache_environment())
        before = self.snapshot(backend=False)
        policy = Path(self.environment['BASE_JAVA_COMPILE_CACHE_POLICY'])
        stamp = policy.stat()
        policy.write_bytes(b'// other Base policy\n')
        os.utime(policy, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaises(ValueError):
            self.snapshot(backend=False)
        self.environment['BASE_JAVA_COMPILE_CACHE_POLICY_SHA256'] = probe.content(policy)
        changed = self.snapshot(backend=False)
        self.assertNotEqual(before['inputs_sha256'], changed['inputs_sha256'])
        self.assertEqual(before['inputs'], changed['inputs'])
        self.environment['BASE_JAVA_COMPILE_CACHE_NAMESPACE'] = 'b' * 64
        namespaced = self.snapshot(backend=False)
        self.assertNotEqual(changed['inputs_sha256'], namespaced['inputs_sha256'])
        self.assertEqual(before['inputs'], namespaced['inputs'])
        other = self.root / 'other/java-qa-compile-cache.init.gradle'
        other.parent.mkdir()
        other.write_bytes(policy.read_bytes())
        self.environment['BASE_JAVA_COMPILE_CACHE_POLICY'] = str(other)
        moved = self.snapshot(backend=False)
        self.assertNotEqual(namespaced['inputs_sha256'], moved['inputs_sha256'])
        self.assertEqual(before['inputs'], moved['inputs'])

    def test_compiler_cache_malformed_environments_and_backend_are_refused_before_java_probe(self):
        valid = self.compile_cache_environment()
        invalid = [{key: value for key, value in valid.items() if key != missing} for missing in valid]
        invalid += [{**valid, key: value} for key, value in (
            ('BASE_JAVA_COMPILE_CACHE_ACTIVE', ''), ('BASE_JAVA_COMPILE_CACHE_ACTIVE', '0'),
            ('BASE_JAVA_COMPILE_CACHE_PROFILE', 'backend-v1'),
            ('BASE_JAVA_COMPILE_CACHE_NAMESPACE', 'A' * 64),
            ('BASE_JAVA_COMPILE_CACHE_POLICY_SHA256', 'f' * 64),
            ('BASE_JAVA_COMPILE_CACHE_POLICY', 'relative/java-qa-compile-cache.init.gradle'),
            ('BASE_JAVA_COMPILE_CACHE_POLICY', str(self.root)),
        )]
        for environment in invalid:
            with self.subTest(environment=environment), patch.object(probe, 'java_details') as java:
                with self.assertRaises(ValueError):
                    probe.snapshot(self.root, False, {**self.environment, **environment})
                java.assert_not_called()
        for backend, modules in ((True, None), (False, ['bot']), (False, ['cli', 'bot']), (False, ['backend'])):
            with self.subTest(backend=backend, modules=modules), patch.object(probe, 'java_details') as java:
                with self.assertRaises(ValueError):
                    probe.snapshot(self.root, backend, {**self.environment, **valid}, prepared_modules=modules)
                java.assert_not_called()

    def test_compiler_policy_byte_budget_is_bounded(self):
        environment = self.compile_cache_environment()
        policy = Path(environment['BASE_JAVA_COMPILE_CACHE_POLICY'])
        policy.write_bytes(b'x' * 65537)
        environment['BASE_JAVA_COMPILE_CACHE_POLICY_SHA256'] = probe.content(policy)
        with self.assertRaises(ValueError):
            probe.compiler_cache_context(environment)

    def test_compiler_policy_linked_parent_is_not_trusted(self):
        environment = self.compile_cache_environment()
        policy = Path(environment['BASE_JAVA_COMPILE_CACHE_POLICY'])
        link = self.root / 'linked-policy'
        try:
            link.symlink_to(policy.parent, target_is_directory=True)
        except OSError:
            self.skipTest('Host does not permit creating symlinks')
        environment['BASE_JAVA_COMPILE_CACHE_POLICY'] = str(link / policy.name)
        with self.assertRaises(ValueError):
            probe.compiler_cache_context(environment)

    def prepared_fixture(self, backend=True, version=None):
        version = version or ('9.8.0' if backend else '9.6.1')
        directory = self.root / 'backend' if backend else self.root
        properties = directory / 'gradle/wrapper/gradle-wrapper.properties'
        properties.write_text('distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
                              'zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n'
                              'distributionUrl=https\\://services.gradle.org/distributions/gradle-' + version + '-bin.zip\n')
        # Golden cache keys from the pinned Wrapper PathAssembler URL algorithm.
        keys = {'9.8.0': '3m7h6ceboy5k31n8kzwzuxssm', '9.6.1': '4ticwg1pgcbps2hj28r8so764',
                '9.8.1': 'a2l6ltp3k1vth6lwipzm5p2jp'}
        parent = self.home / 'wrapper/dists' / ('gradle-' + version + '-bin') / keys[version]
        distribution = parent / ('gradle-' + version)
        launcher = distribution / 'lib' / ('gradle-launcher-' + version + '.jar')
        launcher.parent.mkdir(parents=True)
        launcher.write_bytes(b'launcher')
        (distribution / 'bin').mkdir()
        (distribution / 'bin/gradle').write_text('launcher script')
        (parent / ('gradle-' + version + '-bin.zip.ok')).touch()
        jars = {}
        for name, payload in (('production.jar', b'AAAA'), ('unit.jar', b'BBBB')):
            path = self.home / 'caches/modules-2/files-2.1' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            jars[name] = path
        selected = {str(path.resolve()): {'sha256': probe.content(path), 'bytes': path.stat().st_size}
                    for path in jars.values()}
        module = 'backend' if backend else 'cli'
        self.index = self.root / module / 'build/quality' / ('gradle-' + version + '-dependency-inputs.json')
        self.index.parent.mkdir(parents=True, exist_ok=True)
        self.index_data = {'schema': 'java-gradle-dependency-inputs/v1', 'module': ':' if backend else ':cli', 'gradle': version,
                           'roles': {'production': [str(jars['production.jar'].resolve())],
                                     'unit': sorted(selected)}, 'files': selected}
        self.write_index()
        return distribution, launcher, jars

    def graph_fixture(self, backend=False, profile=None):
        self.prepared_fixture(backend=backend)
        graph = {'profile': profile or ('backend-v1' if backend else 'clients-v1'),
                 'projects': [':', ':shared'] if backend else [':', ':cli', ':shared', ':telegram-bot']}
        if profile == 'full-root-v1':
            graph['projects'] = [':', ':backend', ':cli', ':shared', ':telegram-bot']
        self.contract = {'schema': 'base-java-preparation/v2', 'inputs': {}, 'dependencies': {},
                         'configuration_graphs': {'root': ['bot', 'cli', 'shared'], 'backend': ['backend', 'shared']}}
        self.contract_file = self.root / 'gradle/java-preparation.json'
        self.contract_file.write_text(json.dumps(self.contract))
        self.index_data.update(schema='java-gradle-dependency-inputs/v2', configuration_graph=graph)
        self.write_index()
        return graph

    def test_v2_indices_bind_actual_configuration_graph_without_gradle(self):
        self.graph_fixture()
        for profile, projects in (('clients-v1', [':', ':cli', ':shared', ':telegram-bot']),
                                   ('full-root-v1', [':', ':backend', ':cli', ':shared', ':telegram-bot'])):
            graph = {'profile': profile, 'projects': projects}
            with self.subTest(profile=profile):
                self.index_data['configuration_graph'] = graph
                self.write_index()
                with patch.object(probe.subprocess, 'run', side_effect=AssertionError('No resolver')):
                    result = probe.dependency_snapshot(self.root, self.home, ['cli'], '9.6.1', False, 'unit')
                self.assertEqual(result['configuration_graph'], graph)

    def test_v2_backend_graph_keeps_standalone_root_identity(self):
        graph = self.graph_fixture(backend=True)
        result = probe.dependency_snapshot(self.root, self.home, ['backend'], '9.8.0', True, 'unit')
        self.assertEqual(result['configuration_graph'], graph)

    def test_v2_configuration_graph_is_an_input_and_mismatches_fail_closed(self):
        graph = self.graph_fixture()
        before = self.snapshot(backend=False, prepared_modules=['cli'])
        self.index_data['configuration_graph'] = {'profile': 'full-root-v1',
            'projects': [':', ':backend', ':cli', ':shared', ':telegram-bot']}
        self.write_index()
        self.assertNotEqual(before['inputs_sha256'], self.snapshot(backend=False, prepared_modules=['cli'])['inputs_sha256'])
        bad = [None, {}, {**graph, 'extra': True}, {**graph, 'profile': 'backend-v1'},
               {**graph, 'projects': [':', ':cli', ':shared']}, {**graph, 'projects': list(reversed(graph['projects']))},
               {**graph, 'projects': graph['projects'] + [':backend']}, {**graph, 'profile': 'unknown'}]
        for change in bad:
            with self.subTest(graph=change):
                self.index_data['configuration_graph'] = change
                self.write_index()
                with self.assertRaisesRegex(ValueError, 'graph'):
                    self.snapshot(backend=False, prepared_modules=['cli'])
        self.index_data['configuration_graph'] = graph
        self.write_index()
        self.contract['configuration_graphs']['root'].append('backend')
        self.contract_file.write_text(json.dumps(self.contract))
        with self.assertRaisesRegex(ValueError, 'contract'):
            self.snapshot(backend=False, prepared_modules=['cli'])

    def test_mixed_legacy_and_v2_or_different_graph_indexes_are_refused(self):
        self.graph_fixture()
        shared = self.root / 'shared/build/quality' / self.index.name
        shared.parent.mkdir(parents=True)
        value = {**self.index_data, 'module': ':shared', 'schema': 'java-gradle-dependency-inputs/v1'}
        value.pop('configuration_graph')
        shared.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'different configuration graphs'):
            probe.dependency_snapshot(self.root, self.home, ['cli', 'shared'], '9.6.1', False, 'unit')
        value.update(schema='java-gradle-dependency-inputs/v2', configuration_graph={'profile': 'full-root-v1',
                     'projects': [':', ':backend', ':cli', ':shared', ':telegram-bot']})
        shared.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'different configuration graphs'):
            probe.dependency_snapshot(self.root, self.home, ['cli', 'shared'], '9.6.1', False, 'unit')

    def write_index(self):
        self.index.write_text(json.dumps(self.index_data))

    def test_prepared_snapshot_reads_selected_distribution_and_role_without_gradle(self):
        distribution, launcher, jars = self.prepared_fixture(backend=False)
        with patch.object(probe.subprocess, 'run', side_effect=AssertionError('no Gradle/JVM command')):
            before = self.snapshot(backend=False, prepared_modules=['cli'], role='production')
        context = before['inputs']['prepared']
        self.assertEqual(context['gradle_version'], '9.6.1')
        self.assertEqual(context['dependencies']['files'], 1)
        self.assertEqual(context['dependencies']['bytes'], 4)
        self.assertFalse(before['reuse_qualified'])
        self.assertNotIn(str(self.home), json.dumps(context))
        self.assertEqual(before, self.snapshot(backend=False, prepared_modules=['cli'], role='production'))
        stamp = launcher.stat()
        launcher.write_bytes(b'LAUNCHER')
        os.utime(launcher, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertNotEqual(before['inputs_sha256'],
                            self.snapshot(backend=False, prepared_modules=['cli'], role='production')['inputs_sha256'])

    def test_committed_backend_patch_wrapper_keeps_readonly_integrity_and_reuse_disabled(self):
        distribution, launcher, _ = self.prepared_fixture(backend=True, version='9.8.1')
        with patch.object(probe.subprocess, 'run', side_effect=AssertionError('No resolver or JVM bootstrap')):
            before = self.snapshot(prepared_modules=['backend'])
        self.assertEqual(before['inputs']['prepared']['gradle_version'], '9.8.1')
        self.assertFalse(before['reuse_qualified'])
        stamp = launcher.stat()
        launcher.write_bytes(b'LAUNCHER')
        os.utime(launcher, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.assertNotEqual(before['inputs_sha256'], self.snapshot(prepared_modules=['backend'])['inputs_sha256'])
        (distribution.parent / 'gradle-9.8.1-bin.zip.ok').unlink()
        with self.assertRaisesRegex(ValueError, 'unavailable'):
            self.snapshot(prepared_modules=['backend'])
        props = self.root / 'backend/gradle/wrapper/gradle-wrapper.properties'
        props.write_text(props.read_text().replace('9.8.1-bin.zip', '9.8.2-bin.zip'))
        with self.assertRaisesRegex(ValueError, 'not qualified'):
            self.snapshot(prepared_modules=['backend'])

    def test_unit_only_dependency_change_preserves_production_but_changes_unit_context(self):
        _, _, jars = self.prepared_fixture(backend=False)
        before = {role: self.snapshot(backend=False, prepared_modules=['cli'], role=role)
                  for role in ('production', 'unit')}
        unit = jars['unit.jar']
        stamp = unit.stat()
        unit.write_bytes(b'CCCC')
        os.utime(unit, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        self.index_data['files'][str(unit.resolve())]['sha256'] = probe.content(unit)
        self.write_index()
        self.assertEqual(before['production'], self.snapshot(backend=False, prepared_modules=['cli'], role='production'))
        self.assertNotEqual(before['unit']['inputs_sha256'],
                            self.snapshot(backend=False, prepared_modules=['cli'], role='unit')['inputs_sha256'])
        jars['production.jar'].write_bytes(b'DDDD')
        with self.assertRaisesRegex(ValueError, 'integrity'):
            self.snapshot(backend=False, prepared_modules=['cli'], role='production')

    def test_wrong_wrapper_index_missing_marker_and_unsupported_layout_fail_closed(self):
        distribution, _, _ = self.prepared_fixture(backend=False)
        self.index_data['gradle'] = '9.8.0'
        self.write_index()
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])
        self.index_data['gradle'] = '9.6.1'
        self.write_index()
        (distribution.parent / 'gradle-9.6.1-bin.zip.ok').unlink()
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])
        (distribution.parent / 'gradle-9.6.1-bin.zip.ok').touch()
        properties = self.root / 'gradle/wrapper/gradle-wrapper.properties'
        properties.write_text(properties.read_text().replace('wrapper/dists', '../outside'))
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])

    def test_index_cannot_escape_cache_roots_or_omit_production_from_unit(self):
        _, _, jars = self.prepared_fixture(backend=False)
        valid = json.loads(json.dumps(self.index_data))
        outside = self.root / 'private'
        outside.write_text('do-not-read')
        row = self.index_data['files'].pop(str(jars['production.jar'].resolve()))
        self.index_data['files'][str(outside)] = row
        self.index_data['roles']['production'] = [str(outside)]
        self.index_data['roles']['unit'] = sorted(self.index_data['files'])
        self.write_index()
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])
        self.index_data = valid
        self.index_data['roles']['unit'] = []
        self.write_index()
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])

    def test_standalone_backend_distribution_and_module_identity_remain_independent(self):
        self.prepared_fixture()
        before = self.snapshot(prepared_modules=['backend'])
        self.assertEqual(before['inputs']['prepared']['gradle_version'], '9.8.0')
        other = self.home / 'wrapper/dists/gradle-9.6.1-bin/unrelated'
        other.mkdir(parents=True)
        (other / 'ordinary-cache').write_text('not consumed')
        self.assertEqual(before, self.snapshot(prepared_modules=['backend']))
        self.index_data['module'] = ':backend'
        self.write_index()
        with self.assertRaises(ValueError):
            self.snapshot(prepared_modules=['backend'])

    def test_duplicate_launcher_or_extra_distribution_root_is_not_ready(self):
        distribution, _, _ = self.prepared_fixture(backend=False)
        extra = distribution / 'lib/gradle-launcher-unknown.jar'
        extra.write_text('not a valid wrapper layout')
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])
        extra.unlink()
        (distribution.parent / 'second-root').mkdir()
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])

    def test_wrapper_escapes_cannot_hide_the_distribution_selected_by_java_properties(self):
        self.prepared_fixture(backend=False)
        path = self.root / 'gradle/wrapper/gradle-wrapper.properties'
        original = path.read_text()
        for extra in ('distribution\\u0055rl=https://example.invalid/other.zip\n', 'networkTimeout=10000\\\n'):
            with self.subTest(extra=extra):
                path.write_text(original + extra)
                with self.assertRaises(ValueError):
                    self.snapshot(backend=False, prepared_modules=['cli'])

    def test_shared_external_file_is_read_once_for_compatible_module_indexes(self):
        _, _, jars = self.prepared_fixture(backend=False)
        shared = self.root / 'shared/build/quality' / self.index.name
        shared.parent.mkdir(parents=True)
        shared.write_text(json.dumps({**self.index_data, 'module': ':shared'}))
        opened = []
        original = Path.open

        def recording(path, *arguments, **options):
            opened.append(path)
            return original(path, *arguments, **options)

        with patch.object(Path, 'open', recording):
            result = probe.dependency_snapshot(self.root, self.home, ['cli', 'shared'], '9.6.1', False, 'production')
        self.assertEqual(result['files'], 1)
        self.assertEqual(opened.count(jars['production.jar']), 1)
        self.assertNotIn(jars['unit.jar'], opened)

    def test_owner_index_snapshot_is_not_reopened_and_still_checks_external_bytes(self):
        _, _, jars = self.prepared_fixture(backend=False)
        bundle = {'cli': json.loads(self.index.read_text())}
        self.index.unlink()
        result = probe.dependency_snapshot(self.root, self.home, ['cli'], '9.6.1', False,
                                           'production', indices=bundle)
        self.assertEqual(result['files'], 1)
        jars['production.jar'].write_bytes(b'DDDD')
        with self.assertRaisesRegex(ValueError, 'integrity'):
            probe.dependency_snapshot(self.root, self.home, ['cli'], '9.6.1', False,
                                      'production', indices=bundle)
        with self.assertRaisesRegex(ValueError, 'bundle'):
            probe.dependency_snapshot(self.root, self.home, ['shared'], '9.6.1', False,
                                      'production', indices=bundle)

    def test_malformed_duplicate_or_oversized_index_is_not_trusted(self):
        self.prepared_fixture(backend=False)
        original = self.index.read_text()
        bad = [original[:-1] + ',"files":{}}', '{', ' ' * (4 * 1024**2 + 1)]
        for change in ({'bytes': True}, {'bytes': 2 * 1024**3 + 1}, {'sha256': 'not-sha256'}):
            value = json.loads(original)
            next(iter(value['files'].values())).update(change)
            bad.append(json.dumps(value))
        for payload in bad:
            with self.subTest(bytes=len(payload)):
                self.index.write_text(payload)
                with self.assertRaises(ValueError):
                    self.snapshot(backend=False, prepared_modules=['cli'])

    def test_dependency_link_cannot_redirect_reads_outside_the_cache_root(self):
        _, _, jars = self.prepared_fixture(backend=False)
        original = jars['production.jar']
        outside = self.root / 'external.jar'
        outside.write_bytes(original.read_bytes())
        original.unlink()
        try:
            original.symlink_to(outside)
        except OSError:
            self.skipTest('Host does not permit creating symlinks')
        with self.assertRaises(ValueError):
            self.snapshot(backend=False, prepared_modules=['cli'])

    def test_actual_java_home_wins_over_path_and_version_probe_strips_injection_channels(self):
        result = subprocess.CompletedProcess([], 0, '',
            f'java.home = {self.jdk}\njava.version = 25.0.4.1\njava.vendor = Eclipse Adoptium\n')
        self.environment.update(JAVA_TOOL_OPTIONS='-Dpassword=secret',
                                JDK_JAVA_OPTIONS='-javaagent:secret', LD_PRELOAD='secret')
        with patch.object(probe.subprocess, 'run', return_value=result) as run:
            data = probe.java_details(self.environment)
        args, options = run.call_args
        self.assertEqual(args[0][0], str(self.java.resolve()))
        self.assertNotIn('JAVA_TOOL_OPTIONS', options['env'])
        self.assertNotIn('JDK_JAVA_OPTIONS', options['env'])
        self.assertNotIn('LD_PRELOAD', options['env'])
        self.assertEqual(data['java_version'], '25.0.4.1')

    def test_jdk_old_mtime_byte_change_invalidates_but_gradle_cache_writes_do_not(self):
        before = self.snapshot()
        stamp = self.modules.stat().st_mtime
        self.modules.write_bytes(b'BBBB')
        os.utime(self.modules, (stamp, stamp))
        self.assertNotEqual(before['inputs_sha256'], self.snapshot()['inputs_sha256'])
        self.modules.write_bytes(b'AAAA')
        cache = self.home / 'caches/modules-2/ordinary-cache'
        cache.parent.mkdir(parents=True)
        cache.write_text('not a configuration input')
        self.assertEqual(before, self.snapshot())

    def test_properties_init_flags_wrapper_and_daemon_criteria_change_context_without_plaintext(self):
        baseline = self.snapshot()
        for path in (self.home / 'gradle.properties', self.home / 'init.d/company.gradle',
                     self.root / 'backend/gradle/gradle-daemon-jvm.properties'):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('private_password=do-not-publish')
            current = self.snapshot()
            self.assertNotEqual(baseline['inputs_sha256'], current['inputs_sha256'])
            self.assertNotIn('do-not-publish', json.dumps(current))
            path.unlink()
        self.environment['GRADLE_OPTS'] = '-Dprivate_password=do-not-publish'
        current = self.snapshot()
        self.assertNotEqual(baseline['inputs_sha256'], current['inputs_sha256'])
        self.assertNotIn('do-not-publish', json.dumps(current))
        self.environment.pop('GRADLE_OPTS')
        self.assertNotEqual(baseline['inputs_sha256'], self.snapshot(backend=False)['inputs_sha256'])
        self.assertIs(baseline['reuse_qualified'], False)

    def test_inventory_does_not_follow_an_external_root_link(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'private').write_text('not scanned')
        link = self.home / 'init.d'
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest('Host does not permit creating symlinks')
        with patch.object(probe, 'content', side_effect=AssertionError('must not read external tree')):
            result = probe.inventory(link)
        self.assertFalse(result['complete'])
        self.assertEqual(result['files'], 0)

    def test_gradle_gates_select_wrapper_context_and_keep_unqualified_reuse_disabled(self):
        repository = SCRIPT.parents[1]
        manifest = json.loads((repository / '.quality-gate.json').read_text(encoding='utf-8'))
        gates = [gate for gate in manifest['gates'] if 'scripts/quality-gradle.sh' in gate['argv']]
        self.assertEqual(len(gates), 17)
        for gate in gates:
            with self.subTest(gate=gate['id']):
                expected = ['{python}', 'scripts/gradle-context.py']
                if '--backend' in gate['argv']:
                    expected.append('--backend')
                tool, = gate['tools']
                self.assertEqual(tool['context_argv'], expected)
                self.assertEqual(tool['argv'], ['java', '-version'])
                self.assertEqual(tool['pattern'], r'25\.0\.4\.1')
                self.assertEqual(tool['context_pattern'], r'"java_version":"25\.0\.4\.1"')
                self.assertEqual(tool['reuse_pattern'], '"reuse_qualified":true')
                self.assertIs(tool['recheck'], True)
                self.assertEqual(tool['cwd'], '.')
                self.assertIs(gate['cacheable'], False)
                self.assertEqual(gate['source_date_epoch'], 'git-head')
                self.assertIn('SOURCE_DATE_EPOCH', gate['environment_inputs'])
                if gate.get('kind') == 'artifact':
                    self.assertIn('scripts/gradle-context.py', gate['inputs'])


if __name__ == '__main__':
    unittest.main()
