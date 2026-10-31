"""Manual Gradle API qualification; builtin plugins, no app compilation/network."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class NativeSourceSetTests(unittest.TestCase):
    def test_unit_manual_shared_sources_and_cache_invalidation(self):
        distribution = os.environ.get('PDLC_GRADLE_HOME')
        java_home = os.environ.get('JAVA_HOME')
        if not distribution or not java_home:
            self.skipTest('Manual native check requires PDLC_GRADLE_HOME and pinned JAVA_HOME; caller owns host slot')
        home = Path(distribution).resolve()
        java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        launchers = list((home / 'lib').glob('gradle-gradle-cli-main-*.jar'))
        self.assertEqual(len(launchers), 1, 'Use one installed pinned Gradle distribution')
        self.assertIn(home.name, ('gradle-9.6.1', 'gradle-9.8.2'))
        self.assertIn('JAVA_VERSION="25.0.4.1"', (Path(java_home) / 'release').read_text())
        temporary = tempfile.TemporaryDirectory(prefix='java-source-set-contract-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve()
        (root / 'settings.gradle').write_text("rootProject.name='lane-fixture'\ninclude 'backend','telegram-bot'\n")
        (root / 'gradle').mkdir()
        for name in ('test-lanes.gradle', 'test-topology.gradle', 'test-membership.gradle'):
            (root / 'gradle' / name).write_bytes((ROOT / 'gradle' / name).read_bytes())
        inventory = {'schema': 'java-test-lanes/v1', 'files': {}, 'support': {}}
        for module in ('backend', 'telegram-bot'):
            source = root / module / 'src/test/java/fixture'
            source.mkdir(parents=True)
            for name, role in (('UnitExample', 'unit'), ('ManualExample', 'integration'),
                               ('SharedFixture', 'shared'), ('UnitFixture', 'unit'), ('ManualFixture', 'manual')):
                relative = module + '/src/test/java/fixture/' + name + '.java'
                (root / relative).write_text('package fixture; class ' + name + ' {}\n')
                inventory['files' if name.endswith('Example') else 'support'][relative] = role
            if module == 'telegram-bot':
                for name, role, group in (('GroovyUnit', 'unit', 'files'),
                                           ('GroovyManual', 'integration', 'files'),
                                           ('GroovySharedFixture', 'shared', 'support')):
                    path = root / module / 'src/test/groovy/fixture' / (name + '.groovy')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('package fixture; class ' + name + ' {}\n')
                    inventory[group][path.relative_to(root).as_posix()] = role
            resources = root / module / 'src/test/resources'
            resources.mkdir()
            (resources / 'fixture.txt').write_text('one shared resource')
            for name in ('unit-only.jar', 'manual-only.jar'):
                with zipfile.ZipFile(root / module / name, 'w') as archive:
                    archive.writestr('marker.txt', name)
            (root / module / 'build.gradle').write_text(
                "import groovy.json.JsonOutput\n"
                "plugins { id '" + ('groovy' if module == 'telegram-bot' else 'java') + "' }\n"
                "abstract class DescribeLanes extends DefaultTask {\n"
                " @Input abstract Property<String> getSnapshot()\n"
                " @TaskAction void show() { println 'LANE_LAYOUT=' + snapshot.get() }\n}\n"
                "dependencies { testImplementation files('unit-only.jar') }\n"
                "apply from: '../gradle/test-lanes.gradle'\n"
                "dependencies { manualTestImplementation files('manual-only.jar') }\n"
                "def value = [module:project.name, unit:sourceSets.test.allJava.files*.name.sort(),\n"
                " manual:sourceSets.manualTest.allJava.files*.name.sort(), shared:sourceSets.testSupport.allJava.files*.name.sort(),\n"
                " groovyUnit:plugins.hasPlugin('groovy') ? sourceSets.test.groovy.files*.name.sort() : [],\n"
                " groovyManual:plugins.hasPlugin('groovy') ? sourceSets.manualTest.groovy.files*.name.sort() : [],\n"
                " groovyShared:plugins.hasPlugin('groovy') ? sourceSets.testSupport.groovy.files*.name.sort() : [],\n"
                " unitDependencies:configurations.testCompileClasspath.files*.name.sort(),\n"
                " manualDependencies:configurations.manualTestCompileClasspath.files*.name.sort(),\n"
                " manualClasspath:sourceSets.manualTest.runtimeClasspath.files*.canonicalPath.sort(),\n"
                " manualResourceRoots:sourceSets.manualTest.resources.srcDirs*.canonicalPath.sort(),\n"
                " sharedResourceRoots:sourceSets.testSupport.resources.srcDirs*.canonicalPath.sort(),\n"
                " testResources:tasks.named('processTestResources').get().destinationDir.canonicalPath,\n"
                " unitClasses:sourceSets.test.output.classesDirs.files*.canonicalPath.sort()]\n"
                "tasks.register('describeLanes', DescribeLanes) { snapshot = JsonOutput.toJson(value) }\n")
        (root / 'gradle/test-lanes.json').write_text(json.dumps(inventory))
        client = [str(java), '-Xms64m', '-Xmx64m', '-XX:ActiveProcessorCount=1', '-jar', str(launchers[0]),
                  '--offline', '--no-daemon', '--no-watch-fs', '--max-workers=1',
                  '-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=1',
                  '--configuration-cache', '--configuration-cache-problems=fail', '--console=plain']
        commands = []
        def invoke(*arguments):
            result = subprocess.run([*client, *arguments], cwd=root, env=dict(os.environ),
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                    encoding='utf-8', errors='replace', timeout=120)
            commands.append({'arguments': list(arguments), 'exit_code': result.returncode, 'output': result.stdout})
            if os.environ.get('PDLC_SOURCESET_EVIDENCE'):
                path = Path(os.environ['PDLC_SOURCESET_EVIDENCE'])
                path.write_text(json.dumps({'distribution': str(home), 'commands': commands}, indent=2) + '\n')
            return result
        tasks = (':backend:describeLanes', ':backend:verifyTestCompilationInventory',
                 ':telegram-bot:describeLanes', ':telegram-bot:verifyTestCompilationInventory')
        first = invoke(*tasks)
        self.assertEqual(first.returncode, 0, first.stdout)
        rows = [json.loads(line.split('=', 1)[1]) for line in first.stdout.splitlines() if line.startswith('LANE_LAYOUT=')]
        self.assertEqual(len(rows), 2, first.stdout)
        for row in rows:
            self.assertIn('UnitExample.java', row['unit'])
            self.assertIn('UnitFixture.java', row['unit'])
            self.assertEqual(row['manual'], ['ManualExample.java', 'ManualFixture.java'])
            self.assertEqual(row['shared'], ['SharedFixture.java'])
            self.assertEqual(row['unitDependencies'], ['unit-only.jar'])
            self.assertEqual(row['manualDependencies'], ['manual-only.jar', 'unit-only.jar'])
            self.assertFalse(set(row['manualClasspath']) & set(row['unitClasses']))
            self.assertIn(row['testResources'], row['manualClasspath'])
            self.assertEqual(row['manualResourceRoots'], [])
            self.assertEqual(row['sharedResourceRoots'], [])
            groovy = row['module'] == 'telegram-bot'
            self.assertEqual(row['groovyUnit'], ['GroovyUnit.groovy'] if groovy else [])
            self.assertEqual(row['groovyManual'], ['GroovyManual.groovy'] if groovy else [])
            self.assertEqual(row['groovyShared'], ['GroovySharedFixture.groovy'] if groovy else [])
        warm = invoke(*tasks)
        self.assertEqual(warm.returncode, 0, warm.stdout)
        self.assertIn('Reusing configuration cache.', warm.stdout)
        graph = invoke(':backend:test', ':telegram-bot:test', '--dry-run')
        self.assertEqual(graph.returncode, 0, graph.stdout)
        self.assertNotIn('compileManualTest', graph.stdout)
        self.assertIn('compileTestSupportJava', graph.stdout)
        self.assertIn('compileTestGroovy', graph.stdout)
        manual = invoke(':backend:integrationTest', ':telegram-bot:slowTest', '--dry-run')
        self.assertEqual(manual.returncode, 0, manual.stdout)
        self.assertIn('compileManualTestJava', manual.stdout)
        self.assertIn('compileManualTestGroovy', manual.stdout)
        self.assertIn('compileTestSupportGroovy', manual.stdout)
        self.assertIn('processTestResources', manual.stdout)
        self.assertNotIn(':backend:compileTestJava ', manual.stdout)
        self.assertNotIn(':telegram-bot:compileTestGroovy ', manual.stdout)
        self.assertFalse(any(root.glob('*/build/classes/**')), 'Metadata/dry-run must not compile fixtures or the app')
        (root / 'backend/src/test/java/fixture/Unreviewed.java').write_text('package fixture; class Unreviewed {}')
        rejected = invoke(*tasks)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn('Classify every test/support source before compilation', rejected.stdout)


if __name__ == '__main__':
    unittest.main()
