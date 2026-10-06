"""Regression tests for the parity-dashboard runtime metadata lookup."""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def load_module(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / "parity-dashboard.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    finally:
        sys.modules.pop(name, None)
    return module


class ParityDashboardRuntimeTest(unittest.TestCase):
    def test_runtime_version_file_is_inside_latest_release(self) -> None:
        dashboard = load_module("parity_dashboard_runtime")

        self.assertEqual(dashboard.RUNTIME_LATEST / "VERSION", dashboard.RUNTIME_VERSION_FILE)


if __name__ == "__main__":
    unittest.main()
