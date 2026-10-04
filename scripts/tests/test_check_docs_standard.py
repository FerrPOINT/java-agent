"""Tests for scripts/check_docs_standard.py (docs ratchet linter)."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_docs_standard.py"
spec = importlib.util.spec_from_file_location("check_docs_standard", SCRIPT)
assert spec is not None and spec.loader is not None
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

BIG_BODY = "\n".join(f"    int f{i} = 0;" for i in range(60))


def make_module(root: Path, name: str) -> Path:
    src = root / name / "src" / "main" / "java" / "demo"
    src.mkdir(parents=True)
    return src


class PatchedRoot(unittest.TestCase):
    """Base: swap mod.ROOT to a temp dir for the duration of the test."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self._orig_root, self._orig_baseline = mod.ROOT, mod.BASELINE
        mod.ROOT = self.root
        mod.BASELINE = self.root / "docs" / "standards" / "coverage-baseline.json"

    def tearDown(self) -> None:
        mod.ROOT, mod.BASELINE = self._orig_root, self._orig_baseline
        self._tmp.cleanup()


class CoverageGateTests(PatchedRoot):
    def setUp(self) -> None:
        super().setUp()
        mod.BASELINE.parent.mkdir(parents=True)
        mod.BASELINE.write_text(json.dumps({
            "doc_violations": {name: mod.check_module(name) for name in mod.MODULES},
            "line_coverage": {"backend": 79.38, "telegram-bot": 82.47},
        }))

    def report(self, module: str, covered: int = 85, missed: int = 15,
               counter_type: str = "LINE") -> None:
        report = self.root / module / "build/reports/jacoco/test/jacocoTestReport.xml"
        report.parent.mkdir(parents=True)
        report.write_text(f'<report><counter type="{counter_type}" covered="{covered}" missed="{missed}"/></report>')

    def run_gate(self) -> int:
        with mock.patch("sys.argv", [str(SCRIPT), "--check-coverage"]), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return mod.main()

    def test_missing_required_reports_fail(self) -> None:
        self.assertEqual(self.run_gate(), 1)

    def test_missing_bot_report_fails_even_with_passing_backend_coverage(self) -> None:
        self.report("backend")
        self.assertEqual(self.run_gate(), 1)

    def test_report_without_line_counter_fails(self) -> None:
        self.report("backend")
        self.report("telegram-bot", counter_type="BRANCH")
        self.assertEqual(self.run_gate(), 1)

    def test_real_passing_reports_do_not_require_a_report_for_unconfigured_cli(self) -> None:
        self.report("backend")
        self.report("telegram-bot")
        self.assertEqual(self.run_gate(), 0)

    def test_coverage_below_the_existing_floor_fails(self) -> None:
        self.report("backend")
        self.report("telegram-bot", covered=82, missed=18)
        self.assertEqual(self.run_gate(), 1)


class ClassJavadocTests(PatchedRoot):
    def test_big_class_without_javadoc_is_doc001(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Big.java").write_text("public class Big {\n" + BIG_BODY + "\n}\n")
        counts = mod.check_module("backend")
        self.assertEqual(counts["DOC001"], 1)

    def test_small_record_without_javadoc_is_clean(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Pair.java").write_text("public record Pair(int a, int b) {}\n")
        counts = mod.check_module("backend")
        self.assertEqual(counts["DOC001"], 0)

    def test_big_class_with_javadoc_is_clean(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Big.java").write_text("/** Real contract. */\npublic class Big {\n" + BIG_BODY + "\n}\n")
        counts = mod.check_module("backend")
        self.assertEqual(counts["DOC001"], 0)


class BannedPhraseTests(PatchedRoot):
    def test_filler_phrase_in_javadoc_is_doc002(self) -> None:
        src = make_module(self.root, "backend")
        (src / "C.java").write_text(
            "/** This class is responsible for management of things. */\npublic record C(int x) {}\n"
        )
        counts = mod.check_module("backend")
        self.assertGreaterEqual(counts["DOC002"], 1)

    def test_concise_javadoc_has_no_doc002(self) -> None:
        src = make_module(self.root, "backend")
        (src / "C.java").write_text("/** Binds review summaries to the chat surface. */\npublic record C(int x) {}\n")
        counts = mod.check_module("backend")
        self.assertEqual(counts["DOC002"], 0)


class AgentToolDescriptionTests(PatchedRoot):
    def test_short_tool_description_is_doc003(self) -> None:
        src = make_module(self.root, "backend")
        (src / "T.java").write_text(
            "@AgentTool(\n    name = \"demo\",\n    description = \"Does things\"\n)\npublic class T {\n}\n"
        )
        counts = mod.check_module("backend")
        self.assertEqual(counts["DOC003"], 1)


class BaselineRatchetTests(PatchedRoot):
    def test_ratchet_breach_fails_with_exit_1(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Big.java").write_text("public class Big {\n" + BIG_BODY + "\n}\n")
        mod.BASELINE.parent.mkdir(parents=True, exist_ok=True)
        mod.BASELINE.write_text(
            json.dumps({"doc_violations": {"backend": {"DOC001": 0, "DOC002": 0, "DOC003": 0}}})
        )
        with mock.patch("sys.argv", ["prog"]):
            rc = mod.main()
        self.assertEqual(rc, 1)

    def test_within_baseline_passes_with_exit_0(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Big.java").write_text("public class Big {\n" + BIG_BODY + "\n}\n")
        mod.BASELINE.parent.mkdir(parents=True, exist_ok=True)
        mod.BASELINE.write_text(
            json.dumps({"doc_violations": {"backend": {"DOC001": 1, "DOC002": 0, "DOC003": 0}}})
        )
        with mock.patch("sys.argv", ["prog"]):
            rc = mod.main()
        self.assertEqual(rc, 0)

    def test_update_baseline_rewrites_counts(self) -> None:
        src = make_module(self.root, "backend")
        (src / "Big.java").write_text("public class Big {\n" + BIG_BODY + "\n}\n")
        mod.BASELINE.parent.mkdir(parents=True, exist_ok=True)
        with mock.patch("sys.argv", ["prog", "--update-baseline"]):
            rc = mod.main()
        self.assertEqual(rc, 0)
        data = json.loads(mod.BASELINE.read_text())
        self.assertEqual(data["doc_violations"]["backend"]["DOC001"], 1)


if __name__ == "__main__":
    unittest.main()
