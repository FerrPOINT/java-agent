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



class ValidatorSourceRoutingTests(unittest.TestCase):
    """Exercise the packaged planner and its actual CI projection, without builders."""
    SOURCE = 'scripts/verify_readme.py'
    OWNER = 'tooling:docs-unit'
    MODULE = 'test_verify_readme'

    def plan_validator(self, paths, profile='build'):
        from unittest.mock import patch
        from scripts.vendor import base_quality_selection as selector
        root = Path(__file__).resolve().parents[2]
        def head(repository, *arguments):
            self.assertEqual(repository, root)
            self.assertEqual(arguments, ('rev-parse', 'HEAD'))
            return 'a' * 40
        with patch.object(selector, 'git', side_effect=head):
            return selector.plan(root, profile=profile, changed=paths)

    def test_validator_and_unit_paths_choose_exact_source_checks(self):
        for path in (self.SOURCE, 'scripts/tests/' + self.MODULE + '.py'):
            for profile in ('fast', 'unit', 'build'):
                with self.subTest(path=path, profile=profile):
                    candidate = self.plan_validator([path], profile)
                    expected = {self.OWNER}
                    if path == self.SOURCE and profile == 'fast':
                        expected.add('docs:readme')
                    self.assertEqual({gate['id'] for gate in candidate['gates']}, expected)
                    gate = next(g for g in candidate['gates'] if g['id'] == self.OWNER)
                    self.assertEqual(gate['argv'].count('scripts.tests.' + self.MODULE), 1)
                    self.assertFalse(any(g.get('kind') == 'artifact' for g in candidate['gates']))

    def test_mixed_application_and_unknown_neighbor_keep_builds(self):
        for paths in ([self.SOURCE, 'backend/src/main/java/example/Production.java'],
                      [self.SOURCE, self.SOURCE.replace('.py', '_unreviewed.py')]):
            with self.subTest(paths=paths):
                candidate = self.plan_validator(paths)
                self.assertIn('backend', candidate['components'])
                self.assertTrue(any(g.get('kind') == 'artifact' for g in candidate['gates']))

    def test_ci_projection_keeps_validator_only_out_of_application_jobs(self):
        from unittest.mock import patch
        from scripts.vendor import base_quality_selection as selector
        root = Path(__file__).resolve().parents[2]
        for paths, backend in (([self.SOURCE], 'false'),
                ([self.SOURCE, 'backend/src/main/java/example/Production.java'], 'true')):
            with self.subTest(paths=paths):
                def changed(repository, *arguments):
                    self.assertEqual(arguments, ('diff', '--name-only', '--no-renames', 'a' * 40, 'b' * 40))
                    return '\n'.join(paths)
                with patch.object(selector, 'git', side_effect=changed):
                    result = selector.ci_outputs('pull_request', 'a' * 40, 'b' * 40, repo=root)
                self.assertEqual(result['backend_build'], backend)
                self.assertEqual(result['frontend_build'], 'false')

        from scripts.select_java_modules import groups, root_unit_tasks
        components = self.plan_validator([self.SOURCE])['components']
        self.assertEqual(groups(components), {'java_backend': False, 'java_root': False})
        self.assertEqual(root_unit_tasks(components), '')


if __name__ == "__main__":
    unittest.main()
