"""Exercise real CLI exit codes and the installed driver, without external services."""
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest


class CliTests(unittest.TestCase):
    def invoke(self, values):
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("SEATFLOW_CHECK_MYSQL_")}
        env.update(values)
        return subprocess.run([sys.executable, "-m", "tools.consistency", "7"],
                              cwd=Path(__file__).resolve().parents[2], env=env,
                              capture_output=True, text=True, timeout=12)

    def test_pinned_mysql_driver_is_installed(self):
        self.assertEqual("1.1.2", importlib.metadata.version("PyMySQL"))

    def test_missing_configuration_is_explicit_error(self):
        result = self.invoke({})
        self.assertEqual(2, result.returncode)
        self.assertEqual("CHECK_CONFIGURATION", json.loads(result.stderr)["error"])
        self.assertEqual("", result.stdout)

    def test_refused_database_connection_is_sanitized(self):
        result = self.invoke({"SEATFLOW_CHECK_MYSQL_HOST": "127.0.0.1",
            "SEATFLOW_CHECK_MYSQL_PORT": "1", "SEATFLOW_CHECK_MYSQL_DATABASE": "fictional_db",
            "SEATFLOW_CHECK_MYSQL_USER": "fictional_private_user",
            "SEATFLOW_CHECK_MYSQL_PASSWORD": "fictional_private_password"})
        self.assertEqual(2, result.returncode)
        self.assertEqual("CHECK_DATABASE", json.loads(result.stderr)["error"])
        self.assertNotIn("fictional_private", result.stderr + result.stdout)
