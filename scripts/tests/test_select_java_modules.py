import unittest

from scripts.select_java_modules import groups


class JavaModuleSelectionTests(unittest.TestCase):
    def test_cli_and_bot_do_not_run_backend_and_shared_propagates(self):
        for component in ("cli", "bot"):
            self.assertEqual(groups([component]), {"java_backend": False, "java_root": True})
        self.assertEqual(groups(["backend"]), {"java_backend": True, "java_root": False})
        self.assertEqual(groups(["shared"]), {"java_backend": True, "java_root": True})
        self.assertEqual(groups(["docs"]), {"java_backend": False, "java_root": False})
