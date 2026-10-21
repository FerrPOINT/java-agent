"""Execute the actual Bash argument boundary without Gradle, Docker or a JVM."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASH = (shutil.which('bash') if os.name == 'posix' else
        str(Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe'))


@unittest.skipUnless(BASH and Path(BASH).is_file(), 'A POSIX Bash test runner is required')
class QualityRootGraphTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='java graph ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'scripts').mkdir()
        self.helper = self.root / 'scripts/quality-gradle.sh'
        self.helper.write_text((ROOT / 'scripts/quality-gradle.sh').read_text(), newline='\n')
        (self.root / 'gradlew').write_text('printf "%s\\n" "$@"\n', newline='\n')

    def run_helper(self, arguments):
        env = dict(os.environ, SOURCE_DATE_EPOCH='0')
        for key in tuple(env):
            if key.startswith('BASE_JAVA_COMPILE_CACHE_'):
                del env[key]
        return subprocess.run([BASH, self.helper.as_posix(), '--quality-root', *arguments],
                              env=env, capture_output=True, text=True, timeout=10, check=False)

    def test_actual_selected_unit_artifact_and_dependency_task_transports(self):
        for tasks, members in (([':cli:test', ':cli:bootJar'], 'cli'),
                               ([':telegram-bot:test'], 'bot'),
                               ([':shared:test'], 'shared'),
                               ([':cli:test', ':telegram-bot:test'], 'bot,cli'),
                               (['--dependencies-only', ':cli:qualityDependencyInputs',
                                 ':shared:qualityDependencyInputs'], 'cli,shared')):
            with self.subTest(tasks=tasks):
                result = self.run_helper([*tasks, '--no-daemon', '--max-workers=2'])
                self.assertEqual(result.returncode, 0, result.stderr)
                argv = result.stdout.splitlines()
                self.assertEqual(argv.count('-PqualityRootGraph=clients-selected-v1'), 1)
                self.assertEqual(argv.count('-PqualityRootComponents=' + members), 1)
                self.assertEqual([arg for arg in argv if arg.startswith(':')],
                                 [arg for arg in tasks if arg.startswith(':')])

    def test_unknown_task_or_property_cannot_widen_selected_graph(self):
        for arguments in ([':cli:clean'], [':backend:test'], [':cli:test', '-PqualityRootComponents=bot'],
                          ['--quality-root', ':cli:test']):
            with self.subTest(arguments=arguments):
                result = self.run_helper(arguments)
                self.assertEqual(result.returncode, 64)
                self.assertEqual(result.stdout, '')

    def test_measured_three_workers_is_only_a_single_pure_cli_override(self):
        result = self.run_helper([':cli:test', ':cli:bootJar', '--offline', '--no-daemon', '--max-workers=3'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines().count('--max-workers=3'), 1)
        self.assertIn('-PqualityRootComponents=cli', result.stdout.splitlines())
        for arguments in ([':shared:test', '--max-workers=3'],
                          [':telegram-bot:test', '--max-workers=3'],
                          [':cli:test', ':shared:test', '--max-workers=3'],
                          [':cli:test', '--max-workers=3', '--max-workers=3'],
                          [':cli:test', '--max-workers=2', '--max-workers=3'],
                          [':cli:test', '--max-workers=2', '--max-workers=2'],
                          [':cli:test', '--max-workers=4']):
            with self.subTest(arguments=arguments):
                result = self.run_helper(arguments)
                self.assertEqual(result.returncode, 64, result.stderr)
                self.assertEqual(result.stdout, '')
