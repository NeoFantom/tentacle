from __future__ import annotations

import io
import json
import tempfile
import threading
import time
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from tentacle.config import PhoneConfig
from tentacle.phone import PhoneClient, PhoneError
from tests.fakephone import FakePhone


class RequestPhoneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.server = FakePhone(Path(self.temporary.name), token="test-token").start()
        self.client = PhoneClient(
            PhoneConfig(url=self.server.url, token="test-token", timeout_s=2),
        )

    def tearDown(self) -> None:
        self.server.close()
        self.temporary.cleanup()

    def test_list_update_and_log(self) -> None:
        request = self.server.add_request(
            "Fix the crash", kind="bug", log="failure details\n",
        )

        envelope = self.client.requests()
        self.assertEqual(envelope["requests"], [request])
        self.assertIsInstance(envelope["now_ms"], int)
        self.assertEqual(
            self.client.request_log(str(request["id"])), b"failure details\n",
        )

        working = self.client.update_request(
            str(request["id"]), "working", "Computer is working on it",
        )
        done = self.client.update_request(str(request["id"]), "done", "Fixed")
        self.assertEqual(working["status"], "working")
        self.assertEqual(done["status"], "done")
        self.assertEqual(
            self.server.request_updates,
            [
                (str(request["id"]), "working", "Computer is working on it"),
                (str(request["id"]), "done", "Fixed"),
            ],
        )

    def test_long_poll_wakes_when_request_is_added(self) -> None:
        result: list[dict[str, object]] = []

        thread = threading.Thread(target=lambda: result.append(self.client.requests(wait=2)))
        started = time.monotonic()
        thread.start()
        time.sleep(0.1)
        request = self.server.add_request("Add a setting")
        thread.join(timeout=1)

        self.assertFalse(thread.is_alive())
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(result[0]["requests"], [request])

    def test_claim_conflict_and_missing_log_are_phone_errors(self) -> None:
        request = self.server.add_request("Add a setting")
        request_id = str(request["id"])
        self.server.claim_conflicts.add(request_id)

        with self.assertRaises(PhoneError) as conflict:
            self.client.update_request(request_id, "working", "Claiming")
        self.assertEqual(conflict.exception.status, 409)
        self.assertEqual(conflict.exception.code, "conflict")
        self.assertEqual(self.server.request_updates, [])

        with self.assertRaises(PhoneError) as missing:
            self.client.request_log(request_id)
        self.assertEqual(missing.exception.status, 404)
        self.assertEqual(missing.exception.code, "not_found")

    def test_each_request_gets_an_independent_failover_retry(self) -> None:
        client = PhoneClient(PhoneConfig(
            urls=["https://first.example", "https://second.example"],
            token="test-token",
            timeout_s=2,
        ))
        client._ping_cache = {"app": "tentacle", "api": 1}
        client._set_url("https://first.example")
        envelope = {"requests": [], "now_ms": 1}
        replies = [
            urllib.error.URLError("first path failed"),
            io.BytesIO(json.dumps(envelope).encode()),
            urllib.error.URLError("second path failed"),
            io.BytesIO(json.dumps(envelope).encode()),
        ]
        client._opener = mock.Mock()
        client._opener.open.side_effect = replies

        with mock.patch.object(client, "select_path") as select_path, \
             mock.patch.object(client.paths, "invalidate") as invalidate:
            self.assertEqual(client.requests(), envelope)
            self.assertEqual(client.requests(), envelope)

        self.assertEqual(select_path.call_count, 2)
        self.assertEqual(invalidate.call_count, 2)
        self.assertEqual(client._opener.open.call_count, 4)

    def test_invalid_request_envelope_is_rejected(self) -> None:
        with mock.patch.object(
            self.client,
            "_json",
            return_value={"requests": [{"id": "missing-fields"}], "now_ms": 1},
        ):
            with self.assertRaises(PhoneError) as caught:
                self.client.requests()
        self.assertEqual(caught.exception.code, "bad_response")

    def test_missing_scope_reaches_watcher_validation(self) -> None:
        request = self.server.add_request("Add a setting")
        request.pop("scope")
        with mock.patch.object(
            self.client,
            "_json",
            return_value={"requests": [request], "now_ms": 1},
        ):
            self.assertEqual(self.client.requests()["requests"], [request])


if __name__ == "__main__":
    unittest.main()
