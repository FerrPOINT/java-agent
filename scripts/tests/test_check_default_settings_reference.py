"""Regression tests for the shipped-default documentation reference."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "check_default_settings_reference.py"
spec = importlib.util.spec_from_file_location("check_default_settings_reference", SCRIPT)
assert spec is not None and spec.loader is not None
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class DefaultSettingsReferenceTests(unittest.TestCase):
    def test_reference_covers_every_shipped_yaml_leaf(self) -> None:
        self.assertEqual(mod.verify(), [])

    def test_missing_leaf_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reference = Path(directory) / "reference.md"
            reference.write_text("| `agent.known` | `true` |\n")
            config = Path(directory) / "application.yml"
            config.write_text("agent:\n  known: true\n  undocumented: false\n")
            with mock.patch.object(mod, "REFERENCE", reference), mock.patch.object(
                mod, "CONFIG_FILES", (config,)
            ), mock.patch.object(mod, "JAVA_PROPERTY_FILES", ()), mock.patch.object(
                mod, "VALUE_SOURCE_DIRS", ()
            ):
                self.assertEqual(mod.verify(), [
                    "Missing YAML defaults in docs/default-settings.md:",
                    "- agent.undocumented",
                ])


if __name__ == "__main__":
    unittest.main()
