"""Reject unsafe mount models even when path environment values remain valid."""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "compose_auth_guard", Path(__file__).resolve().parents[1] / "check-compose-backend-auth.py",
)
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


def fixture() -> dict:
    backend = {
        "environment": {"AGENT_CORE_WORKING_DIRECTORY": "/workspace", "AGENT_ATTACHMENTS_CACHE_ROOT": "/artifacts"},
        "volumes": [{"type": "volume", "source": "work", "target": "/workspace"},
                    {"type": "volume", "source": "artifacts", "target": "/artifacts"}],
    }
    bot = copy.deepcopy(backend)
    bot["environment"] = {"BOT_WORKING_DIRECTORY": "/workspace", "AGENT_ATTACHMENTS_CACHE_ROOT": "/artifacts",
                          "BOT_BACKEND_URL": "http://backend:8090", "BOT_BACKEND_API_KEY": "${TEST_KEY:-}"}
    return {"services": {"backend": backend, "bot": bot}, "volumes": {"work": {}, "artifacts": {}, "other": {}}}


class ComposeBackendAuthTest(unittest.TestCase):
    def validate(self, model, *, require_bot=True):
        GUARD.validate_compose(model, Path("fixture.yml"), require_bot=require_bot)

    def test_accepts_writable_shared_named_volumes(self):
        self.validate(fixture())

    def test_accepts_backend_only_profile(self):
        model = fixture()
        del model["services"]["bot"]
        self.validate(model, require_bot=False)

    def test_missing_mount_is_rejected_with_environment_still_present(self):
        for name in ("backend", "bot"):
            for index in (0, 1):
                with self.subTest(service=name, index=index):
                    model = fixture()
                    del model["services"][name]["volumes"][index]
                    with self.assertRaisesRegex(AssertionError, "must mount"):
                        self.validate(model)

    def test_bind_anonymous_undeclared_readonly_and_nocopy_are_rejected(self):
        changes = ({"type": "bind", "source": "/host/work"}, {"source": ""},
                   {"source": "undeclared"}, {"read_only": True}, {"volume": {"nocopy": True}},
                   {"volume": {"subpath": "different-tree"}})
        for name in ("backend", "bot"):
            for index in (0, 1):
                for change in changes:
                    with self.subTest(service=name, index=index, change=change):
                        model = fixture()
                        model["services"][name]["volumes"][index].update(change)
                        with self.assertRaises(AssertionError):
                            self.validate(model)

    def test_different_shared_source_is_rejected(self):
        for index in (0, 1):
            model = fixture()
            model["services"]["bot"]["volumes"][index]["source"] = "other"
            with self.assertRaisesRegex(AssertionError, "must share"):
                self.validate(model)

    def test_duplicate_mount_and_wrong_environment_are_rejected(self):
        model = fixture()
        model["services"]["backend"]["volumes"].append(model["services"]["backend"]["volumes"][0].copy())
        with self.assertRaisesRegex(AssertionError, "exactly once"):
            self.validate(model)
        model = fixture()
        model["services"]["bot"]["environment"]["BOT_WORKING_DIRECTORY"] = "/tmp"
        with self.assertRaisesRegex(AssertionError, "must set"):
            self.validate(model)

    def test_missing_bot_and_backend_auth_are_rejected(self):
        model = fixture()
        del model["services"]["bot"]
        with self.assertRaisesRegex(AssertionError, "service is missing"):
            self.validate(model)
        for key in ("BOT_BACKEND_URL", "BOT_BACKEND_API_KEY"):
            model = fixture()
            del model["services"]["bot"]["environment"][key]
            with self.assertRaisesRegex(AssertionError, "backend URL and API key"):
                self.validate(model)

    def test_foreign_user_override_is_rejected(self):
        for name in ("backend", "bot"):
            model = fixture()
            model["services"][name]["user"] = "20002:20002"
            with self.assertRaisesRegex(AssertionError, "agent identity"):
                self.validate(model)
