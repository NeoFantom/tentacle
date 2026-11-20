from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest import mock

from tentacle import cli, notify_cmd
from tentacle.config import PhoneConfig
from tentacle.phone import PhoneClient, PhoneError
from tests.fakephone import FakePhone


class NotifyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.phone = FakePhone(self.root, token="test-token").start()
        self.addCleanup(self.phone.close)
        self.config = self.root / "config.toml"
        self.config.write_text(
            f'[phone]\nurl = {json.dumps(self.phone.url)}\ntoken = "test-token"\ntimeout_s = 2\n',
            encoding="utf-8",
        )

    def run_cli(
        self, *arguments: str, config_path: Path | None = None,
    ) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main([
                "--config", str(config_path or self.config), "notify", *arguments,
            ])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_delivered_and_json_response(self) -> None:
        self.assertEqual(self.run_cli("Task finished"), (0, "delivered\n", ""))
        code, output, error = self.run_cli("Need a decision", "--title", "Tentacle", "--json")
        self.assertEqual((code, error), (0, ""))
        reply = json.loads(output)
        self.assertTrue(reply["shown"])
        self.assertEqual(reply["level"], "normal")
        self.assertEqual(self.phone.notify_requests[-1]["title"], "Tentacle")

    def test_urgent_is_downgraded_when_owner_did_not_allow_it(self) -> None:
        self.assertEqual(
            self.run_cli("Wake up", "--urgent"),
            (0, "delivered (urgent alerts are off in the app, sent as a normal notification)\n", ""),
        )

    def test_full_screen_fallback_has_exact_delivery_message(self) -> None:
        self.phone.notify_options["urgent_allowed"] = True
        self.phone.notify_options["full_screen_allowed"] = False
        self.assertEqual(
            self.run_cli("Wake up", "--urgent"),
            (0, "delivered (full-screen alerts not allowed on the phone)\n", ""),
        )

    def test_not_shown_and_rate_limited_exit_one(self) -> None:
        self.phone.notify_options["shown"] = False
        self.assertEqual(
            self.run_cli("Hello"),
            (1, "not shown: notifications_disabled\n", ""),
        )
        self.phone.notify_options["shown"] = True
        self.phone.set_notify_rate_limited(True)
        code, output, error = self.run_cli("Again")
        self.assertEqual((code, output), (1, ""))
        self.assertIn("rate limited", error)

    def test_invalid_text_is_a_usage_error_without_contacting_phone(self) -> None:
        self.assertEqual(
            self.run_cli("bad\ttext"),
            (2, "", "usage/config error: notification text must not contain control characters\n"),
        )
        self.assertEqual(self.phone.notify_requests, [])

    def test_wait_ack_wakes_and_timeout_exits_one(self) -> None:
        def acknowledge() -> None:
            while not self.phone.notify_requests:
                time.sleep(0.01)
            self.phone.set_notification_state(str(self.phone.notify_requests[-1]["id"]), "acknowledged")

        thread = threading.Thread(target=acknowledge)
        thread.start()
        result = self.run_cli("Please look", "--wait-ack", "2")
        thread.join(timeout=1)
        self.assertEqual(result, (0, "delivered\nacknowledged\n", ""))

        self.assertEqual(
            self.run_cli("No wait", "--wait-ack", "0"),
            (1, "delivered\n", "no response before timeout\n"),
        )

    def test_json_wait_prints_json_delivery_and_terminal_state(self) -> None:
        def dismiss() -> None:
            while not self.phone.notify_requests:
                time.sleep(0.01)
            self.phone.set_notification_state(str(self.phone.notify_requests[-1]["id"]), "dismissed")

        thread = threading.Thread(target=dismiss)
        thread.start()
        code, output, error = self.run_cli("Please look", "--wait-ack", "2", "--json")
        thread.join(timeout=1)
        lines = [json.loads(line) for line in output.splitlines()]
        self.assertEqual((code, error), (0, ""))
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[0]["shown"])
        self.assertEqual(lines[1]["state"], "dismissed")
        self.assertEqual(lines[0]["id"], lines[1]["id"])

    def test_positive_timeout_uses_a_long_poll_without_large_overshoot(self) -> None:
        started = time.monotonic()
        result = self.run_cli("Please look", "--wait-ack", "1")
        elapsed = time.monotonic() - started
        self.assertEqual(result, (1, "delivered\n", "no response before timeout\n"))
        self.assertGreaterEqual(elapsed, 0.8)
        self.assertLess(elapsed, 2.0)

    def test_unpaired_phone_is_a_usage_or_pairing_error(self) -> None:
        unpaired = self.root / "unpaired.toml"
        unpaired.write_text("[phone]\n", encoding="utf-8")
        self.assertEqual(
            self.run_cli("Hello", config_path=unpaired),
            (2, "", "Phone notification failed (not_paired).\n"),
        )

        wrong_token = self.root / "wrong-token.toml"
        wrong_token.write_text(
            f'[phone]\nurl = {json.dumps(self.phone.url)}\ntoken = "wrong"\n',
            encoding="utf-8",
        )
        self.assertEqual(
            self.run_cli("Hello", config_path=wrong_token),
            (2, "", "Phone notification failed (unauthorized).\n"),
        )

    def test_client_rejects_mismatched_response_ids(self) -> None:
        client = PhoneClient(PhoneConfig(
            url=self.phone.url, token="test-token", timeout_s=2,
        ))
        delivery = {
            "id": "other", "level": "normal", "downgraded": False, "shown": True,
        }
        with mock.patch.object(client, "_json", return_value=delivery):
            with self.assertRaises(PhoneError) as post_error:
                client.send_notification("Hello", notification_id="expected")
        self.assertEqual(post_error.exception.code, "bad_response")

        state = {"id": "other", "level": "normal", "state": "shown"}
        with mock.patch.object(client, "_json", return_value=state):
            with self.assertRaises(PhoneError) as get_error:
                client.notification("expected")
        self.assertEqual(get_error.exception.code, "bad_response")

    def test_same_id_with_different_content_conflicts(self) -> None:
        client = PhoneClient(PhoneConfig(
            url=self.phone.url, token="test-token", timeout_s=2,
        ))
        first = client.send_notification("First", notification_id="same-id")
        self.assertTrue(first["shown"])
        with self.assertRaises(PhoneError) as caught:
            client.send_notification("Different", notification_id="same-id")
        self.assertEqual((caught.exception.status, caught.exception.code), (409, "conflict"))

    def test_retry_reuses_the_same_id_after_lost_response(self) -> None:
        real_client = notify_cmd.PhoneClient(
            mock.Mock(
                resolved_token=lambda: "test-token", timeout_s=2, find_timeout_s=120,
                use_system_proxy=False, urls=[], url=self.phone.url,
            ),
        )

        class LostResponseClient:
            def __init__(self) -> None:
                self.first = True
                self.ids: list[str] = []

            def send_notification(self, *args, **kwargs):
                self.ids.append(kwargs["notification_id"])
                reply = real_client.send_notification(*args, **kwargs)
                if self.first:
                    self.first = False
                    raise PhoneError("unreachable", "connection closed", None)
                return reply

        lost_response = LostResponseClient()
        with mock.patch.object(notify_cmd, "PhoneClient", return_value=lost_response):
            result = self.run_cli("Only once")
        self.assertEqual(result, (0, "delivered\n", ""))
        self.assertEqual(len(lost_response.ids), 2)
        self.assertEqual(lost_response.ids[0], lost_response.ids[1])
        self.assertEqual(len(self.phone.notify_requests), 1)

    def test_wait_is_chunked_and_bare_option_defaults_to_300_seconds(self) -> None:
        parser = cli.build_parser()
        parsed = parser.parse_args(["notify", "Text", "--wait-ack"])
        self.assertEqual(parsed.wait_ack, 300)

        class Client:
            def __init__(self, *_args, **_kwargs):
                self.waits: list[int] = []

            def send_notification(self, *_args, **_kwargs):
                return {"id": "n-1", "level": "normal", "downgraded": False, "shown": True}

            def notification(self, _notification_id, wait=0):
                self.waits.append(wait)
                return {"id": "n-1", "level": "normal", "state": "dismissed"}

        client = Client()
        args = mock.Mock(text="Text", title=None, urgent=False, wait_ack=120, json=False)
        with mock.patch.object(notify_cmd, "PhoneClient", return_value=client), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(notify_cmd._run(args, mock.Mock()), 0)
        self.assertEqual(client.waits, [55])


if __name__ == "__main__":
    unittest.main()
