from __future__ import annotations

import os
import tempfile
import tomllib
import unittest
from pathlib import Path
from unittest import mock

from tentacle import config


class ConfigTests(unittest.TestCase):
    def test_manual_sections_and_defaults(self):
        cfg = config.from_dict({
            "phone": {"url": "http://example.com:8765", "find_timeout_s": 30},
            "storage": {"data_dir": "/data"},
            "calls": {"timezone": "Asia/Shanghai"},
            "asr": {"backend": "none"},
            "sources": [{"name": "calls", "path": "Recordings"}],
        })
        self.assertEqual(cfg.phone.find_timeout_s, 30)
        self.assertEqual(cfg.calls.timezone, "Asia/Shanghai")
        self.assertEqual(cfg.sources[0].path, "Recordings")
        with self.assertRaisesRegex(config.ConfigError, "unknown section"):
            config.from_dict({"sync": {}})

    def test_paths_token_masking_and_init(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            with mock.patch.dict(os.environ, {"TENTACLE_CONFIG": str(directory / "one.toml")}, clear=True):
                self.assertEqual(config.default_config_path(), directory / "one.toml")
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(directory)}, clear=True):
                self.assertEqual(config.default_config_path(), directory / "tentacle/config.toml")
            path = directory / "custom/config.toml"
            self.assertEqual(config.init(path), path)
            self.assertTrue(path.is_file())
            with self.assertRaises(config.ConfigError):
                config.init(path)
            cfg = config.load(path)
            cfg.phone.token = "secret-token"
            self.assertEqual(config.masked(cfg)["phone"]["token"], "secr…")
            token_file = directory / "token"
            token_file.write_text("from-file\n")
            self.assertEqual(config.PhoneConfig(token_file=str(token_file)).resolved_token(), "from-file")
            with mock.patch.dict(os.environ, {"PHONE_TOKEN": "from-env"}):
                self.assertEqual(config.PhoneConfig(token_env="PHONE_TOKEN").resolved_token(), "from-env")

    def test_example_contains_only_manual_sections(self):
        text = config.example_text()
        cfg = config.from_dict(tomllib.loads(text))
        self.assertIsInstance(cfg.calls, config.CallsConfig)
        self.assertIn("[calls]", text)
        self.assertIn("find_timeout_s = 120", text)
        for section in ("sync", "agent", "analyze", "projects", "tasks", "pipeline", "notify"):
            self.assertNotIn(f"[{section}]", text)


if __name__ == "__main__":
    unittest.main()
