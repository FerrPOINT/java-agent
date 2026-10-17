"""Test classification through the inventory validator, without a JVM or service."""

import json
from pathlib import Path
import tempfile
import unittest

from scripts.check_test_lanes import validate


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


if __name__ == "__main__":
    unittest.main()
