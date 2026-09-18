import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "verify_readme.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("verify_readme", SCRIPT)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class VerifyReadmeTest(unittest.TestCase):
    def make_repo(self, readme: str, files: dict[str, str] | None = None) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / "README.md").write_text(readme, encoding="utf-8")
        for relative, content in (files or {}).items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        return root

    def test_accepts_readme_with_explicit_anchor_and_local_asset(self) -> None:
        validator = load_validator()
        root = self.make_repo(
            '<a name="runtime"></a>\n## Runtime\n![runtime](docs/evidence/runtime.png)\n',
            {"docs/evidence/runtime.png": "not inspected by validator"},
        )

        self.assertEqual([], validator.validate(root))

    def test_rejects_missing_image_and_missing_anchor(self) -> None:
        validator = load_validator()
        root = self.make_repo('[Runtime](#runtime)\n![missing](docs/evidence/nope.png)\n')

        findings = validator.validate(root)

        self.assertIn("RMD002: README.md: missing anchor #runtime", findings)
        self.assertIn("RMD003: README.md: missing local asset docs/evidence/nope.png", findings)

    def test_rejects_placeholders_and_local_filesystem_paths(self) -> None:
        validator = load_validator()
        root = self.make_repo('Run {{PORT}} from /opt/dev/java-agent.\n')

        findings = validator.validate(root)

        self.assertIn("RMD005: README.md: unresolved placeholder", findings)
        self.assertIn("RMD006: README.md: local filesystem path leaked", findings)

    def test_rejects_badge_for_missing_workflow(self) -> None:
        validator = load_validator()
        root = self.make_repo(
            '![CI](https://github.com/FerrPOINT/java-agent/actions/workflows/missing.yml/badge.svg)\n',
            {".github/workflows/ci.yml": "name: CI\n"},
        )

        self.assertEqual(
            ["RMD007: README.md: badge references missing workflow missing.yml"],
            validator.validate(root),
        )


if __name__ == "__main__":
    unittest.main()
