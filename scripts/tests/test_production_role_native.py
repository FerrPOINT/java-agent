"""Manual pinned Gradle API fixture; builtin plugins, no application/network."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class ProductionRoleNativeTests(unittest.TestCase):
    def test_private_main_graph_integrity_cache_and_refusals(self):
        distribution = os.environ.get('PDLC_GRADLE_HOME')
        java_home = os.environ.get('JAVA_HOME')
        if os.name != 'posix' or not distribution or not java_home:
            self.skipTest('Owned Linux fixture requires pinned PDLC_GRADLE_HOME/JAVA_HOME and host admission')
        home = Path(distribution).resolve()
        launchers = list((home / 'lib').glob('gradle-gradle-cli-main-*.jar'))
        self.assertEqual(home.name, 'gradle-9.6.1')
        self.assertEqual(len(launchers), 1)
        self.assertIn('JAVA_VERSION="25.0.4.1"', (Path(java_home) / 'release').read_text())
        temporary = tempfile.TemporaryDirectory(prefix='java-production-role-')
        self.addCleanup(temporary.cleanup)
        owner = Path(temporary.name)
        root = owner / 'sources'
        root.mkdir()
        for name in ['settings.gradle', 'gradle/production/settings.gradle', 'gradle/production/project.gradle', 'gradle/quality-inputs.gradle']:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        for module in ['cli', 'shared']:
            target = root / module / 'src/main/java/fixture'
            target.mkdir(parents=True)
            source = 'public static String value(){ return "shared"; }' if module == 'shared' else 'public static String value(){ return Shared.value(); }'
            (target / (module.title() + '.java')).write_text('package fixture; public class ' + module.title() + ' {' + source + '}')
            build = "plugins { id 'java-library' }\n"
            if module == 'cli':
                build += "dependencies { implementation project(':shared') }\n"
                build += "tasks.register('bootJar', Jar) { archiveFileName='cli.jar'; from sourceSets.main.output }\n"
            build += "apply from: '../gradle/quality-inputs.gradle'\n"
            (root / module / 'build.gradle').write_text(build)
        files = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in root.rglob('*') if p.is_file()}
        view = {'schema': 'base-java-production-source/v1', 'component': 'cli', 'wrapper': 'root',
                'modules': ['cli', 'shared'], 'files': files}
        raw = json.dumps(view, sort_keys=True, separators=(',', ':')).encode()
        marker = root / '.base-production-source.json'
        marker.write_bytes(raw)
        sha = hashlib.sha256(raw).hexdigest()
        output = owner / 'outputs'
        options = ['--offline', '--no-daemon', '--max-workers=2', '--console=plain',
                   '--configuration-cache', '--configuration-cache-problems=fail',
                   '--project-cache-dir', str(owner / 'project-cache'),
                   '-PbaseProductionRole=cli-main-v1', '-PbaseProductionSourceSha256=' + sha,
                   '-PbaseProductionOutput=' + str(output)]
        command = [str(Path(java_home) / 'bin/java'), '-cp', str(launchers[0]), 'org.gradle.launcher.GradleMain']
        environment = {**os.environ, 'SOURCE_DATE_EPOCH': '0'}

        def run(tasks, *, success=True, more=()):
            result = subprocess.run([*command, *tasks, *options, *more], cwd=root, env=environment,
                                    capture_output=True, text=True, timeout=90)
            text = result.stdout + result.stderr
            if success:
                self.assertEqual(result.returncode, 0, text[-14000:])
            else:
                self.assertNotEqual(result.returncode, 0, text[-14000:])
            return text

        run([':cli:test'], success=False)
        run([':cli:bootJar'], success=False, more=['-PbaseProductionSourceSha256=' + '0' * 64])
        index = run([':cli:qualityDependencyInputs', ':shared:qualityDependencyInputs'])
        self.assertNotIn('> Task :cli:compileJava', index)
        for module in ['cli', 'shared']:
            data = json.loads((output / module / 'quality/gradle-9.6.1-dependency-inputs.json').read_bytes())
            self.assertEqual(data['configuration_graph'], {'profile': 'production-cli-v1', 'projects': [':', ':cli', ':shared']})
            self.assertEqual(data['roles']['unit'], [])
        self.assertFalse((output / 'cli/classes').exists())
        first = run([':cli:bootJar'])
        self.assertNotIn('> Task :cli:test', first)
        self.assertNotIn('> Task :cli:compileTestJava', first)
        jar = output / 'cli/libs/cli.jar'
        with zipfile.ZipFile(jar) as archive:
            self.assertIn('fixture/Cli.class', archive.namelist())
        before = (hashlib.sha256(jar.read_bytes()).hexdigest(), jar.stat().st_mtime_ns)
        second = run([':cli:bootJar'])
        self.assertIn('Configuration cache entry reused', second)
        self.assertIn(':cli:bootJar UP-TO-DATE', second)
        self.assertEqual((hashlib.sha256(jar.read_bytes()).hexdigest(), jar.stat().st_mtime_ns), before)
        main = root / 'cli/src/main/java/fixture/Cli.java'
        main.write_bytes(main.read_bytes() + b'\n// changed with an old timestamp\n')
        os.utime(main, (1, 1))
        rejected = run([':cli:bootJar'], success=False)
        self.assertIn('Production source content/budget changed', rejected)
        self.assertEqual((hashlib.sha256(jar.read_bytes()).hexdigest(), jar.stat().st_mtime_ns), before)
        marker.unlink()
        self.assertIn('Private production requires its captured source control', run([':cli:bootJar'], success=False))
