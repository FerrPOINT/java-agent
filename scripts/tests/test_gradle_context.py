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

    def snapshot(self, backend=True):
        with patch.object(probe, 'java_details', return_value={
            'java_version': '25.0.4.1', 'home': self.jdk,
            'executable': self.java, 'vendor_sha256': 'vendor-digest',
        }):
            return probe.snapshot(self.root, backend, self.environment)

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
        self.assertEqual(len(gates), 15)
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
