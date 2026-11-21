from __future__ import annotations

import contextlib
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest import mock

from tentacle import cli, notifications_cmd
from tentacle.config import PhoneConfig
from tentacle.notifications import (
    Batch, Notification, NotificationFeed, SharingOff, UnsupportedPhone, parse_since,
)
from tentacle.phone import PhoneError
from tests.fakephone import FakePhone


class NotificationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.phone = FakePhone(self.root, token="test-feed-token", clock_offset_s=3600).start()
        self.addCleanup(self.phone.close)
        self.cfg = self.root / "config.toml"
        self.cfg.write_text(
            f'[phone]\nurl = {json.dumps(self.phone.url)}\ntoken = "test-feed-token"\ntimeout_s = 2\n'
            f'[storage]\ndata_dir = {json.dumps(str(self.root / "data"))}\n', encoding="utf-8")
        self.feed = NotificationFeed(PhoneConfig(url=self.phone.url, token="test-feed-token", timeout_s=2))

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = cli.main(["--config", str(self.cfg), "notifications", *args])
        return status, out.getvalue(), err.getvalue()

    def test_cli_since_app_grep_json_and_limit(self):
        now = time.time_ns() // 1_000_000 + 3_600_000
        self.phone.add_phone_notification(posted_ms=now - 700_000, text="Old")
        expected = self.phone.add_phone_notification(posted_ms=now - 1000, text="Meeting tomorrow")
        self.phone.add_phone_notification(posted_ms=now, app="Other", package="com.example.other")
        status, out, err = self.run_cli("--since", "10m", "--app", "CHAT", "--grep", "MEETING",
                                        "--limit", "1", "--json")
        self.assertEqual((status, err), (0, ""))
        self.assertEqual([json.loads(line) for line in out.splitlines()], [expected])
        self.assertAlmostEqual(self.phone.notification_queries[-1]["since"], now - 600_000, delta=2000)
        self.assertEqual(self.run_cli("--app", "COM.EXAMPLE.CHAT", "--grep", "tomorrow", "--json"),
                         (0, json.dumps(expected) + "\n", ""))

    def test_filters_apply_before_limit_and_newest_first(self):
        self.phone.add_phone_notification(posted_ms=100, title="first")
        expected = self.phone.add_phone_notification(posted_ms=200, title="second")
        self.phone.add_phone_notification(posted_ms=300, app="Other", package="com.example.other")
        self.assertEqual(self.feed.fetch(app="chat", limit=1).to_dict()["notifications"], [expected])
        self.assertEqual([n.posted_ms for n in self.feed.fetch().notifications], [300, 200, 100])

    def test_iso_since_and_duration_use_phone_clock(self):
        self.assertEqual(parse_since("2h", 10_000_000), 2_800_000)
        self.assertEqual(parse_since("10m", 10_000), 0)
        value = "2026-01-01T12:34:56+00:00"
        timestamp = int(datetime(2026, 1, 1, 12, 34, 56, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(parse_since(value, 0), timestamp)
        self.phone.add_phone_notification(posted_ms=timestamp, text="before")
        expected = self.phone.add_phone_notification(posted_ms=timestamp + 1, text="after")
        self.assertEqual(self.feed.fetch(since=value).to_dict()["notifications"], [expected])
        local = datetime.fromisoformat("2026-01-01T12:34:56").timestamp()
        self.assertEqual(parse_since("2026-01-01T12:34:56", 0), int(local * 1000))

    def test_off_and_403_disabled_exit_two(self):
        for error_response in (False, True):
            with self.subTest(error_response=error_response):
                self.phone.notifications_off_403 = error_response
                self.phone.set_notifications_enabled(False)
                status, out, err = self.run_cli("--json")
                self.assertEqual((status, out), (2, ""))
                self.assertIn("Share notifications with the computer", err)
                self.assertIn("notification access", err)
                with self.assertRaises(SharingOff):
                    self.feed.fetch()

    def test_old_phone_exit_two(self):
        self.phone.notifications_supported = False
        status, out, err = self.run_cli("--json")
        self.assertEqual((status, out), (2, ""))
        self.assertIn("Update the phone app", err)
        with self.assertRaises(UnsupportedPhone):
            self.feed.fetch()

    def test_unreachable_exit_one(self):
        with mock.patch.object(NotificationFeed, "fetch", side_effect=PhoneError("unreachable", "private")):
            status, out, err = self.run_cli("--json")
        self.assertEqual((status, out), (1, ""))
        self.assertIn("unreachable", err)
        self.assertNotIn("private", err)

    def test_malformed_entries_and_additive_fields(self):
        item = self.phone.add_phone_notification(future="ignored")
        self.assertNotIn("future", self.feed.fetch().notifications[0].to_dict())
        for overrides in ({"posted_ms": True}, {"ongoing": 1}, {"text": []}, {"removed_ms": "bad"},
                          {"updated_ms": True}, {"updated_ms": -1}, {"updated_ms": "100"},
                          {"updated_ms": None}):
            with self.subTest(overrides=overrides), self.assertRaises(PhoneError) as error:
                Notification.parse(dict(item, **overrides))
            self.assertEqual(error.exception.code, "bad_response")

    def test_updated_ms_optional_fallback(self):
        item = self.phone.add_phone_notification(posted_ms=100)
        item.pop("updated_ms")
        self.assertEqual(Notification.parse(item).updated_ms, 100)
        self.assertEqual(Notification.parse(dict(item, removed_ms=300)).updated_ms, 300)
        self.assertEqual(Notification.parse(dict(item, removed_ms=50)).updated_ms, 100)

    def test_updated_cursor_includes_old_posts_and_orders_changes(self):
        older = self.phone.add_phone_notification(posted_ms=100, updated_ms=400)
        newer = self.phone.add_phone_notification(posted_ms=200, updated_ms=300)
        self.assertEqual(self.feed.fetch(since_ms=250).to_dict()["notifications"], [older, newer])
        self.assertEqual(self.feed.fetch(since_ms=300).to_dict()["notifications"], [older])
        self.assertEqual(self.feed.fetch(since_ms=400).notifications, ())
        self.assertEqual(self.phone.notification_queries[-1]["since"], 400)

    def test_since_filters_post_time_after_updated_cursor_fetch(self):
        self.phone.add_phone_notification(posted_ms=100, updated_ms=400)
        expected = self.phone.add_phone_notification(posted_ms=300, updated_ms=350)
        self.assertEqual(self.feed.fetch(since_ms=250, since="1970-01-01T00:00:00.250+00:00")
                         .to_dict()["notifications"], [expected])
        self.assertEqual(self.phone.notification_queries[-1]["since"], 250)
        self.assertEqual(self.feed.fetch(since="1970-01-01T00:00:00.250+00:00")
                         .to_dict()["notifications"], [expected])
        self.assertEqual(self.phone.notification_queries[-1]["since"], 250)

    def test_duplicate_keys_keep_newest_version_before_filtering(self):
        old = self.phone.add_phone_notification(posted_ms=100, updated_ms=100, text="Old match")
        latest = dict(old, text="Replacement", updated_ms=300)
        other = dict(old, key="other", updated_ms=200)
        payload = {"notifications": [old, latest, other, old], "now_ms": 400, "enabled": True}
        with mock.patch.object(self.feed.client, "_json", return_value=payload):
            self.assertEqual(self.feed.fetch().to_dict()["notifications"], [latest, other])
            self.assertEqual(self.feed.fetch(grep="old").to_dict()["notifications"], [other])

    def test_fake_phone_replaces_key_and_long_poll_wakes_on_update_and_removal(self):
        original = self.phone.add_phone_notification(posted_ms=100, updated_ms=200)
        for updated_ms, action in (
            (300, lambda: self.phone.add_phone_notification(**dict(original, text="Edited", updated_ms=300))),
            (400, lambda: self.phone.remove_phone_notification(original["key"], removed_ms=350, updated_ms=400)),
        ):
            with self.subTest(updated_ms=updated_ms):
                timer = threading.Timer(0.1, action)
                timer.start()
                try:
                    started = time.monotonic()
                    batch = self.feed.fetch(since_ms=updated_ms - 100, wait=1)
                finally:
                    timer.join(timeout=2)
                self.assertGreaterEqual(time.monotonic() - started, 0.08)
                self.assertEqual(len(batch.notifications), 1)
                self.assertEqual(batch.notifications[0].updated_ms, updated_ms)
        items = self.feed.fetch().notifications
        self.assertEqual(len(items), 1)
        self.assertEqual((items[0].text, items[0].removed_ms), ("Edited", 350))

    def test_ping_and_disabled_empty_response(self):
        self.assertEqual(self.feed.client.ping()["phone_notifications"], {"enabled": True})
        self.phone.add_phone_notification()
        self.phone.set_notifications_enabled(False)
        self.assertEqual(self.feed.client._json("ping")["phone_notifications"], {"enabled": False})
        response = self.feed.client._json("notifications", {"wait": 60})
        self.assertEqual(response["notifications"], [])
        self.assertFalse(response["enabled"])

    def test_follow_updates_removals_and_no_duplicates(self):
        original = self.phone.add_phone_notification(posted_ms=100, updated_ms=200)
        plain_update = dict(original, ongoing=True, updated_ms=300)
        edited = dict(plain_update, title="Edited title", text="Edited text", updated_ms=400)
        removed = dict(edited, removed_ms=450, updated_ms=500)
        other = dict(original, key="other", app="Other", updated_ms=600)
        fresh = dict(original, key="fresh", text="New", updated_ms=700)
        payloads = [
            [original], [plain_update], [edited, original], [removed],
            [removed], [other], [fresh],
        ]
        responses = [Batch(tuple(Notification.parse(item) for item in items), 800, True)
                     for items in payloads]
        with mock.patch.object(self.feed, "fetch", side_effect=[*responses, SharingOff()]) as fetch, \
                mock.patch("tentacle.notifications.time.sleep"):
            stream = self.feed.follow(app="chat", wait=1)
            self.assertEqual([next(stream).to_dict() for _ in range(4)],
                             [original, edited, removed, fresh])
            with self.assertRaises(SharingOff):
                next(stream)
        self.assertEqual([call.kwargs["since_ms"] for call in fetch.call_args_list[1:]],
                         [200, 300, 400, 500, 500, 600, 700])

    def test_follow_since_keeps_post_time_filter_while_advancing_updates(self):
        old = self.phone.add_phone_notification(posted_ms=100, updated_ms=300)
        initial = dict(old, key="initial", posted_ms=200, updated_ms=200)
        old_edited = dict(old, text="Old edit", updated_ms=400)
        fresh = dict(old, key="fresh", posted_ms=500, updated_ms=600)
        responses = [Batch(tuple(Notification.parse(item) for item in items), 600, True)
                     for items in ([old, initial], [old_edited], [fresh])]
        with mock.patch.object(self.feed, "fetch", side_effect=[*responses, SharingOff()]) as fetch, \
                mock.patch("tentacle.notifications.time.sleep"):
            stream = self.feed.follow(since="1970-01-01T00:00:00.150+00:00")
            self.assertEqual([next(stream).key for _ in range(2)], ["initial", "fresh"])
            with self.assertRaises(SharingOff):
                next(stream)
        self.assertEqual([call.kwargs["since_ms"] for call in fetch.call_args_list[1:]],
                         [300, 400, 600])

    def test_follow_empty_snapshot_does_not_skip_same_millisecond_post(self):
        posted = Notification.parse(self.phone.add_phone_notification(posted_ms=200, updated_ms=200))
        with mock.patch.object(self.feed, "fetch", side_effect=[
                Batch((), 200, True), Batch((posted,), 200, True)]) as fetch:
            stream = self.feed.follow(wait=1)
            self.assertEqual(next(stream), posted)
            stream.close()
        self.assertEqual(fetch.call_args_list[1].kwargs["since_ms"], 0)

    def test_cli_follow_marks_updated_and_shows_short_removal(self):
        original = self.phone.add_phone_notification(posted_ms=100, updated_ms=200)
        edited = dict(original, title="Edited", text="Revised text", updated_ms=300)
        removed = dict(edited, removed_ms=350, updated_ms=400)
        entries = [Notification.parse(item) for item in (original, edited, removed)]
        with mock.patch.object(NotificationFeed, "follow", return_value=iter(entries)):
            status, out, err = self.run_cli("--follow")
        self.assertEqual((status, err), (0, ""))
        lines = out.splitlines()
        self.assertEqual(len(lines), 4)
        self.assertIn("[updated]", lines[2])
        self.assertIn("Revised text", lines[2])
        self.assertIn("[removed]", lines[3])
        self.assertNotIn("Revised text", lines[3])
        with mock.patch.object(NotificationFeed, "follow", return_value=iter(entries)):
            status, out, err = self.run_cli("--follow", "--json")
        self.assertEqual((status, err), (0, ""))
        self.assertEqual([json.loads(line) for line in out.splitlines()], [original, edited, removed])

    def test_long_poll_timeout_and_arrival(self):
        baseline = self.feed.fetch().now_ms
        started = time.monotonic()
        self.assertEqual(self.feed.fetch(since_ms=baseline, wait=1).notifications, ())
        self.assertGreaterEqual(time.monotonic() - started, 0.8)
        timer = threading.Timer(0.1, self.phone.add_phone_notification)
        timer.start()
        try:
            batch = self.feed.fetch(since_ms=baseline, wait=1)
        finally:
            timer.join(timeout=2)
        self.assertEqual(len(batch.notifications), 1)

    def test_follow_advances_past_unmatched_and_stops_when_off(self):
        self.phone.add_phone_notification(posted_ms=100, app="Other")
        first = self.phone.add_phone_notification(posted_ms=101, text="first")
        stream = self.feed.follow(app="chat", wait=1)
        self.assertEqual(next(stream).to_dict(), first)
        # A newer unmatched item must advance the cursor before waiting again.
        self.phone.add_phone_notification(posted_ms=102, app="Other")
        timer = threading.Timer(0.15, lambda: self.phone.add_phone_notification(posted_ms=103, text="live"))
        timer.start()
        try:
            self.assertEqual(next(stream).text, "live")
        finally:
            timer.join(timeout=2)
        self.assertEqual([q["since"] for q in self.phone.notification_queries[-2:]], [101, 102])
        self.phone.set_notifications_enabled(False)
        with self.assertRaises(SharingOff):
            next(stream)

    def test_cli_follow_json_bounded(self):
        original = NotificationFeed.follow

        def bounded(feed, **kwargs):
            stream = original(feed, **kwargs)
            try:
                yield next(stream)
                yield next(stream)
            finally:
                stream.close()

        self.phone.add_phone_notification(posted_ms=100, text="first")
        timer = threading.Timer(0.15, lambda: self.phone.add_phone_notification(posted_ms=101, text="live"))
        timer.start()
        try:
            with mock.patch.object(NotificationFeed, "follow", bounded):
                status, out, err = self.run_cli("--follow", "--json")
        finally:
            timer.join(timeout=2)
        self.assertEqual((status, err), (0, ""))
        self.assertEqual([json.loads(line)["text"] for line in out.splitlines()], ["first", "live"])
        self.assertEqual(self.phone.notification_queries[-1]["wait"], 55)

    def test_plain_table_fits_terminal_and_neutralizes_controls(self):
        self.phone.add_phone_notification(app="消息", title="Title", text="Hello\n\x1b[31m" + "界" * 80)
        with mock.patch.object(notifications_cmd.shutil, "get_terminal_size", return_value=__import__("os").terminal_size((55, 20))):
            status, out, err = self.run_cli()
        self.assertEqual((status, err), (0, ""))
        self.assertEqual(len(out.splitlines()), 2)
        self.assertNotIn("\x1b", out)
        import unicodedata
        for line in out.splitlines():
            self.assertLessEqual(sum(2 if unicodedata.east_asian_width(c) in {"W", "F"} else 1 for c in line), 55)

    def test_bad_arguments_exit_two_and_empty_listing_succeeds(self):
        for args in (("--limit", "0"), ("--limit", "501"), ("--since", "nonsense")):
            with self.subTest(args=args):
                self.assertEqual(self.run_cli(*args)[0], 2)
        self.assertEqual(self.run_cli("--json"), (0, "", ""))

    def test_long_poll_extends_http_timeout(self):
        payload = {"notifications": [], "now_ms": 100, "enabled": True}
        with mock.patch.object(self.feed.client, "_json", return_value=payload) as request:
            self.feed.fetch(wait=60, since_ms=1)
        self.assertEqual(request.call_args.kwargs["timeout"], 65)


if __name__ == "__main__":
    unittest.main()
