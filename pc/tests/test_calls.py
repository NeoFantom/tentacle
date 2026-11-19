from __future__ import annotations

import contextlib
import io
import json
import os
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
from zoneinfo import ZoneInfo

from tentacle import calls, cli, config
from tentacle.timeutil import from_ms, to_ms


APP_PATTERN = r"(?P<app>\S+?)录音 (?P<counterpart>.+?)_(?P<ts>\d{14})"
CALL_PATTERN = r"^(?P<counterpart>.+?)_(?P<ts>\d{14})"


class ParseNameTests(unittest.TestCase):
    def setUp(self):
        self.mtime = to_ms(datetime(2026, 11, 15, 12, 34, 56, tzinfo=ZoneInfo("UTC")))
        self.source = config.SourceConfig("app-call", "recordings", name_pattern=APP_PATTERN)

    def test_named_groups_and_app_normalisation(self):
        result = calls.parse_name(
            "微信录音 张三_20261117172841.aac", self.source, self.mtime, ZoneInfo("Asia/Shanghai")
        )
        self.assertEqual(result, {
            "app": "wechat", "counterpart": "张三", "started_at": "2026-11-17T17:28:41+08:00",
        })
        expected = {"企业微信": "wecom", "飞书": "feishu", "QQ": "qq", "钉钉": "dingtalk",
                    "腾讯会议": "tencent-meeting", "NewApp": "newapp"}
        for raw, app in expected.items():
            with self.subTest(raw=raw):
                name = f"{raw}录音 Contact_20261117172841.aac"
                self.assertEqual(calls.parse_name(name, self.source, self.mtime)["app"], app)

        self.source.name_pattern = r"record (?P<counterpart>.+)_(?P<ts>\d{8})$"
        self.source.ts_format = "%Y%m%d"
        result = calls.parse_name("prefix record contact_20261117.mp3", self.source, self.mtime)
        self.assertEqual(result["counterpart"], "contact")
        self.assertTrue(result["started_at"].startswith("2026-11-17T00:00:00"))

    def test_phone_default_invalid_patterns_and_aware_timestamp(self):
        phone = config.SourceConfig("call", "calls", name_pattern=CALL_PATTERN)
        parsed = calls.parse_name("13800000000_20261116093000.mp3", phone, self.mtime, ZoneInfo("UTC"))
        self.assertEqual((parsed["app"], parsed["counterpart"]), ("phone", "13800000000"))
        phone.name_pattern = r"(?P<app>app)?(?P<counterpart>contact)"
        self.assertIsNone(calls.parse_name("contact.aac", phone, self.mtime)["app"])
        phone.name_pattern = ""
        self.assertEqual(calls.parse_name("unknown.aac", phone, self.mtime)["app"], "phone")

        for pattern in ("", "no match", "[", r"(?P<counterpart>contact)"):
            self.source.name_pattern = pattern
            self.assertEqual(calls.parse_name("unknown.aac", self.source, self.mtime)["started_at"],
                             from_ms(self.mtime).isoformat(timespec="seconds"))
            self.assertIsNone(calls.parse_name("unknown.aac", self.source, self.mtime)["app"])

        for value in ("bad", "20261317172841", ""):
            self.source.name_pattern = r"(?P<app>微信)录音 (?P<counterpart>.+?)_(?P<ts>.*)"
            parsed = calls.parse_name(f"微信录音 张三_{value}.aac", self.source, self.mtime)
            self.assertEqual(parsed["started_at"], from_ms(self.mtime).isoformat(timespec="seconds"))
            self.assertEqual((parsed["app"], parsed["counterpart"]), ("wechat", "张三"))
        self.source.name_pattern = r"(?P<app>微信)录音 (?P<counterpart>.+?)_(?P<ts>\d{14}[+-]\d{4})"
        self.source.ts_format = "%Y%m%d%H%M%S%z"
        parsed = calls.parse_name("微信录音 张三_20261117172841+0800.aac", self.source,
                                  self.mtime, ZoneInfo("UTC"))
        self.assertEqual(parsed["started_at"], "2026-11-17T09:28:41+00:00")


class ManualCommandsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.cfg = config.Config(
            phone=config.PhoneConfig(url="http://example.com:8765", token="token"),
            storage=config.StorageConfig(str(self.base / "data")),
            sources=[config.SourceConfig("app-call", "MIUI/app", name_pattern=APP_PATTERN,
                                         min_age_s=60)],
            calls=config.CallsConfig(timezone="Asia/Shanghai"),
            asr=config.AsrConfig(backend="command"),
        )
        self.now_ms = to_ms(datetime(2026, 11, 19, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai")))

    def test_list_calls_filters_and_marks_active_recording(self):
        client = mock.Mock()
        client.ping.return_value = {"time_ms": self.now_ms, "timezone": "UTC"}
        client.find.return_value = {"entries": [
            {"name": "微信录音 Alice_20261119115930.aac", "path": "/storage/emulated/0/MIUI/app/a.aac",
             "type": "file", "size": 12, "mtime_ms": self.now_ms - 30_000},
            {"name": "QQ录音 Bob_20261118110000.aac", "path": "/storage/emulated/0/MIUI/app/b.aac",
             "type": "file", "size": 8, "mtime_ms": self.now_ms - 90_000},
        ], "truncated": False}
        rows = calls.list_calls(self.cfg, client, with_text="ali", app="wechat", limit=50)
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0]["recording"])
        self.assertEqual(rows[0]["started_at"], "2026-11-19T11:59:30+08:00")
        self.assertIsNone(rows[0]["local"])
        self.assertEqual(client.find.call_args.kwargs["limit"], 50)
        self.assertIsNotNone(client.find.call_args.kwargs["since_ms"])

    @unittest.skipUnless(hasattr(time, "tzset"), "time.tzset is unavailable")
    def test_invalid_configured_timezone_does_not_reinterpret_phone_times_locally(self):
        old_timezone = os.environ.get("TZ")
        client = mock.Mock()
        client.ping.return_value = {"time_ms": self.now_ms, "timezone": "Asia/Shanghai"}
        self.cfg.calls.timezone = "Invalid/Timezone"
        try:
            os.environ["TZ"] = "America/Los_Angeles"
            time.tzset()
            with self.assertRaisesRegex(ValueError, r"invalid \[calls\]\.timezone"):
                calls.list_calls(self.cfg, client)
        finally:
            if old_timezone is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = old_timezone
            time.tzset()
        client.find.assert_not_called()

    def test_pull_skips_complete_cache_and_downloads_other(self):
        cached = self.cfg.data_dir / "recordings" / "app-call" / "a.aac"
        cached.parent.mkdir(parents=True)
        cached.write_bytes(b"complete")
        client = mock.Mock()
        client.ping.return_value = {"roots": [{"id": "primary", "path": "/storage/emulated/0"}]}
        client.stat.side_effect = [
            {"path": "/storage/emulated/0/MIUI/app/a.aac", "name": "a.aac", "size": 8},
            {"path": "/storage/emulated/0/Music/b.aac", "name": "b.aac", "size": 3},
        ]
        client.download.side_effect = lambda _path, dest, **_kw: Path(dest)
        paths = calls.pull_paths(self.cfg, client, ["MIUI/app/a.aac", "Music/b.aac"])
        self.assertEqual(paths[0], cached)
        self.assertEqual(paths[1], self.cfg.data_dir / "recordings" / "other" / "b.aac")
        client.download.assert_called_once()

    def test_transcribe_caches_by_sha_and_prints_text_or_json(self):
        audio = self.base / "call.wav"
        audio.write_bytes(b"audio")
        value = {"backend": "test", "model": "fixture", "language": "zh", "duration_s": 1.0,
                 "segments": [{"id": 0, "start": 0.0, "end": 1.0, "speaker": "S1", "text": "hello"}],
                 "text": "hello"}
        with mock.patch("tentacle.calls.asr.transcribe", return_value=value) as run:
            first = calls.transcribe_path(self.cfg, mock.Mock(), str(audio))
            second = calls.transcribe_path(self.cfg, mock.Mock(), str(audio))
        self.assertEqual(run.call_count, 1)
        self.assertEqual(first, second)
        self.assertEqual(first.text_path.read_text(), "[00:00] S1: hello\n")
        self.assertEqual(json.loads(first.json_path.read_text())["text"], "hello")

    def test_transcribe_rejects_output_that_would_overwrite_audio(self):
        prefix = self.base / "call"
        audio = Path(f"{prefix}.txt")
        audio.write_bytes(b"audio")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            calls.transcribe_path(self.cfg, mock.Mock(), str(audio), output_prefix=prefix)

    @unittest.skipUnless(hasattr(time, "tzset"), "time.tzset is unavailable")
    def test_local_time_changes_only_human_display_preserving_instant(self):
        old_timezone = os.environ.get("TZ")
        row = {"app": "phone", "counterpart": "Alice", "started_at": "2026-11-19T12:00:00+08:00",
               "size": 1, "source": "call", "path": "/phone/call.aac", "local": None,
               "recording": False}
        args = SimpleNamespace(since=None, until=None, source=None, with_text=None, app=None,
                               limit=50, local_time=True, json=False)
        output = io.StringIO()
        try:
            os.environ["TZ"] = "America/Los_Angeles"
            time.tzset()
            with mock.patch.object(calls, "list_calls", return_value=[row]), contextlib.redirect_stdout(output):
                calls._calls_command(args, self.cfg)
        finally:
            if old_timezone is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = old_timezone
            time.tzset()
        self.assertIn("2026-11-18T20:00:00-08:00", output.getvalue())
        self.assertEqual(row["started_at"], "2026-11-19T12:00:00+08:00")

    def test_cli_has_only_manual_recording_commands(self):
        parser = cli.build_parser()
        args = parser.parse_args(["calls", "--with", "Alice", "--app", "wechat", "--json"])
        self.assertEqual((args.with_text, args.app), ("Alice", "wechat"))
        self.assertEqual(args.since, "7d")
        for removed in ("sync", "recordings", "analyze", "dispatch", "tasks", "run", "daemon"):
            with self.subTest(removed=removed), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    parser.parse_args([removed])

        def invalid(_args, _cfg):
            raise ValueError("bad limit")

        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(calls.run_command(invalid, None, self.cfg), 2)


if __name__ == "__main__":
    unittest.main()
