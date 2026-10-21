"""Test classification through the inventory validator, without a JVM or service."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from scripts.check_test_lanes import compilation_layout, validate, without_comments


def processor_consumers(root):
    """Read the complete physical test graph, including shared/manual helpers."""
    consumers = []
    for lanes in compilation_layout(root).values():
        for names in lanes.values():
            for name in names:
                source = without_comments((root / name).read_text(encoding='utf-8'))
                direct = re.search(r'(?m)^\s*import\s+(?:static\s+)?(?:lombok\.|'
                                   r'org\.mapstruct\.(?:Mapper|MapperConfig)\b)'
                                   r'|@lombok\.|@org\.mapstruct\.(?:Mapper|MapperConfig)\b', source)
                wildcard = (re.search(r'(?m)^\s*import\s+org\.mapstruct\.\*', source)
                            and re.search(r'@(?:Mapper|MapperConfig)\b', source))
                if direct or wildcard:
                    consumers.append(name)
    return consumers


class TestLaneTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def inventory(self, body, tier="unit"):
        relative = "backend/src/test/java/fixture/BoundaryTest.java"
        source = self.root / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(body, encoding="utf-8")
        inventory = self.root / "gradle/test-lanes.json"
        inventory.parent.mkdir(exist_ok=True)
        inventory.write_text(json.dumps({"files": {relative: tier}}), encoding="utf-8")

    def test_spring_junit_context_is_not_unit(self):
        for annotation in ("SpringJUnitConfig", "SpringJUnitWebConfig"):
            with self.subTest(annotation=annotation):
                self.inventory("@" + annotation + " class BoundaryTest { @Test void test() {} }")
                with self.assertRaisesRegex(ValueError, "cannot be unit"):
                    validate(self.root)

    def test_real_application_context_is_not_unit(self):
        for context in ("StaticApplicationContext", "AnnotationConfigApplicationContext",
                        "org.springframework.context.support.GenericApplicationContext"):
            with self.subTest(context=context):
                self.inventory("class BoundaryTest { @Test void test() { new " + context + "(); } }")
                with self.assertRaisesRegex(ValueError, "cannot be unit"):
                    validate(self.root)

    def test_mock_context_and_standalone_mockmvc_remain_unit(self):
        self.inventory("""import org.springframework.context.support.StaticApplicationContext;
            class BoundaryTest { @Test void test() {
                var context = Mockito.mock(StaticApplicationContext.class);
                var mvc = MockMvcBuilders.standaloneSetup(new Controller()).build();
            } }""")
        self.assertEqual(validate(self.root)["unit"], 1)

    def test_explicit_manual_tag_cannot_be_reclassified_unit(self):
        for tier in ("integration", "slow", "live", "e2e"):
            with self.subTest(tier=tier):
                self.inventory('@Tag("' + tier + '") class BoundaryTest { @Test void test() {} }')
                with self.assertRaisesRegex(ValueError, "cannot be unit"):
                    validate(self.root)

    def test_real_context_remains_in_manual_inventory(self):
        self.inventory("@SpringJUnitConfig class BoundaryTest { @Test void test() {} }", "integration")
        self.assertEqual(validate(self.root), {"unit": 0, "integration": 1, "slow": 0, "live": 0})

    def test_existing_direct_network_boundary_still_rejected(self):
        self.inventory("class BoundaryTest { @Test void test() { new MockWebServer(); } }")
        with self.assertRaisesRegex(ValueError, "cannot be unit"):
            validate(self.root)

    def support(self, body, tier=None):
        name = 'backend/src/test/java/fixture/Fixture.java'
        path = self.root / name
        path.write_text(body, encoding='utf-8')
        if tier is not None:
            inventory_path = self.root / 'gradle/test-lanes.json'
            data = json.loads(inventory_path.read_text())
            data['support'] = {name: tier}
            inventory_path.write_text(json.dumps(data))
        return name

    def manual_test(self):
        name = 'backend/src/test/java/fixture/ManualBoundaryTest.java'
        (self.root / name).write_text('class ManualBoundaryTest { @Test void boundary() {} }')
        path = self.root / 'gradle/test-lanes.json'
        data = json.loads(path.read_text())
        data['files'][name] = 'integration'
        path.write_text(json.dumps(data))
        return name

    def test_unclassified_support_source_does_not_silently_join_unit_compilation(self):
        self.inventory('class BoundaryTest { @Test void test() {} }')
        self.support('class Fixture {}')
        with self.assertRaisesRegex(ValueError, 'support source'):
            validate(self.root)

    def test_shared_fixture_has_one_owner_without_compiling_manual_test_for_unit(self):
        self.inventory('class BoundaryTest { @Test void test() { new Fixture(); } }')
        shared = self.support('class Fixture {}', 'shared')
        manual = self.manual_test()
        self.assertEqual(validate(self.root), {'unit': 1, 'integration': 1, 'slow': 0, 'live': 0})
        layout = compilation_layout(self.root)['backend']
        self.assertEqual(layout['shared'], [shared])
        self.assertEqual(layout['manual'], [manual])
        self.assertNotIn(manual, layout['unit'])

    def test_boundary_fixture_cannot_leak_into_unit_or_shared_classpath(self):
        self.inventory('class BoundaryTest { @Test void test() {} }')
        for tier in ('unit', 'shared'):
            with self.subTest(tier=tier):
                self.support('class Fixture { ProcessBuilder process; }', tier)
                with self.assertRaisesRegex(ValueError, 'Boundary helper'):
                    validate(self.root)
        self.support('class Fixture { ProcessBuilder process; }', 'manual')
        self.assertEqual(validate(self.root)['unit'], 1)

    def test_reflective_manual_fixture_dependency_is_rejected_but_comment_is_allowed(self):
        self.inventory('class BoundaryTest { @Test void test() { Class.forName("fixture.ManualBoundaryTest$Fixture"); } }')
        self.manual_test()
        with self.assertRaisesRegex(ValueError, 'extract a shared fixture'):
            validate(self.root)
        self.inventory('/** ManualBoundaryTest covers the integration path. */ class BoundaryTest { @Test void test() {} }')
        self.manual_test()
        self.assertEqual(validate(self.root)['unit'], 1)

    def test_stale_or_overlapping_support_inventory_is_rejected(self):
        self.inventory('class BoundaryTest { @Test void test() {} }')
        helper = self.support('class Fixture {}', 'shared')
        (self.root / helper).unlink()
        with self.assertRaisesRegex(ValueError, 'stale support'):
            validate(self.root)
        path = self.root / 'gradle/test-lanes.json'
        data = json.loads(path.read_text())
        data['support'] = {next(iter(data['files'])): 'shared'}
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'disjoint'):
            validate(self.root)

    def test_manual_compilation_cannot_use_unit_only_fixture(self):
        self.inventory('class BoundaryTest { @Test void test() {} }')
        self.support('class Fixture {}', 'unit')
        manual = self.manual_test()
        (self.root / manual).write_text('class ManualBoundaryTest { @Test void test() { new Fixture(); } }')
        with self.assertRaisesRegex(ValueError, 'Manual/shared source depends on a unit-only class'):
            validate(self.root)
        self.support('class Fixture {}', 'shared')
        self.assertEqual(validate(self.root)['integration'], 1)

    def test_all_reviewed_test_compilations_need_no_lombok_or_mapstruct_generation(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(processor_consumers(root), [],
                         'Review test processor dependencies before adding generated test classes')

    def test_processor_review_distinguishes_generation_from_existing_mapper_api(self):
        cases = [
            ('import org.mapstruct.factory.Mappers;\nclass Fixture {}', False),
            ('import org.mapstruct.ReportingPolicy;\nclass Fixture { Object value = ReportingPolicy.ERROR; }', False),
            ('import org.mapstruct.*;\nclass Fixture { Object value = ReportingPolicy.ERROR; }', False),
            ('// import lombok.Getter;\n/* @org.mapstruct.Mapper */ class Fixture {}', False),
            ('import lombok.Getter;\n@Getter class Fixture {}', True),
            ('@lombok.Getter class Fixture {}', True),
            ('import org.mapstruct.Mapper;\n@Mapper interface Fixture {}', True),
            ('import org.mapstruct.MapperConfig;\n@MapperConfig interface Fixture {}', True),
            ('@org.mapstruct.Mapper interface Fixture {}', True),
            ('@org.mapstruct.MapperConfig interface Fixture {}', True),
            ('import org.mapstruct.*;\n@Mapper interface Fixture {}', True),
            ('import org.mapstruct.*;\n@MapperConfig interface Fixture {}', True),
        ]
        name = 'backend/src/test/java/Fixture.java'
        for lane in ('unit', 'manual', 'shared'):
            for source, required in cases:
                with self.subTest(lane=lane, source=source), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    target = root / name
                    target.parent.mkdir(parents=True)
                    target.write_text(source, encoding='utf-8')
                    inventory = {'files': {}, 'support': {}}
                    if lane == 'shared':
                        inventory['support'][name] = 'shared'
                    else:
                        inventory['files'][name] = 'unit' if lane == 'unit' else 'integration'
                    (root / 'gradle').mkdir()
                    (root / 'gradle/test-lanes.json').write_text(json.dumps(inventory), encoding='utf-8')
                    self.assertEqual(processor_consumers(root), [name] if required else [])


if __name__ == "__main__":
    unittest.main()
