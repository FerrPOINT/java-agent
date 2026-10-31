"""Pure path/command selection; never starts Git, Gradle or an application."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scripts.select_java_modules import groups, root_unit_tasks
from scripts.vendor import base_quality_selection as selection

ROOT = Path(__file__).resolve().parents[2]


class PreparedWrapperReaderTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('fixture_gradle_context', ROOT / 'scripts/gradle-context.py')
        self.probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.probe)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def distribution(self, version, *, backend=True, source=None):
        repository = self.root / ('fixture-' + version)
        directory = repository / 'backend' if backend else repository
        properties = directory / 'gradle/wrapper/gradle-wrapper.properties'
        properties.parent.mkdir(parents=True)
        url = 'https://services.gradle.org/distributions/gradle-' + version + '-bin.zip'
        properties.write_text(source or ('distributionBase=GRADLE_USER_HOME\n'
            'distributionPath=wrapper/dists\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n'
            'distributionUrl=' + url.replace(':', '\\:') + '\n'), encoding='utf-8')
        number = int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest(), 'big')
        key = ''
        while number:
            number, remainder = divmod(number, 36)
            key = '0123456789abcdefghijklmnopqrstuvwxyz'[remainder] + key
        home = repository / 'gradle-home'
        parent = home / 'wrapper/dists' / ('gradle-' + version + '-bin') / key
        installed = parent / ('gradle-' + version)
        for name in ('bin/gradle', 'lib/gradle-launcher-' + version + '.jar'):
            path = installed / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'inert fixture; never executed')
        (parent / ('gradle-' + version + '-bin.zip.ok')).write_bytes(b'')
        return repository, directory, home, installed

    def test_actual_declared_root_and_backend_wrappers_are_readable_without_execution(self):
        for relative, version, backend in [('gradle/wrapper/gradle-wrapper.properties', '9.6.1', False),
                                          ('backend/gradle/wrapper/gradle-wrapper.properties', '9.8.2', True)]:
            with self.subTest(version=version):
                self.assertIn('gradle-' + version + '-bin.zip', (ROOT / relative).read_text())
                _, directory, home, _ = self.distribution(version, backend=backend)
                with patch.object(self.probe.subprocess, 'run', side_effect=AssertionError('No JVM or Gradle')):
                    actual, state = self.probe.distribution_snapshot(directory, home)
                self.assertEqual(actual, version)
                self.assertTrue(state['complete'])
                self.assertEqual(state['files'], 2)

    def test_older_supported_wrappers_keep_their_independent_inventory(self):
        for version in ('9.8.0', '9.8.1'):
            with self.subTest(version=version):
                _, directory, home, _ = self.distribution(version)
                self.assertEqual(self.probe.distribution_snapshot(directory, home)[0], version)

    def test_unknown_version_host_or_archive_kind_does_not_become_supported(self):
        for suffix, replacement in [('future', 'gradle-9.8.3-bin.zip'),
                                    ('archive', 'gradle-9.8.2-all.zip'),
                                    ('host', 'https\\://untrusted.invalid/gradle-9.8.2-bin.zip')]:
            with self.subTest(case=suffix):
                _, directory, home, _ = self.distribution('9.8.2-' + suffix)
                properties = directory / 'gradle/wrapper/gradle-wrapper.properties'
                value = properties.read_text()
                url = replacement if suffix == 'host' else 'https\\://services.gradle.org/distributions/' + replacement
                properties.write_text(value[:value.index('distributionUrl=')] + 'distributionUrl=' + url + '\n')
                with self.assertRaisesRegex(ValueError, 'distribution URL'):
                    self.probe.distribution_snapshot(directory, home)

    def test_repeat_read_is_read_only_and_changed_launcher_bytes_change_identity(self):
        _, directory, home, installed = self.distribution('9.8.2')
        before = {path.relative_to(home): path.read_bytes() for path in home.rglob('*') if path.is_file()}
        first = self.probe.distribution_snapshot(directory, home)
        with patch.object(self.probe.subprocess, 'run', side_effect=AssertionError('No bootstrap')):
            self.assertEqual(first, self.probe.distribution_snapshot(directory, home))
        self.assertEqual(before, {path.relative_to(home): path.read_bytes() for path in home.rglob('*') if path.is_file()})
        launcher = installed / 'lib/gradle-launcher-9.8.2.jar'
        info = launcher.stat()
        launcher.write_bytes(b'changed inert fixture')
        os.utime(launcher, ns=(info.st_atime_ns, info.st_mtime_ns))
        self.assertNotEqual(first[1]['sha256'], self.probe.distribution_snapshot(directory, home)[1]['sha256'])

    def test_incomplete_ready_distribution_does_not_trigger_install_or_fallback(self):
        for index, missing in enumerate(('bin/gradle', 'lib/gradle-launcher-9.8.2.jar', '../gradle-9.8.2-bin.zip.ok')):
            with self.subTest(missing=missing):
                fixture = self.root / str(index)
                fixture.mkdir()
                previous, self.root = self.root, fixture
                _, directory, home, installed = self.distribution('9.8.2')
                self.root = previous
                (installed / missing).unlink()
                with patch.object(self.probe.subprocess, 'run', side_effect=AssertionError('No installation')), \
                        self.assertRaises((ValueError, FileNotFoundError)):
                    self.probe.distribution_snapshot(directory, home)

    def test_prepared_snapshot_recognizes_current_wrapper_but_never_grants_native_reuse(self):
        repository, _, home, _ = self.distribution('9.8.2')
        contract = repository / 'gradle/java-preparation.json'
        contract.parent.mkdir(parents=True)
        contract.write_bytes((ROOT / 'gradle/java-preparation.json').read_bytes())
        artifact = home / 'caches/fixture-dependency.jar'
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b'fixture dependency')
        row = {'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(), 'bytes': artifact.stat().st_size}
        filename = str(artifact)
        for name, project in [('backend', ':'), ('shared', ':shared')]:
            index = repository / name / 'build/quality/gradle-9.8.2-dependency-inputs.json'
            index.parent.mkdir(parents=True)
            index.write_text(json.dumps({'schema': 'java-gradle-dependency-inputs/v2', 'module': project,
                'gradle': '9.8.2', 'configuration_graph': {'profile': 'backend-v1', 'projects': [':', ':shared']},
                'roles': {'production': [filename], 'unit': [filename]}, 'files': {filename: row}}))
        jdk = repository / 'jdk'
        jdk.mkdir()
        executable = jdk / 'inert-java'
        executable.write_bytes(b'never executed')
        java = {'home': jdk, 'executable': executable, 'java_version': '25.0.4.1',
                'vendor_sha256': self.probe.digest('Eclipse Adoptium')}
        with patch.object(self.probe, 'java_details', return_value=java), \
                patch.object(self.probe.subprocess, 'run', side_effect=AssertionError('No native process')):
            snapshot = self.probe.snapshot(repository, True, {'GRADLE_USER_HOME': str(home)},
                                           prepared_modules=['backend', 'shared'])
        self.assertEqual(snapshot['inputs']['prepared']['gradle_version'], '9.8.2')
        self.assertEqual(snapshot['inputs']['prepared']['dependencies']['files'], 1)
        self.assertFalse(snapshot['reuse_qualified'])


class ToolInventoryMetadataTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('fixture_inventory_context', ROOT / 'scripts/gradle-context.py')
        self.probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.probe)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name, data in {'bin/tool': b'executable bytes', 'lib/nested/api.jar': b'archive bytes'}.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_regular_files_use_one_fresh_metadata_snapshot_per_inventory(self):
        original = Path.lstat
        for _ in range(2):
            seen = []
            def metadata(path, *args, seen=seen, **kwargs):
                seen.append(path)
                return original(path, *args, **kwargs)
            with patch.object(Path, 'lstat', metadata):
                result = self.probe.inventory(self.root)
            self.assertTrue(result['complete'])
            self.assertEqual(result['files'], 2)
            for path in (self.root, self.root / 'bin/tool', self.root / 'lib/nested/api.jar'):
                self.assertEqual(seen.count(path), 1, str(path))

    def test_content_counts_and_executable_identity_preserve_the_existing_schema(self):
        rows = {}
        size = 0
        for name in ('bin/tool', 'lib/nested/api.jar'):
            path = self.root / name
            data = path.read_bytes()
            size += len(data)
            rows[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'executable': bool(path.stat().st_mode & 0o111)}
        self.assertEqual(self.probe.inventory(self.root),
                         {'sha256': self.probe.digest(rows), 'files': 2, 'bytes': size, 'complete': True})

    def test_old_timestamp_does_not_reuse_content_between_observations(self):
        path = self.root / 'lib/nested/api.jar'
        first = self.probe.inventory(self.root)
        before = path.stat()
        path.write_bytes(b'changed bytes')
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        changed = self.probe.inventory(self.root)
        self.assertNotEqual(first['sha256'], changed['sha256'])
        self.assertEqual(first['files'], changed['files'])
        self.assertEqual(first['bytes'], changed['bytes'])

    def test_missing_optional_or_regular_file_root_keeps_empty_inventory(self):
        empty = {'sha256': self.probe.digest({}), 'files': 0, 'bytes': 0, 'complete': True}
        self.assertEqual(self.probe.inventory(self.root / 'missing'), empty)
        self.assertEqual(self.probe.inventory(self.root / 'bin/tool'), empty)

    def test_external_file_alias_is_not_read(self):
        outside = self.root.parent / ('outside-' + self.root.name)
        original_stat, original_resolve = Path.lstat, Path.resolve
        linked = self.root / 'bin/tool'
        def metadata(path, *args, **kwargs):
            if path == linked:
                return SimpleNamespace(st_mode=stat.S_IFLNK, st_file_attributes=0)
            return original_stat(path, *args, **kwargs)
        def resolve(path, *args, **kwargs):
            return outside if path == linked else original_resolve(path, *args, **kwargs)
        with patch.object(Path, 'lstat', metadata), patch.object(Path, 'resolve', resolve), \
                patch.object(self.probe.os, 'readlink', return_value=str(outside)):
            result = self.probe.inventory(self.root)
        self.assertFalse(result['complete'])
        self.assertEqual(result['files'], 1)

    def test_reparse_root_is_incomplete_before_scan(self):
        original = Path.lstat
        def metadata(path, *args, **kwargs):
            return SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400) if path == self.root else original(path, *args, **kwargs)
        with patch.object(Path, 'lstat', metadata), patch.object(self.probe.os, 'readlink', return_value='inert alias'), \
                patch.object(self.probe.os, 'scandir', side_effect=AssertionError('No alias traversal')):
            result = self.probe.inventory(self.root)
        self.assertFalse(result['complete'])
        self.assertEqual(result['files'], 0)

    def test_real_internal_file_directory_broken_and_external_aliases_keep_their_policy(self):
        directory = self.root / 'alias-fixture'
        directory.mkdir()
        tooltree = directory / 'tools'
        (tooltree / 'lib').mkdir(parents=True)
        (tooltree / 'target.txt').write_bytes(b'target')
        (tooltree / 'lib/other.txt').write_bytes(b'second')
        outside = directory / 'outside.txt'
        outside.write_bytes(b'outside is never an input')
        try:
            (tooltree / 'file-alias').symlink_to('target.txt')
            (tooltree / 'dir-alias').symlink_to('lib', target_is_directory=True)
            (tooltree / 'broken-alias').symlink_to('missing')
            (tooltree / 'external-alias').symlink_to(outside)
            (directory / 'root-alias').symlink_to(tooltree, target_is_directory=True)
        except OSError as error:
            self.skipTest('Fixture symlinks unavailable: ' + type(error).__name__)
        original, observed = self.probe.content, []
        def content(path):
            self.assertTrue(path.resolve().is_relative_to(tooltree))
            observed.append(path.resolve())
            return original(path)
        with patch.object(self.probe, 'content', side_effect=content):
            result = self.probe.inventory(tooltree)
            root_alias = self.probe.inventory(directory / 'root-alias')
        self.assertFalse(result['complete'])
        self.assertEqual((result['files'], result['bytes']), (3, 18))
        self.assertEqual(set(observed), {tooltree / 'target.txt', tooltree / 'lib/other.txt'})
        self.assertEqual(len(observed), 2)
        self.assertFalse(root_alias['complete'])
        self.assertEqual(root_alias['files'], 0)

    def test_selected_scan_error_is_not_changed_into_complete_inventory(self):
        with patch.object(self.probe.os, 'scandir', side_effect=PermissionError('source inaccessible')), \
                self.assertRaisesRegex(PermissionError, 'source inaccessible'):
            self.probe.inventory(self.root)


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
