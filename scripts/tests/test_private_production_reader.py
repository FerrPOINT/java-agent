"""Read-only execution-input proof without Gradle or a real JVM."""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'gradle-context.py'
SPEC = importlib.util.spec_from_file_location('gradle_context', SCRIPT)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class PrivateProductionReaderTests(unittest.TestCase):
    def production_indices(self):
        self.graph_fixture()
        self.contract.update(schema='base-java-preparation/v4',
            dependencies={'cli': ['shared'], 'bot': ['shared'], 'shared': []})
        self.contract_file.write_text(json.dumps(self.contract))
        self.index_data['configuration_graph'] = {'profile': 'production-cli-v1', 'projects': [':', ':cli', ':shared']}
        self.index_data['roles']['unit'] = []
        self.index_data['files'] = {name: self.index_data['files'][name]
                                    for name in self.index_data['roles']['production']}
        self.write_index()
        indices = {name: json.loads(json.dumps({**self.index_data, 'module': ':' + name}))
                   for name in ['cli', 'shared']}
        return indices

    def test_private_production_index_reads_exact_external_bytes_without_resolver_or_units(self):
        indices = self.production_indices()
        with patch.object(probe.subprocess, 'run', side_effect=AssertionError('No resolver or JVM')):
            result = probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        self.assertEqual((result['files'], result['bytes'], result['role']), (1, 4, 'production'))
        self.assertEqual(result['configuration_graph'], self.index_data['configuration_graph'])
        self.assertNotIn(str(self.home), json.dumps(result))
        for role in ['production', 'unit']:
            with self.subTest(role=role), self.assertRaises(ValueError):
                probe.dependency_snapshot(self.root, self.home, ['cli', 'shared'], '9.6.1', False, role, indices=indices)

    def test_private_production_index_refuses_unit_payloads_legacy_graphs_and_wrong_wrapper(self):
        original = self.production_indices()
        invalid = []
        for mutation in ['unit', 'files', 'graph', 'schema', 'module', 'missing', 'extra']:
            indices = json.loads(json.dumps(original))
            row = indices['cli']
            if mutation == 'unit': row['roles']['unit'] = row['roles']['production']
            elif mutation == 'files': row['roles']['production'] = []
            elif mutation == 'graph': row['configuration_graph']['profile'] = 'clients-selected-v1'
            elif mutation == 'schema':
                row['schema'] = 'java-gradle-dependency-inputs/v1'
                del row['configuration_graph']
            elif mutation == 'module': row['module'] = ':shared'
            elif mutation == 'missing': del indices['shared']
            else: indices['bot'] = indices['shared']
            invalid.append((mutation, indices))
        for mutation, indices in invalid:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        for version in ['9.8.0', '9.8.1', 'unknown']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                probe.production_dependency_snapshot(self.root, self.home, version, indices=original)

    def test_private_production_index_refuses_old_timestamp_corruption_and_graph_drift(self):
        indices = self.production_indices()
        jar = Path(indices['cli']['roles']['production'][0])
        stamp = jar.stat()
        jar.write_bytes(b'CCCC')
        os.utime(jar, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaisesRegex(ValueError, 'integrity'):
            probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        jar.write_bytes(b'AAAA')
        self.contract['dependencies']['cli'] = ['bot']
        self.contract_file.write_text(json.dumps(self.contract))
        with self.assertRaises(ValueError):
            probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)

    def test_private_production_index_rejects_outside_files_and_conflicting_shared_records(self):
        indices = self.production_indices()
        selected = indices['cli']['roles']['production'][0]
        indices['shared']['files'][selected]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        row = indices['cli']['files'].pop(selected)
        outside = str(self.root / 'outside-private.jar')
        indices['cli']['files'][outside] = row
        indices['cli']['roles']['production'] = [outside]
        with self.assertRaisesRegex(ValueError, 'cache root'):
            probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)

    def test_selected_cli_context_requires_the_shared_dependency_and_excludes_bot(self):
        self.graph_fixture()
        self.contract.update(schema='base-java-preparation/v4',
            dependencies={'cli': ['shared'], 'bot': ['shared'], 'shared': []})
        self.contract_file.write_text(json.dumps(self.contract))
        graph = {'profile': 'clients-selected-v1', 'projects': [':', ':cli', ':shared']}
        self.assertEqual(probe.configuration_graph(self.root, False, graph, ['cli', 'shared']), graph)
        for modules in (['cli'], ['cli', 'shared', 'bot'], ['cli', 'cli', 'shared']):
            with self.subTest(modules=modules), self.assertRaises(ValueError):
                probe.configuration_graph(self.root, False, graph, modules)

    def test_private_production_dependency_identity_ignores_sources_but_tracks_valid_dependency_change(self):
        indices = self.production_indices()
        before = probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        for name in ['cli/src/main/java/Main.java', 'cli/src/test/java/Test.java', 'README.md']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'unrelated to dependency bytes')
        self.assertEqual(before, probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices))
        selected = indices['cli']['roles']['production'][0]
        Path(selected).write_bytes(b'CCCC')
        for value in indices.values():
            value['files'][selected]['sha256'] = probe.content(Path(selected))
        after = probe.production_dependency_snapshot(self.root, self.home, '9.6.1', indices=indices)
        self.assertNotEqual(before['sha256'], after['sha256'])
        self.assertEqual((before['files'], before['bytes']), (after['files'], after['bytes']))

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

    def write_index(self):
        self.index.write_text(json.dumps(self.index_data))


if __name__ == '__main__': unittest.main()
