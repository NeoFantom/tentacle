from __future__ import annotations

import json
import sqlite3
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from phone2agent.store import Store
from phone2agent.timeutil import from_ms, parse_since, to_ms


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "data" / "state.db"
        self.store = Store(self.path)
        self.addCleanup(self.store.close)

    def add_recording(self, suffix: str = "a", **fields):
        return self.store.add_recording(
            "calls", f"Recordings/{suffix}.m4a", 100, 1_700_000_000_000, **fields
        )[0]

    def test_empty_database_migrates_and_sets_pragmas_and_indexes(self) -> None:
        self.assertEqual(1, self.store.conn.execute("PRAGMA user_version").fetchone()[0])
        self.assertEqual(1, self.store.conn.execute("PRAGMA foreign_keys").fetchone()[0])
        self.assertEqual(5000, self.store.conn.execute("PRAGMA busy_timeout").fetchone()[0])
        self.assertEqual("wal", self.store.conn.execute("PRAGMA journal_mode").fetchone()[0])
        indexes = {
            row["name"] for row in self.store.conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'index'"
            )
        }
        self.assertIn("recordings_status_next_try_at_idx", indexes)
        self.assertIn("tasks_status_idx", indexes)

    def test_open_uses_config_data_dir_and_creates_it(self) -> None:
        data_dir = Path(self.temp.name) / "other"
        with Store.open(SimpleNamespace(data_dir=data_dir)) as opened:
            self.assertEqual(data_dir / "state.db", opened.path)
            self.assertTrue(opened.path.exists())

    def test_empty_file_migrates_and_reopening_preserves_data(self) -> None:
        path = Path(self.temp.name) / "empty.db"
        path.touch()
        with Store(path) as opened:
            recording_id, _ = opened.add_recording("calls", "a.m4a", 10, 20)
        with Store(path) as reopened:
            self.assertEqual("a.m4a", reopened.get_recording(recording_id)["phone_path"])
            self.assertEqual(1, reopened.conn.execute("PRAGMA user_version").fetchone()[0])

    def test_newer_schema_is_rejected_without_downgrade(self) -> None:
        path = Path(self.temp.name) / "future.db"
        connection = sqlite3.connect(path)
        connection.execute("PRAGMA user_version = 99")
        connection.close()
        with self.assertRaisesRegex(RuntimeError, "newer than supported"):
            Store(path)
        connection = sqlite3.connect(path)
        try:
            self.assertEqual(99, connection.execute("PRAGMA user_version").fetchone()[0])
        finally:
            connection.close()

    def test_add_recording_is_idempotent_and_updates_are_validated(self) -> None:
        first, created = self.store.add_recording("calls", "a.m4a", 9, 10, app="phone")
        second, created_again = self.store.add_recording(
            "different-source", "a.m4a", 9, 10, app="wechat"
        )
        self.assertEqual(first, second)
        self.assertTrue(created)
        self.assertFalse(created_again)
        row = self.store.get_recording(first)
        self.assertEqual("calls", row["source"])
        self.assertEqual("phone", row["app"])
        self.assertEqual("discovered", row["status"])

        with mock.patch("phone2agent.store.timeutil.now_iso", return_value="2026-01-02T03:04:05+08:00"):
            updated = self.store.update_recording(first, status="downloaded", sha256="abc")
        self.assertEqual("downloaded", updated["status"])
        self.assertEqual("2026-01-02T03:04:05+08:00", updated["updated_at"])
        self.assertEqual(first, self.store.find_by_sha256("abc")["id"])
        with self.assertRaises(ValueError):
            self.store.update_recording(first, made_up=True)

    def test_listing_due_and_counts(self) -> None:
        one = self.add_recording("one")
        two = self.add_recording("two", status="downloaded")
        three = self.add_recording(
            "three", status="downloaded", next_try_at="2026-01-02T00:00:00+00:00"
        )
        self.store.update_recording(
            two, next_try_at="2026-01-04T00:00:00+00:00"
        )
        self.assertEqual([one], [r["id"] for r in self.store.list_recordings("discovered")])
        self.assertEqual(1, len(self.store.list_recordings(limit=1)))
        self.assertEqual(
            [one, three],
            [r["id"] for r in self.store.due_recordings(
                ["discovered", "downloaded"], "2026-01-03T00:00:00+00:00"
            )],
        )
        self.assertEqual({"discovered": 1, "downloaded": 2}, self.store.counts_by_status())
        self.assertEqual([], self.store.due_recordings([], "2026-01-03T00:00:00+00:00"))

    def test_failure_backoff_cap_permanent_and_due_rules(self) -> None:
        recording_id = self.add_recording()
        fixed = "2026-01-01T12:00:00+00:00"
        with mock.patch("phone2agent.store.timeutil.now_iso", return_value=fixed):
            first = self.store.mark_failed(recording_id, "download", "offline", 20)
            self.assertEqual("2026-01-01T12:05:00+00:00", first["next_try_at"])
            for _ in range(9):
                capped = self.store.mark_failed(recording_id, "download", "offline", 20)
            self.assertEqual(10, capped["attempts"])
            self.assertEqual("2026-01-02T12:00:00+00:00", capped["next_try_at"])
        self.assertEqual(
            [recording_id],
            [r["id"] for r in self.store.due_recordings(
                ["failed"], "2026-01-03T00:00:00+00:00"
            )],
        )

        with mock.patch("phone2agent.store.timeutil.now_iso", return_value=fixed):
            permanent = self.store.mark_failed(recording_id, "download", "still offline", 11)
        self.assertEqual(11, permanent["attempts"])
        self.assertIsNone(permanent["next_try_at"])
        self.assertEqual([], self.store.due_recordings(["failed"], "2099-01-01T00:00:00+00:00"))

    def test_due_comparison_accounts_for_utc_offsets(self) -> None:
        recording_id = self.add_recording()
        self.store.update_recording(
            recording_id,
            status="failed",
            next_try_at="2026-01-01T13:00:00+01:00",
        )
        due = self.store.due_recordings(["failed"], "2026-01-01T12:30:00+00:00")
        self.assertEqual([recording_id], [row["id"] for row in due])

    def test_failure_attempt_increment_is_atomic_across_connections(self) -> None:
        recording_id = self.add_recording()
        barrier = threading.Barrier(2)
        errors: list[Exception] = []

        def fail_once() -> None:
            try:
                with Store(self.path) as other:
                    barrier.wait(timeout=2)
                    other.mark_failed(recording_id, "download", "offline", 5)
            except Exception as exc:  # pragma: no cover - asserted below
                errors.append(exc)

        threads = [threading.Thread(target=fail_once) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive(), "concurrent failure update did not finish")
        self.assertEqual([], errors)
        self.assertEqual(2, self.store.get_recording(recording_id)["attempts"])

    def test_retry_reset_mapping(self) -> None:
        mapping = {
            "download": "discovered",
            "transcribe": "downloaded",
            "analyze": "transcribed",
            "dispatch": "analyzed",
        }
        for index, (stage, expected) in enumerate(mapping.items()):
            recording_id = self.add_recording(str(index))
            self.store.update_recording(
                recording_id,
                status="failed",
                failed_stage=stage,
                error="boom",
                attempts=3,
                next_try_at="2099-01-01T00:00:00+00:00",
            )
            row = self.store.reset_for_retry(recording_id)
            self.assertEqual(expected, row["status"])
            self.assertIsNone(row["failed_stage"])
            self.assertIsNone(row["error"])
            self.assertEqual(0, row["attempts"])
            self.assertIsNone(row["next_try_at"])

    def test_artifacts_tasks_and_events(self) -> None:
        recording_id = self.add_recording()
        first = self.store.add_artifact(recording_id, "transcript", "one.json", {"lang": "zh"})
        second = self.store.add_artifact(recording_id, "transcript", "two.json")
        self.store.add_artifact(recording_id, "analysis", "analysis.json")
        self.assertEqual([first, second], [r["id"] for r in self.store.artifacts(recording_id, "transcript")])
        self.assertEqual(second, self.store.latest_artifact(recording_id, "transcript")["id"])
        self.assertEqual({"lang": "zh"}, json.loads(self.store.artifacts(recording_id)[0]["meta"]))

        task_id = self.store.add_task(
            "Review note", "Check the generated note", recording_id, "project", reason="approval"
        )
        self.assertEqual("pending", self.store.get_task(task_id)["status"])
        self.store.update_task(task_id, status="approved", owner_note="Proceed")
        self.assertEqual([task_id], [r["id"] for r in self.store.list_tasks(["approved"])])
        self.assertEqual([], self.store.list_tasks([]))
        with self.assertRaises(ValueError):
            self.store.update_task(task_id, made_up=True)

        first_event = self.store.log_event("info", "created", recording_id, task_id)
        second_event = self.store.log_event("warning", "changed")
        self.assertEqual([second_event, first_event], [r["id"] for r in self.store.recent_events(10)])

    def test_foreign_keys_are_enforced(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.add_artifact(9999, "transcript", "missing.json")


class TimeUtilTest(unittest.TestCase):
    def test_parse_relative_and_absolute_since(self) -> None:
        now = datetime(2026, 11, 17, 15, 30, tzinfo=timezone(timedelta(hours=8)))
        self.assertEqual(now - timedelta(minutes=30), parse_since("30m", now))
        self.assertEqual(now - timedelta(hours=2), parse_since("2h", now))
        self.assertEqual(now - timedelta(days=3), parse_since("3d", now))
        self.assertEqual(now - timedelta(weeks=1), parse_since("1w", now))
        self.assertEqual(
            datetime(2026, 11, 1, tzinfo=now.tzinfo), parse_since("2026-11-01", now)
        )
        self.assertEqual(
            datetime(2026, 11, 1, 9, 45, tzinfo=now.tzinfo),
            parse_since("2026-11-01T09:45", now),
        )
        with self.assertRaises(ValueError):
            parse_since("yesterday", now)

    def test_epoch_millisecond_round_trip(self) -> None:
        value = datetime(2026, 11, 17, 15, 30, 0, 123000, tzinfo=timezone.utc)
        millis = to_ms(value)
        self.assertEqual(value, from_ms(millis, timezone.utc))


if __name__ == "__main__":
    unittest.main()
