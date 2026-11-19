"""Offline integration tests of the registered file commands."""
from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import time
import tomllib
import unittest
from unittest import mock

from tentacle import cli, config
from tests.fakephone import FakePhone


class PhoneCliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name) / "phone"
        cls.root.mkdir()
        (cls.root / "Recordings").mkdir()
        (cls.root / "Recordings" / "one.aac").write_bytes(b"audio one")
        (cls.root / "Recordings" / "two.M4A").write_bytes(b"audio two")
        (cls.root / "Download").mkdir()
        (cls.root / "Download" / "three.wav").write_bytes(b"audio three")
        (cls.root / "Recordings" / "old.mp3").write_bytes(b"old")
        old = time.time() - 100 * 86400
        os.utime(cls.root / "Recordings" / "old.mp3", (old, old))
        (cls.root / "note.txt").write_text("hello\nworld\n", encoding="utf-8")
        (cls.root / ".hidden").write_text("hidden", encoding="utf-8")
        cls.server = FakePhone(cls.root)
        cls.server.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.server.__exit__(None, None, None)
        cls.temporary.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cfg = Path(self.tmp.name) / "config.toml"
        self.cfg.write_text("[phone]\nurl = " + json.dumps(self.server.url)
                            + '\ntoken = "t"\n', encoding="utf-8")

    def run_cli(self, *args, config_path=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = cli.main(["--config", str(config_path or self.cfg), *args])
        return code, stdout.getvalue(), stderr.getvalue()

    def run_json(self, *args):
        code, out, err = self.run_cli(*args, "--json")
        self.assertEqual((code, err), (0, ""))
        return json.loads(out)

    def test_ping_ls_stat_and_human_columns(self):
        self.assertEqual(self.run_json("ping")["app"], "tentacle")
        listing = self.run_json("ls")
        self.assertNotIn(".hidden", [entry["name"] for entry in listing["entries"]])
        self.assertIn(".hidden", [entry["name"] for entry in self.run_json("ls", "-a")["entries"]])
        self.assertEqual(self.run_json("stat", "note.txt")["size"], 12)
        code, out, err = self.run_cli("ls", "Recordings")
        self.assertEqual((code, err), (0, ""))
        self.assertRegex(out, r"f\s+9 B\s+\d{4}-\d\d-\d\d \d\d:\d\d\s+")
        self.assertIn("one.aac", out)
        for command in ("ping", "stat"):
            args = (command, "note.txt") if command == "stat" else (command,)
            code, out, err = self.run_cli(*args)
            self.assertEqual((code, err), (0, ""))
            self.assertTrue(out.strip())

    def test_find_and_recent(self):
        found = self.run_json("find", "Recordings", "--ext", "AAC,m4a", "--since", "2d",
                              "--name", "o", "--limit", "1")
        self.assertEqual(len(found["entries"]), 1)
        self.assertTrue(found["truncated"])
        recent = self.run_json("recent", "--ext", "mp3")
        self.assertEqual(recent["entries"], [])
        all_old = self.run_json("recent", "--ext", "mp3", "--since", "101d")
        self.assertEqual([entry["name"] for entry in all_old["entries"]], ["old.mp3"])
        self.assertEqual(len(self.run_json("find", "--type", "dir", "--max-depth", "1")["entries"]), 2)
        self.assertEqual(self.run_cli("recent")[0], 0)

    def test_get_and_cat(self):
        dest = Path(self.tmp.name) / "download.txt"
        result = self.run_json("get", "note.txt", str(dest))
        self.assertEqual(result["dest"], str(dest))
        self.assertEqual(dest.read_bytes(), b"hello\nworld\n")
        self.assertFalse(Path(str(dest) + ".part").exists())
        self.assertEqual(self.run_json("cat", "note.txt", "--max-bytes", "5")["text"], "hello")
        self.assertEqual(self.run_cli("cat", "note.txt"), (0, "hello\nworld\n", ""))
        self.assertEqual(self.run_cli("get", "note.txt", str(dest))[0], 0)
        original = Path.cwd()
        try:
            os.chdir(self.tmp.name)
            self.assertEqual(self.run_cli("get", "note.txt")[0], 0)
            self.assertEqual(Path("note.txt").read_bytes(), dest.read_bytes())
        finally:
            os.chdir(original)

    def test_pair_creates_missing_config_and_preserves_existing(self):
        missing = Path(self.tmp.name) / "nested" / "new.toml"
        address = self.server.url.replace("http://", "tentacle://") + "?token=t&name=Test%20device"
        code, out, err = self.run_cli("pair", address, "--json", config_path=missing)
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(json.loads(out)["app"], "tentacle")
        loaded = config.load(missing)
        self.assertEqual((loaded.phone.url, loaded.phone.token), (self.server.url, "t"))
        self.assertIn("# tentacle configuration", missing.read_text(encoding="utf-8"))
        code, out, err = self.run_cli("pair", self.server.url, "--token", "t")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("API 1", out)
        self.assertNotIn("token", out)

    def test_bounded_cat_of_large_file_closes_cleanly(self):
        root = Path(self.tmp.name) / "large-phone"
        root.mkdir()
        (root / "large.bin").write_bytes(b"x" * (4 * 1024 * 1024))
        server_stderr = io.StringIO()
        with contextlib.redirect_stderr(server_stderr):
            with FakePhone(root) as server:
                config.set_values(self.cfg, "phone", {"url": server.url, "token": "t"})
                code, out, err = self.run_cli("cat", "large.bin", "--max-bytes", "5")
        self.assertEqual((code, out, err), (0, "xxxxx", ""))
        self.assertEqual(server_stderr.getvalue(), "")

    def test_discover_json_and_valid_toml_suggestions(self):
        result = self.run_json("discover")
        self.assertEqual([item["count"] for item in result["directories"]], [2, 1])
        self.assertEqual(result["directories"][0]["path"], str(self.root / "Recordings"))
        self.assertEqual(result["suggested_sources"][0]["path"], str(self.root / "Recordings"))
        self.assertEqual(self.run_json("discover", "--since", "101d")["directories"][0]["count"], 3)
        code, out, err = self.run_cli("discover")
        self.assertEqual((code, err), (0, ""))
        snippet = out[out.index("[[sources]]"):]
        self.assertEqual(len(tomllib.loads(snippet)["sources"]), 2)

    def test_human_times_use_phone_zone_unless_local_requested(self):
        epoch_ms = 1_700_000_000_000
        entry = {"name": "x", "path": "/storage/emulated/0/x", "type": "file",
                 "size": 1, "mtime_ms": epoch_ms}
        old_tz = os.environ.get("TZ")
        try:
            os.environ["TZ"] = "America/Los_Angeles"
            if hasattr(time, "tzset"):
                time.tzset()
            with mock.patch("tentacle.phone_cli.PhoneClient.ls", return_value={"entries": [entry]}), \
                 mock.patch("tentacle.phone_cli.PhoneClient.ping", return_value={"timezone": "Asia/Shanghai"}):
                code, phone_out, err = self.run_cli("ls")
                self.assertEqual((code, err), (0, ""))
                code, local_out, err = self.run_cli("ls", "--local-time")
                self.assertEqual((code, err), (0, ""))
            self.assertIn("2023-11-15 06:13", phone_out)
            self.assertIn("2023-11-14 14:13", local_out)
        finally:
            if old_tz is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = old_tz
            if hasattr(time, "tzset"):
                time.tzset()

    def test_absolute_find_dates_use_the_selected_timezone(self):
        old_tz = os.environ.get("TZ")
        calls = []

        def find(**kwargs):
            calls.append(kwargs)
            return {"entries": [], "truncated": False}

        try:
            os.environ["TZ"] = "America/Los_Angeles"
            if hasattr(time, "tzset"):
                time.tzset()
            with mock.patch("tentacle.phone_cli.PhoneClient.find", side_effect=find), \
                 mock.patch("tentacle.phone_cli.PhoneClient.ping", return_value={"timezone": "Asia/Shanghai"}):
                self.run_json("find", "--since", "2023-11-15")
                self.run_json("find", "--since", "2023-11-15", "--local-time")
            self.assertEqual(calls[0]["since_ms"], 1_699_977_600_000)
            self.assertEqual(calls[1]["since_ms"], 1_700_035_200_000)
        finally:
            if old_tz is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = old_tz
            if hasattr(time, "tzset"):
                time.tzset()

    def test_discover_queries_only_known_locations(self):
        calls = []

        def find(**kwargs):
            calls.append(kwargs)
            if kwargs["root"] == "Recordings":
                return {"entries": [], "truncated": False}
            from tentacle.phone import PhoneError
            raise PhoneError("not_found", "missing", 404)

        with mock.patch("tentacle.phone_cli.PhoneClient.find", side_effect=find):
            self.run_json("discover")
        self.assertNotIn(None, [call["root"] for call in calls])
        self.assertEqual({call["max_depth"] for call in calls}, {4})
        self.assertEqual(len(calls), 11)

    def test_doctor_checks_only_manual_toolkit_dependencies(self):
        doctor_config = Path(self.tmp.name) / "doctor.toml"
        doctor_config.write_text(
            "[phone]\nurl = " + json.dumps(self.server.url) + "\ntoken = \"t\"\n"
            "[storage]\ndata_dir = " + json.dumps(str(Path(self.tmp.name) / "data")) + "\n"
            "[[sources]]\nname = \"recordings\"\npath = \"Recordings\"\n"
            "[asr]\nbackend = \"none\"\n",
            encoding="utf-8",
        )
        code, out, err = self.run_cli("doctor", "--json", config_path=doctor_config)
        self.assertEqual((code, err), (0, ""))
        checks = json.loads(out)
        self.assertEqual(
            [item["check"] for item in checks],
            ["config", "data_dir", "phone route", "phone ping", "source recordings", "ASR backend"],
        )
        self.assertTrue(all(item["ok"] for item in checks))

    def test_error_exit_codes(self):
        code, out, err = self.run_cli("stat", "missing", "--json")
        self.assertEqual((code, out), (1, ""))
        self.assertIn("not_found", err)
        self.cfg.write_text('[phone]\nurl = ""\ntoken = ""\n', encoding="utf-8")
        self.assertEqual(self.run_cli("ping")[0], 2)
        self.assertEqual(self.run_cli("find", "--since", "invalid")[0], 2)
        self.assertEqual(self.run_cli("pair", "invalid")[0], 2)
        self.assertEqual(self.run_cli("pair", self.server.url, "--token", "wrong")[0], 1)
        self.assertEqual(self.run_cli("find", "--since", "2026-02-02", "--until", "2026-01-01")[0], 2)

    def test_bad_usage(self):
        for args in (("find", "--limit", "5001"), ("cat", "note.txt", "--max-bytes", "-1")):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    cli.main(["--config", str(self.cfg), *args])
                self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
