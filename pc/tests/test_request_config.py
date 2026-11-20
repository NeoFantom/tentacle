from __future__ import annotations

import os
import tempfile
import tomllib
import unittest
from pathlib import Path
from unittest import mock

from tentacle import config


class RequestsConfigTest(unittest.TestCase):
    def test_defaults_and_path_expansion(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"TENTACLE_TEST_ROOT": tmp}):
                cfg = config.from_dict({"requests": {
                    "repo": "$TENTACLE_TEST_ROOT/repo",
                    "prompt_template": "$TENTACLE_TEST_ROOT/prompt.txt",
                }})
                self.assertEqual(cfg.requests.repo_path, Path(tmp) / "repo")
                self.assertEqual(cfg.requests.prompt_template_path, Path(tmp) / "prompt.txt")
        self.assertEqual(cfg.requests.shell, ["bash", "-lc"])
        self.assertEqual(cfg.requests.push, "git push")
        self.assertIn(".github/**", cfg.requests.protected)

    def test_set_values_preserves_shell_command_characters(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.toml"
            config.set_values(path, "requests", {
                "command": 'agent --label "change #1"',
                "shell": ["bash", "-ic"],
            })
            values = tomllib.loads(path.read_text(encoding="utf-8"))["requests"]
        self.assertEqual(values["command"], 'agent --label "change #1"')
        self.assertEqual(values["shell"], ["bash", "-ic"])

    def test_rejects_invalid_values(self):
        invalid = [
            {"command": ["agent"]},
            {"shell": []},
            {"shell": ["bash", ""]},
            {"timeout_s": 0},
            {"timeout_s": True},
            {"push": "  "},
            {"protected": [".github/**", 3]},
        ]
        for values in invalid:
            with self.subTest(values=values):
                with self.assertRaises(config.ConfigError):
                    config.from_dict({"requests": values})


if __name__ == "__main__":
    unittest.main()
