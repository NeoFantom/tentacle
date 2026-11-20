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

from tentacle import cli
from tentacle import codes_cmd
from tentacle.config import PhoneConfig
from tentacle.phone import PhoneClient, PhoneError
from tests.fakephone import FakePhone


class CodeRelayTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.phone = FakePhone(self.root, token="test-token", clock_offset_s=3600).start()
        self.addCleanup(self.phone.close)
        self.config = self.root / "config.toml"
        self.config.write_text(
            f'[phone]\nurl = {json.dumps(self.phone.url)}\ntoken = "test-token"\ntimeout_s = 2\n',
            encoding="utf-8",
        )

    def run_cli(self, *arguments: str) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = cli.main(["--config", str(self.config), "code", *arguments])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_client_lists_codes_and_validates_response(self) -> None:
        item = self.phone.add_code("AB12CD", "Microsoft", source="sms")
        client = PhoneClient(PhoneConfig(
            url=self.phone.url, token="test-token", timeout_s=2,
        ))
        payload = client.codes(since_ms=int(item["received_ms"]) - 1)
        self.assertEqual(payload["codes"], [item])
        self.assertTrue(payload["enabled"])
        with mock.patch.object(client, "_json", return_value={
                "codes": [{"id": "bad"}], "now_ms": 1, "enabled": True}):
            with self.assertRaises(PhoneError) as caught:
                client.codes()
        self.assertEqual(caught.exception.code, "bad_response")

    def test_baseline_omits_since_then_long_poll_uses_phone_clock(self) -> None:
        client = PhoneClient(PhoneConfig(
            url=self.phone.url, token="test-token", timeout_s=2,
        ))
        baseline = client.codes(wait=0)
        started = time.monotonic()
        result = client.codes(since_ms=baseline["now_ms"], wait=1)
        elapsed = time.monotonic() - started
        self.assertEqual(result["codes"], [])
        self.assertGreaterEqual(elapsed, 0.8)
        self.assertLess(elapsed, 2.0)
        self.assertEqual(
            self.phone.code_queries[-2:],
            [(None, 0), (baseline["now_ms"], 1)],
        )

    def test_waits_for_matching_sender_and_prints_only_code(self) -> None:
        def inject() -> None:
            time.sleep(0.1)
            self.phone.add_code("111111", "Other service")
            time.sleep(0.1)
            self.phone.add_code("654321", "阿里云", snippet="【阿里云】验证码654321")

        thread = threading.Thread(target=inject)
        thread.start()
        result = self.run_cli("--from", "阿里", "--wait", "2")
        thread.join(timeout=1)
        self.assertEqual(result, (0, "654321\n", ""))

    def test_sender_filter_also_matches_snippet_and_json_returns_object(self) -> None:
        item = self.phone.add_code(
            "246810", "Messages", snippet="Your Apple ID Code is: 246810",
        )
        code, output, error = self.run_cli("--from", "apple", "--since", "30s", "--json")
        self.assertEqual((code, error), (0, ""))
        self.assertEqual(json.loads(output), item)

    def test_since_now_ignores_existing_code_and_duration_includes_it(self) -> None:
        self.phone.add_code("112233", "GitHub")
        code, output, error = self.run_cli("--wait", "0")
        self.assertEqual((code, output), (1, ""))
        self.assertIn("No verification code", error)
        self.assertEqual(self.run_cli("--since", "2m", "--wait", "0"),
                         (0, "112233\n", ""))

    def test_since_window_returns_the_newest_matching_code(self) -> None:
        self.phone.add_code("111111", "GitHub")
        time.sleep(0.01)
        self.phone.add_code("222222", "GitHub")
        self.assertEqual(self.run_cli("--from", "github", "--since", "2m", "--wait", "0"),
                         (0, "222222\n", ""))

    def test_no_match_times_out(self) -> None:
        self.phone.add_code("123456", "GitHub")
        code, output, error = self.run_cli(
            "--from", "Microsoft", "--since", "30s", "--wait", "0",
        )
        self.assertEqual((code, output), (1, ""))
        self.assertIn("from Microsoft", error)

    def test_disabled_exits_two_immediately(self) -> None:
        self.phone.set_codes_enabled(False)
        started = time.monotonic()
        code, output, error = self.run_cli("--wait", "120")
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual((code, output), (2, ""))
        self.assertEqual(
            error,
            "Code relay is off in the app (Settings → Relay verification codes)\n",
        )

    def test_long_poll_cycles_are_capped_and_advance_past_unmatched(self) -> None:
        unmatched = {
            "id": "c-1", "code": "111111", "sender": "Other",
            "source": "notification", "snippet": "Other code 111111",
            "received_ms": 1_100,
        }
        matched = {
            "id": "c-2", "code": "222222", "sender": "Wanted",
            "source": "sms", "snippet": "Wanted code 222222",
            "received_ms": 1_200,
        }

        class Client:
            def __init__(self, *_args, **_kwargs):
                self.calls: list[tuple[int | None, int]] = []
                self.replies = iter([
                    {"codes": [], "now_ms": 1_000, "enabled": True},
                    {"codes": [], "now_ms": 1_001, "enabled": True},
                    {"codes": [unmatched], "now_ms": 1_100, "enabled": True},
                    {"codes": [matched], "now_ms": 1_200, "enabled": True},
                ])

            def codes(self, since_ms=None, wait=0):
                self.calls.append((since_ms, wait))
                return next(self.replies)

        client = Client()
        arguments = mock.Mock(wait=120, since="now", sender="wanted", json=False)
        cfg = mock.Mock()
        with mock.patch.object(codes_cmd, "PhoneClient", return_value=client), \
             mock.patch.object(codes_cmd.time, "sleep") as sleep, \
             contextlib.redirect_stdout(output := io.StringIO()), \
             contextlib.redirect_stderr(error := io.StringIO()):
            result = codes_cmd._run(arguments, cfg)
        self.assertEqual((result, output.getvalue(), error.getvalue()), (0, "222222\n", ""))
        self.assertEqual(client.calls, [
            (None, 0), (1_000, 55), (1_000, 55), (1_100, 55),
        ])
        self.assertTrue(all(wait <= 55 for _since, wait in client.calls))
        sleep.assert_called_once()


if __name__ == "__main__":
    unittest.main()
