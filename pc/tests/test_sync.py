"""Offline name parsing, sync and CLI integration tests."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock

from phone2agent import cli, config, sync
from phone2agent.phone import PhoneClient, PhoneError
from phone2agent.store import Store
from phone2agent.timeutil import from_ms, to_ms
from tests.fakephone import FakePhone


APP_PATTERN = r"(?P<app>\S+?)录音 (?P<counterpart>.+?)_(?P<ts>\d{14})"
CALL_PATTERN = r"^(?P<counterpart>.+?)_(?P<ts>\d{14})"
APP_NAME = "微信录音 张三_20261117172841.aac"
CALL_NAME = "13800000000(13800000000)_20261116093000.mp3"


class ParseNameTests(unittest.TestCase):
    def setUp(self):
        self.mtime = to_ms(datetime(2026, 11, 15, 12, 34, 56).astimezone())
        self.source = config.SourceConfig("app-call", "recordings", name_pattern=APP_PATTERN)

    def test_named_groups_and_local_timestamp(self):
        result = sync.parse_name(APP_NAME, self.source, self.mtime)
        self.assertEqual(result, {
            "app": "wechat", "counterpart": "张三",
            "started_at": datetime(2026, 11, 17, 17, 28, 41).astimezone().isoformat(timespec="seconds"),
        })
        # The expression is searched rather than implicitly anchored.
        self.source.name_pattern = r"record (?P<counterpart>.+)_(?P<ts>\d{8})$"
        self.source.ts_format = "%Y%m%d"
        result = sync.parse_name("prefix record contact_20261117.mp3", self.source, self.mtime)
        self.assertEqual(result["counterpart"], "contact")
        self.assertTrue(result["started_at"].startswith("2026-11-17T00:00:00"))

    def test_app_normalisation(self):
        for raw, expected in (
            ("微信", "wechat"), ("企业微信", "wecom"), ("飞书", "feishu"),
            ("QQ", "qq"), ("钉钉", "dingtalk"), ("腾讯会议", "tencent-meeting"),
            ("NewApp", "newapp"),
        ):
            with self.subTest(app=raw):
                result = sync.parse_name(f"{raw}录音 Contact_20261117172841.aac", self.source, self.mtime)
                self.assertEqual(result["app"], expected)

    def test_call_default_and_optional_app_group(self):
        source = config.SourceConfig("call", "calls", name_pattern=CALL_PATTERN)
        result = sync.parse_name(CALL_NAME, source, self.mtime)
        self.assertEqual(result["app"], "phone")
        self.assertEqual(result["counterpart"], "13800000000(13800000000)")
        self.assertTrue(result["started_at"].startswith("2026-11-16T09:30:00"))
        source.name_pattern = r"(?P<app>app)?(?P<counterpart>contact)"
        self.assertIsNone(sync.parse_name("contact.aac", source, self.mtime)["app"])
        source.name_pattern = ""
        self.assertEqual(sync.parse_name("unknown.aac", source, self.mtime)["app"], "phone")

    def test_missing_unmatched_and_invalid_patterns_fall_back(self):
        for pattern in ("", "no match", "[", r"(?P<counterpart>contact)"):
            with self.subTest(pattern=pattern):
                self.source.name_pattern = pattern
                result = sync.parse_name("contact.aac", self.source, self.mtime)
                self.assertEqual(result["started_at"], from_ms(self.mtime).isoformat(timespec="seconds"))
                self.assertIsNone(result["app"])

    def test_invalid_missing_timestamp_retains_other_metadata(self):
        for value in ("bad", "20261317172841", ""):
            with self.subTest(ts=value):
                self.source.name_pattern = r"(?P<app>微信)录音 (?P<counterpart>.+?)_(?P<ts>.*)"
                result = sync.parse_name(f"微信录音 张三_{value}.aac", self.source, self.mtime)
                self.assertEqual(result["started_at"], from_ms(self.mtime).isoformat(timespec="seconds"))
                self.assertEqual((result["app"], result["counterpart"]), ("wechat", "张三"))


class SyncTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / "phone"
        self.root.mkdir()
        self.app_dir = self.root / "MIUI/sound_recorder/app_rec"
        self.call_dir = self.root / "MIUI/sound_recorder/call_rec"
        self.app_dir.mkdir(parents=True)
        self.call_dir.mkdir(parents=True)
        self.server = FakePhone(self.root).start()
        self.addCleanup(self.server.close)
        self.cfg = config.Config(
            phone=config.PhoneConfig(url=self.server.url, token=self.server.token, timeout_s=1),
            storage=config.StorageConfig(str(self.base / "data")),
            sources=[
                config.SourceConfig("app-call", "MIUI/sound_recorder/app_rec", name_pattern=APP_PATTERN),
                config.SourceConfig("call", "MIUI/sound_recorder/call_rec", name_pattern=CALL_PATTERN),
            ],
        )
        self.store = Store.open(self.cfg)
        self.addCleanup(self.store.close)
        self.client = PhoneClient(self.cfg.phone)
        self.now = datetime(2026, 11, 17, 18, 0, 0).astimezone()

    def audio(self, name=APP_NAME, content=b"fake audio", age_s=120, directory=None):
        path = (directory or self.app_dir) / name
        path.write_bytes(content)
        mtime_ms = to_ms(self.now) - age_s * 1000
        os.utime(path, ns=(mtime_ms * 1_000_000, mtime_ms * 1_000_000))
        return path

    def scan(self):
        return sync.scan(self.cfg, self.store, self.client, now=self.now)

    def download(self, **kwargs):
        return sync.download_pending(self.cfg, self.store, self.client, now=self.now, **kwargs)

    def test_first_scan_backfill_window_and_filters(self):
        self.audio(age_s=7 * 86400)
        self.audio("too-old.aac", age_s=7 * 86400 + 1)
        self.audio("ignored.txt", age_s=120)
        self.audio(CALL_NAME, directory=self.call_dir, age_s=86400)
        with mock.patch.object(self.client, "find", wraps=self.client.find) as find:
            result = self.scan()
        self.assertEqual(result, sync.ScanResult(found=2, new=2))
        self.assertEqual(len(self.store.list_recordings()), 2)
        for source, call in zip(self.cfg.sources, find.call_args_list):
            self.assertEqual(call.kwargs, {
                "root": source.path, "ext": source.extensions,
                "since_ms": to_ms(self.now - timedelta(days=7)), "sort": "mtime_asc", "limit": 5000,
            })

    def test_incremental_window_is_per_source_and_uses_newest_mtime(self):
        old_time = to_ms(self.now - timedelta(days=20))
        newest_time = to_ms(self.now - timedelta(days=10))
        self.store.add_recording("app-call", "old.aac", 1, old_time, status="skipped")
        self.store.add_recording("app-call", "newest.aac", 1, newest_time, status="downloaded")
        # A later inserted ID does not necessarily have the newest mtime.
        self.store.add_recording("app-call", "older.aac", 1, old_time + 1)
        self.audio("in-overlap.aac", age_s=11 * 86400)
        self.audio("outside-overlap.aac", age_s=11 * 86400 + 1)
        with mock.patch.object(self.client, "find", wraps=self.client.find) as find:
            result = self.scan()
        self.assertEqual(result.new, 1)
        self.assertEqual(find.call_args_list[0].kwargs["since_ms"], newest_time - 86400000)
        self.assertEqual(find.call_args_list[1].kwargs["since_ms"], to_ms(self.now - timedelta(days=7)))

    def test_minimum_age_boundary_and_future_files(self):
        self.audio("young.aac", age_s=59)
        self.audio("boundary.aac", age_s=60)
        self.audio("future.aac", age_s=-60)
        self.assertEqual(self.scan(), sync.ScanResult(found=3, new=1, skipped_young=2))
        self.assertTrue(self.store.list_recordings()[0]["phone_path"].endswith("boundary.aac"))
        self.now += timedelta(minutes=3)
        self.assertEqual(self.scan().new, 2)

    def test_rescan_is_idempotent(self):
        self.audio()
        self.assertEqual(self.scan().new, 1)
        self.assertEqual(self.scan(), sync.ScanResult(found=1))
        self.assertEqual(len(self.store.list_recordings()), 1)

    def test_missing_source_logs_warning_and_continues(self):
        self.app_dir.rmdir()
        self.audio(CALL_NAME, directory=self.call_dir)
        self.assertEqual(self.scan(), sync.ScanResult(found=1, new=1, errors=1))
        event, = self.store.recent_events(10)
        self.assertEqual(event["level"], "warning")
        self.assertIn("app-call", event["message"])
        self.assertIn("not_found", event["message"])

    def test_unreachable_scan_logs_once_and_stops(self):
        self.server.close()
        with mock.patch.object(self.client, "find", wraps=self.client.find) as find:
            self.assertEqual(self.scan(), sync.ScanResult(errors=1))
        self.assertEqual(find.call_count, 1)
        self.assertEqual(len(self.store.recent_events(10)), 1)

    def test_scan_http_error_continues_and_truncation_is_reported(self):
        with mock.patch.object(self.client, "find", side_effect=[
            PhoneError("forbidden", "No permission", 403),
            {"entries": [], "truncated": True},
        ]) as find:
            self.assertEqual(self.scan().errors, 1)
        self.assertEqual(find.call_count, 2)
        events = self.store.recent_events(10)
        self.assertEqual({event["level"] for event in events}, {"error", "warning"})
        self.assertIn("truncated", events[0]["message"])

    def test_download_hash_metadata_and_started_month_layout(self):
        content = b"audio data" * 40000
        self.audio(content=content)
        self.scan()
        row, = self.store.list_recordings()
        # Layout uses the recording start month rather than its mtime month.
        self.store.update_recording(row["id"], started_at="2026-10-31T23:59:00+00:00")
        with mock.patch("phone2agent.asr.audio.probe_duration", return_value=12.5) as probe:
            self.assertEqual(self.download(), [row["id"]])
        row = self.store.get_recording(row["id"])
        path = self.cfg.data_dir / "recordings/2026-10" / f"{row['id']}-{APP_NAME}"
        self.assertEqual(Path(row["local_path"]), path)
        self.assertEqual(path.read_bytes(), content)
        self.assertEqual(row["sha256"], hashlib.sha256(content).hexdigest())
        self.assertEqual(row["duration_s"], 12.5)
        self.assertEqual(row["status"], "downloaded")
        probe.assert_called_once_with(path)
        self.assertFalse(Path(str(path) + ".part").exists())
        self.assertEqual(self.download(), [])

    def test_download_resumes_client_partial_file(self):
        content = b"audiobytes" * 2000
        remote = self.audio(content=content)
        self.scan()
        row, = self.store.list_recordings()
        destination = self.cfg.data_dir / "recordings/2026-11" / f"{row['id']}-{APP_NAME}"
        destination.parent.mkdir(parents=True)
        part = Path(str(destination) + ".part")
        part.write_bytes(content[:777])
        Path(str(part) + ".etag").write_text(f'"{len(content)}-{row["mtime_ms"]}"', encoding="ascii")
        with mock.patch.object(self.client, "_open", wraps=self.client._open) as opened:
            self.assertEqual(self.download(), [row["id"]])
        self.assertTrue(any(call.kwargs.get("headers", {}).get("Range") == "bytes=777-"
                            for call in opened.call_args_list))
        self.assertEqual(destination.read_bytes(), remote.read_bytes())

    def test_duplicate_content_is_skipped_and_deleted(self):
        self.audio(content=b"same", age_s=180)
        self.audio(CALL_NAME, content=b"same", directory=self.call_dir)
        self.scan()
        first, second = self.store.list_recordings()
        self.assertEqual(self.download(), [first["id"]])
        row = self.store.get_recording(second["id"])
        self.assertEqual(row["status"], "skipped")
        self.assertIsNone(row["local_path"])
        files = list((self.cfg.data_dir / "recordings").rglob("*.*"))
        self.assertEqual(files, [Path(self.store.get_recording(first["id"])["local_path"])])
        event, = self.store.recent_events(10)
        self.assertEqual(event["recording_id"], second["id"])
        self.assertIn(str(first["id"]), event["message"])

    def test_size_change_fails_download_with_backoff_and_retries_when_due(self):
        remote = self.audio()
        self.scan()
        row, = self.store.list_recordings()
        original = remote.read_bytes()
        remote.write_bytes(b"changed and longer")
        now_iso = self.now.isoformat(timespec="seconds")
        with mock.patch("phone2agent.store.timeutil.now_iso", return_value=now_iso):
            self.assertEqual(self.download(), [])
        failed = self.store.get_recording(row["id"])
        self.assertEqual((failed["status"], failed["failed_stage"], failed["attempts"]), ("failed", "download", 1))
        self.assertIn("size_mismatch", failed["error"])
        self.assertEqual(datetime.fromisoformat(failed["next_try_at"]), self.now + timedelta(minutes=5))
        remote.write_bytes(original)
        self.assertEqual(self.download(), [])
        self.now += timedelta(minutes=5)
        self.assertEqual(self.download(), [row["id"]])
        recovered = self.store.get_recording(row["id"])
        self.assertEqual(recovered["status"], "downloaded")
        for field in ("failed_stage", "error", "next_try_at"):
            self.assertIsNone(recovered[field])
        self.assertEqual(recovered["attempts"], 0)

    def test_deleted_remote_file_fails_but_remaining_downloads_continue(self):
        missing = self.audio(age_s=180)
        self.audio(CALL_NAME, directory=self.call_dir)
        self.scan()
        first, second = self.store.list_recordings()
        missing.unlink()
        self.assertEqual(self.download(), [second["id"]])
        failed = self.store.get_recording(first["id"])
        self.assertEqual((failed["status"], failed["failed_stage"]), ("failed", "download"))
        # HEAD error responses have no JSON body to recover an API error code.
        self.assertIn("404", failed["error"])

    def test_network_download_error_stops_without_counting_failures(self):
        self.audio()
        self.audio(CALL_NAME, directory=self.call_dir)
        self.scan()
        self.server.close()
        with mock.patch.object(self.client, "download", wraps=self.client.download) as download:
            self.assertEqual(self.download(), [])
        self.assertEqual(download.call_count, 1)
        for row in self.store.list_recordings():
            self.assertEqual((row["status"], row["attempts"]), ("discovered", 0))
        self.assertEqual(len(self.store.recent_events(10)), 1)

    def test_only_due_download_rows_consume_limit(self):
        self.audio()
        self.audio(CALL_NAME, directory=self.call_dir)
        self.scan()
        first, second = self.store.list_recordings()
        self.store.update_recording(first["id"], status="failed", failed_stage="transcribe",
                                    next_try_at=(self.now - timedelta(minutes=1)).isoformat())
        self.assertEqual(self.download(limit=0), [])
        self.assertEqual(self.download(limit=1), [second["id"]])
        self.assertEqual(self.store.get_recording(first["id"])["status"], "failed")
        self.store.update_recording(first["id"], failed_stage="download", next_try_at=None)
        self.assertEqual(self.download(), [])  # Exhausted attempts are not due.
        with self.assertRaises(ValueError):
            self.download(limit=-1)

    def test_optional_duration_failures_never_fail_download(self):
        for index, failure in enumerate((ImportError("no optional module"), RuntimeError("invalid audio"))):
            with self.subTest(error=type(failure).__name__):
                self.audio(f"audio-{index}.aac", content=f"audio {index}".encode())
                self.scan()
                with mock.patch("phone2agent.asr.audio.probe_duration", side_effect=failure):
                    downloaded = self.download()
                self.assertEqual(len(downloaded), 1)
                row = self.store.get_recording(downloaded[0])
                self.assertEqual(row["status"], "downloaded")
                self.assertIsNone(row["duration_s"])

    def test_local_io_failure_is_retryable(self):
        self.audio()
        self.scan()
        with mock.patch.object(self.client, "download", side_effect=OSError("disk full")):
            self.assertEqual(self.download(), [])
        row, = self.store.list_recordings()
        self.assertEqual((row["status"], row["failed_stage"]), ("failed", "download"))
        self.assertIn("disk full", row["error"])

    def test_sanitised_unicode_name_has_bounded_utf8_length(self):
        name = "张三" * 35 + ".aac"
        self.audio(name)
        self.scan()
        row, = self.store.list_recordings()
        self.assertEqual(self.download(), [row["id"]])
        path = Path(self.store.get_recording(row["id"])["local_path"])
        safe = path.name.split("-", 1)[1]
        self.assertLessEqual(len(safe.encode("utf-8")), 120)
        self.assertTrue(safe.startswith("张三"))
        self.assertTrue(safe.endswith(".aac"))
        for raw in ("../张三\\bad\x00\n\x7f.aac", "...", "/\\", "字" * 60 + ".m4a"):
            safe = sync._sanitise_name(raw)
            self.assertNotIn("/", safe)
            self.assertNotIn("\\", safe)
            self.assertFalse(any(ord(char) < 32 or ord(char) == 127 for char in safe))
            self.assertNotIn(safe, ("", ".", ".."))
            self.assertLessEqual(len(safe.encode()), 120)

    def test_sync_once_counts_and_cli_scan_download_and_listing(self):
        # CLI uses wall time, so move the fixture's mtime relative to that time.
        self.now = datetime.now().astimezone()
        self.audio()
        cfg_path = self.base / "config.toml"
        cfg_path.write_text(
            "[phone]\nurl = " + json.dumps(self.server.url)
            + "\ntoken = " + json.dumps(self.server.token)
            + "\n[storage]\ndata_dir = " + json.dumps(str(self.cfg.data_dir))
            + "\n[[sources]]\nname = \"app-call\"\npath = \"MIUI/sound_recorder/app_rec\""
            + "\nname_pattern = '" + APP_PATTERN + "'\n", encoding="utf-8",
        )

        def run(*args):
            output, error = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
                result = cli.main([*args, "--config", str(cfg_path)])
            self.assertEqual((result, error.getvalue()), (0, ""))
            return output.getvalue()

        scanned = json.loads(run("sync", "--scan-only", "--json"))
        self.assertEqual({key: scanned[key] for key in ("found", "new", "downloaded", "errors")},
                         {"found": 1, "new": 1, "downloaded": 0, "errors": 0})
        self.assertEqual(scanned["recordings"][0]["counterpart"], "张三")
        self.assertEqual(set(scanned["recordings"][0]), {"id", "source", "counterpart", "started_at", "size"})
        self.assertFalse((self.cfg.data_dir / "recordings").exists())
        rows = json.loads(run("recordings", "--status", "discovered", "--limit", "1", "--json"))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["app"], "wechat")
        self.assertEqual(set(rows[0]), {
            "id", "status", "source", "app", "counterpart", "started_at", "duration", "error",
        })
        downloaded = json.loads(run("sync", "--json"))
        self.assertEqual(downloaded["downloaded"], 1)
        self.assertEqual(downloaded["new"], 0)
        self.assertEqual(downloaded["recordings"], [])
        self.assertEqual(json.loads(run("recordings", "--status", "discovered", "--json")), [])
        self.assertEqual(json.loads(run("recordings", "--limit", "0", "--json")), [])
        self.assertIn("downloaded", run("recordings"))
        self.assertIn("张三", run("recordings"))
        self.assertIn("found=1", run("sync"))
        # Preserve the shared CLI's global --config form as well.
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(cli.main(["--config", str(cfg_path), "recordings", "--json"]), 0)
        self.assertEqual(len(json.loads(output.getvalue())), 1)
        self.assertEqual(sync.sync_once(self.cfg, self.store, self.client),
                         {"found": 1, "new": 0, "skipped_young": 0, "errors": 0, "downloaded": 0})

    def test_human_sync_output_lists_new_recording(self):
        self.now = datetime.now().astimezone()
        self.audio()
        args = cli.build_parser().parse_args(["sync", "--scan-only"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(args.func(args, self.cfg), 0)
        for value in ("new=1", "id", "source", "counterpart", "started_at", "size", "张三", "app-call"):
            self.assertIn(value, output.getvalue())


if __name__ == "__main__":
    unittest.main()
