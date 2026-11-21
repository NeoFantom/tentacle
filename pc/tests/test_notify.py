from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import shlex
import sys
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
            (0, "delivered (ring alerts are off in the app, sent as a normal alert)\n", ""),
        )
        self.assertEqual(self.phone.notify_requests[-1]["level"], "urgent")

    def test_levels_forced_downgrade_and_old_phone_fallback(self) -> None:
        self.phone.notify_options["urgent_allowed"] = True
        self.assertEqual(
            self.run_cli("Wake up", "--level", "ring"),
            (0, "delivered\n", ""),
        )
        self.assertEqual(self.phone.notify_requests[-1]["level"], "urgent")

        self.assertEqual(
            self.run_cli("Answer me", "--level", "forced"),
            (0, "delivered (forced alerts are off in the app, sent as a ring alert)\n", ""),
        )
        self.phone.notify_options["forced_supported"] = False
        code, output, error = self.run_cli("Old phone", "--level", "forced", "--json")
        self.assertEqual((code, error), (0, ""))
        reply = json.loads(output)
        self.assertEqual(reply["fallback"], "urgent")
        self.assertEqual(reply["level"], "urgent")
        self.assertEqual(self.phone.notify_requests[-1]["level"], "urgent")

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

    def test_forced_timeout_is_reported(self) -> None:
        self.phone.notify_options["forced_allowed"] = True

        def time_out() -> None:
            while not self.phone.notify_requests:
                time.sleep(0.01)
            self.phone.set_notification_state(str(self.phone.notify_requests[-1]["id"]), "timeout")

        thread = threading.Thread(target=time_out)
        thread.start()
        result = self.run_cli("Wake me", "--level", "forced", "--wait-ack", "2")
        thread.join(timeout=1)
        self.assertEqual(result, (
            1, "delivered\n", "forced alert stopped after its time limit without an answer\n",
        ))

        class TimedOutClient:
            def send_notification(self, *_args, **kwargs):
                return {"id": kwargs["notification_id"], "level": "forced",
                        "downgraded": False, "shown": True}

            def notification(self, notification_id, wait=0):
                return {"id": notification_id, "level": "forced", "state": "timeout"}

        with mock.patch.object(notify_cmd, "PhoneClient", return_value=TimedOutClient()):
            code, output, error = self.run_cli(
                "Wake me", "--level", "forced", "--wait-ack", "2", "--json",
            )
        lines = [json.loads(line) for line in output.splitlines()]
        self.assertEqual((code, error), (
            1, "forced alert stopped after its time limit without an answer\n",
        ))
        self.assertEqual([item.get("state") for item in lines], [None, "timeout"])

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

    def test_forced_fallback_handles_transport_retry_then_old_phone_response(self) -> None:
        calls: list[tuple[str, str]] = []

        class Client:
            def send_notification(self, _text, *, notification_id, title, level):
                calls.append((notification_id, level))
                if len(calls) == 1:
                    raise PhoneError("unreachable", "lost", None)
                if level == "forced":
                    raise PhoneError("bad_request", "unsupported", 400)
                return {"id": notification_id, "level": "normal", "downgraded": True,
                        "shown": True, "reason": "urgent_not_allowed"}

        result = notify_cmd._post_with_retry(
            Client(), text="Wake", title=None, level="forced", notification_id="same",
        )
        self.assertEqual(calls, [("same", "forced"), ("same", "forced"), ("same", "urgent")])
        self.assertEqual(result["fallback"], "urgent")
        self.assertEqual(
            notify_cmd._delivery_message(result),
            "phone app does not support forced alerts; ring alerts are off in the app, sent as a normal alert",
        )

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
        args = mock.Mock(text="Text", question_args=[], title=None, level=None, urgent=False,
                         wait_ack=120, json=False, count=30, out=None, no_push=False)
        with mock.patch.object(notify_cmd, "PhoneClient", return_value=client), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(notify_cmd._run(args, mock.Mock()), 0)
        self.assertEqual(client.waits, [55])

    def test_questions_show_push_clear_and_old_phone(self) -> None:
        self.assertEqual(
            self.run_cli("questions"),
            (0, "Questions: 90 from built-in (English: 45, Chinese: 45)\n", ""),
        )
        question_file = self.root / "questions.json"
        question_file.write_text(json.dumps([
            {"lang": "en", "q": "12 × 3 = ?", "answers": ["36"], "domain": "math"},
            {"lang": "zh", "q": "水的化学式是什么？", "answers": ["H2O"], "domain": "chemistry"},
        ], ensure_ascii=False), encoding="utf-8")
        self.assertEqual(
            self.run_cli("questions", "push", str(question_file)),
            (0, "Questions: 2 from your computer (English: 1, Chinese: 1)\n", ""),
        )
        self.assertEqual(
            self.run_cli("questions", "clear"),
            (0, "Questions: 90 from built-in (English: 45, Chinese: 45)\n", ""),
        )
        self.phone.notify_options["questions_supported"] = False
        self.assertEqual(
            self.run_cli("questions", "show"),
            (1, "", "the phone app is too old for custom questions\n"),
        )

    def test_question_generate_extracts_json_drops_invalid_and_can_skip_push(self) -> None:
        questions = []
        for lang in ("en", "zh"):
            for index in range(10):
                questions.append({
                    "lang": lang, "q": f"{lang} prompt {index}",
                    "answers": [str(index)], "domain": "math",
                })
        questions += [questions[0], {"lang": "xx", "q": "bad", "answers": ["bad"]}]
        script = self.root / "generator.py"
        script.write_text(
            "import json,sys\nsys.stdin.read()\nprint('prose before')\n"
            f"print('```json')\nprint(json.dumps({json.dumps({'questions': questions})}))\n"
            "print('``` prose after')\n",
            encoding="utf-8",
        )
        generated = self.root / "generated.json"
        self.config.write_text(
            self.config.read_text(encoding="utf-8") +
            f'\n[notify]\nquestion_command = {json.dumps(shlex.join([sys.executable, str(script)]))}\n',
            encoding="utf-8",
        )
        code, output, error = self.run_cli(
            "questions", "generate", "--count", "10", "--out", str(generated), "--no-push",
        )
        self.assertEqual((code, error), (0, ""))
        self.assertIn("Generated 20 questions", output)
        self.assertEqual(len(json.loads(generated.read_text())["questions"]), 20)

    def test_question_generate_rejects_invalid_output_and_missing_command(self) -> None:
        self.assertEqual(self.run_cli("questions", "generate", "--no-push"), (
            2, "", "usage/config error: no [notify] question_command is configured; "
            "set it to an AI CLI command\n",
        ))
        script = self.root / "invalid.py"
        script.write_text("print('not json')\n", encoding="utf-8")
        self.config.write_text(
            self.config.read_text(encoding="utf-8") +
            f'\n[notify]\nquestion_command = {json.dumps(shlex.join([sys.executable, str(script)]))}\n',
            encoding="utf-8",
        )
        code, output, error = self.run_cli("questions", "generate", "--no-push")
        self.assertEqual((code, output), (1, ""))
        self.assertIn("did not contain a JSON", error)

    def test_question_validation_drops_untrusted_types_and_checks_wire_size(self) -> None:
        invalid = [
            {"lang": [], "q": "bad", "answers": ["answer"]},
            {"lang": "en", "q": "bad", "answers": ["answer"], "domain": []},
            {"lang": "en", "q": "bad", "answers": ["answer"], "domain": None},
        ]
        self.assertEqual(notify_cmd._validated_questions(invalid, drop_invalid=True), [])

        client = PhoneClient(PhoneConfig(
            url=self.phone.url, token="test-token", timeout_s=2,
        ))
        with mock.patch.object(client, "_json", return_value={
            "source": [], "count": 0, "counts": {"en": 0, "zh": 0},
        }):
            with self.assertRaises(PhoneError):
                client.notify_questions()
        oversized = [
            {"lang": "zh", "q": "问" * 200 + str(index), "answers": ["答" * 60]}
            for index in range(150)
        ]
        with self.assertRaisesRegex(ValueError, "64 KiB"):
            client.put_notify_questions(oversized)


if __name__ == "__main__":
    unittest.main()
