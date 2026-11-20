"""Offline tests of multi-address pairing and path selection."""
from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from tentacle import cli, config, paths
from tentacle.paths import PathResult, PathSelector
from tentacle.phone import PhoneClient, PhoneError, parse_pair
from tests.fakephone import FakePhone


def _dead_url() -> str:
    """A loopback URL whose port is closed (connection refused immediately)."""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    return f"http://127.0.0.1:{port}"


def _status(ip: str, *, cur_addr: str = "", relay: str = "fra") -> dict:
    return {"Peer": {"nodekey:1": {"TailscaleIPs": [ip, "fd7a:115c:a1e0::1"],
                                   "CurAddr": cur_addr, "Relay": relay}}}


class Clock:
    def __init__(self, now: float = 1000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


class PairingLinkTests(unittest.TestCase):
    def test_multiple_urls_keep_order_and_drop_duplicates(self):
        urls, token, name = parse_pair(
            "tentacle://pair?name=Phone&token=a%2Bb"
            "&url=http%3A%2F%2F100.64.0.1%3A8765"
            "&url=http%3A%2F%2F192.0.2.10%3A8765%2F"
            "&url=https%3A%2F%2Fexample.com%2Ftentacle"
            "&url=http%3A%2F%2F100.64.0.1%3A8765")
        self.assertEqual(urls, ["http://100.64.0.1:8765", "http://192.0.2.10:8765",
                                "https://example.com/tentacle"])
        self.assertEqual((token, name), ("a+b", "Phone"))

    def test_single_url_forms_still_parse(self):
        self.assertEqual(parse_pair("tentacle://pair?token=t&url=http%3A%2F%2F192.0.2.1%3A8765")[0],
                         ["http://192.0.2.1:8765"])
        self.assertEqual(parse_pair("tentacle://192.0.2.1:8765?token=t")[0], ["http://192.0.2.1:8765"])
        self.assertEqual(parse_pair("https://example.com/tentacle/", "t")[0],
                         ["https://example.com/tentacle"])


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.probed: list[str] = []
        self.answers: dict[str, PathResult] = {}

    def prober(self, url: str) -> PathResult:
        self.probed.append(url)
        answer = self.answers.get(url)
        return dataclasses.replace(answer) if answer else PathResult(url, "unreachable", detail="refused")

    def selector(self, urls, *, status=None, clock=None) -> PathSelector:
        phone = config.PhoneConfig(urls=list(urls), token="t")
        return PathSelector(phone, self.dir, prober=self.prober, status=status or (lambda: None),
                            clock=clock or Clock())

    def test_first_working_candidate_wins_in_order(self):
        tailnet, lan, public = "http://100.64.0.1:8765", "http://192.0.2.10:8765", "https://example.com/t"
        self.answers = {lan: PathResult(lan, "ok", 3.0), public: PathResult(public, "ok", 80.0)}
        selection = self.selector([tailnet, lan, public]).select(wait_all=True)
        self.assertEqual(selection.url, lan)
        self.assertEqual([r.status for r in selection.results], ["unreachable", "ok", "ok"])
        self.assertEqual(sorted(self.probed), sorted([tailnet, lan, public]))
        # A reachable LAN address beats an earlier tailnet one; otherwise order decides.
        self.answers[tailnet] = PathResult(tailnet, "ok", 9.0)
        self.assertEqual(self.selector([tailnet, lan, public]).select(refresh=True).url, lan)
        self.answers.pop(lan)
        self.assertEqual(self.selector([tailnet, lan, public]).select(refresh=True).url, tailnet)

    def test_lan_addresses_reported_by_the_phone_are_learned_and_preferred(self):
        public, new_lan = "https://example.com/t", "http://10.1.2.3:8765"
        self.answers = {public: PathResult(public, "ok", 80.0, lan=[new_lan]),
                        new_lan: PathResult(new_lan, "ok", 2.0)}
        selection = self.selector([public]).select()
        self.assertEqual(selection.url, new_lan)
        self.assertEqual(self.probed, [public, new_lan])
        # Remembered: next selection probes the learned LAN address directly.
        self.probed.clear()
        clock = Clock(5000.0)
        self.assertEqual(self.selector([public], clock=clock).select().url, new_lan)
        self.assertIn(new_lan, self.probed)
        # Phone moved to another network: LAN address dead, falls back, keeps working.
        self.answers = {public: PathResult(public, "ok", 80.0, lan=[])}
        selector = self.selector([public], clock=Clock(9000.0))
        self.assertEqual(selector.select(refresh=True).url, public)
        self.assertEqual(selector.learned_lan(), [])

    def test_invalidate_keeps_learned_lan_addresses(self):
        public, lan = "https://example.com/t", "http://10.1.2.3:8765"
        self.answers = {public: PathResult(public, "ok", 80.0, lan=[lan]), lan: PathResult(lan, "ok", 2.0)}
        selector = self.selector([public])
        selector.select()
        selector.invalidate()
        self.assertIsNone(selector.cached())
        self.assertEqual(selector.learned_lan(), [lan])

    def test_only_private_http_urls_are_accepted_from_the_phone(self):
        payload = {"lan": ["http://10.0.0.5:8765", "https://10.0.0.6:8765", "http://8.8.8.8:8765",
                           "http://100.64.0.1:8765", 7, "http://192.168.1.4:8765/"]}
        self.assertEqual(paths._lan_urls(payload), ["http://10.0.0.5:8765", "http://192.168.1.4:8765"])

    def test_relayed_tailnet_is_demoted_when_a_later_candidate_works(self):
        tailnet, public = "http://100.64.0.1:8765", "https://example.com/t"
        self.answers = {tailnet: PathResult(tailnet, "ok", 400.0), public: PathResult(public, "ok", 90.0)}
        relayed = lambda: _status("100.64.0.1")  # noqa: E731
        selection = self.selector([tailnet, public], status=relayed).select()
        self.assertEqual(selection.url, public)
        self.assertEqual(selection.results[0].status, "relayed")
        # Nothing else works: the relayed tailnet path is still used.
        self.answers.pop(public)
        self.assertEqual(self.selector([tailnet, public], status=relayed).select(refresh=True).url, tailnet)
        # A direct path is accepted at its position.
        self.answers[public] = PathResult(public, "ok", 90.0)
        direct = lambda: _status("100.64.0.1", cur_addr="192.0.2.7:41641", relay="")  # noqa: E731
        selection = self.selector([tailnet, public], status=direct).select(refresh=True)
        self.assertEqual((selection.url, selection.results[0].status), (tailnet, "direct"))
        # Unknown peer or no tailscale CLI: accepted as plain "ok".
        selection = self.selector([tailnet, public], status=lambda: _status("100.64.0.2")).select(refresh=True)
        self.assertEqual((selection.url, selection.results[0].status), (tailnet, "ok"))

    def test_tailscale_status_parsing_uses_the_cli_json(self):
        completed = mock.Mock(returncode=0, stdout=json.dumps(_status("100.64.0.1")))
        with mock.patch("shutil.which", return_value="/usr/bin/tailscale"):
            status = paths.tailscale_status(run=mock.Mock(return_value=completed))
        self.assertEqual(paths.peer_path(status, "100.64.0.1"), "relayed")
        self.assertIsNone(paths.peer_path(status, "100.64.0.9"))
        with mock.patch("shutil.which", return_value=None):
            self.assertIsNone(paths.tailscale_status())
        failed = mock.Mock(returncode=1, stdout="")
        with mock.patch("shutil.which", return_value="/usr/bin/tailscale"):
            self.assertIsNone(paths.tailscale_status(run=mock.Mock(return_value=failed)))

    def test_cache_expiry_and_invalidation(self):
        lan, public = "http://192.0.2.10:8765", "https://example.com/t"
        self.answers = {lan: PathResult(lan, "ok", 3.0), public: PathResult(public, "ok", 80.0)}
        clock = Clock()
        self.assertEqual(self.selector([lan, public], clock=clock).select().url, lan)
        self.assertEqual(len(self.probed), 2)
        clock.now += 59
        cached = self.selector([lan, public], clock=clock).select()
        self.assertEqual((cached.url, cached.cached, len(self.probed)), (lan, True, 2))
        # A changed candidate list does not reuse the cache.
        self.assertFalse(self.selector([public, lan], clock=clock).select().cached)
        self.probed.clear()
        clock.now += 61
        self.assertFalse(self.selector([public, lan], clock=clock).select().cached)
        self.assertEqual(len(self.probed), 2)
        selector = self.selector([public, lan], clock=clock)
        self.assertTrue(selector.select().cached)
        selector.invalidate()
        self.assertFalse(selector.select().cached)
        retried = selector.select(exclude=[public])
        self.assertEqual(retried.url, lan)

    def test_nothing_reachable(self):
        with self.assertRaisesRegex(LookupError, "no phone address is reachable"):
            self.selector(["http://192.0.2.10:8765", "https://example.com/t"]).select()

    def test_proxy_is_bypassed_for_local_and_tailnet_addresses(self):
        for url in ("http://127.0.0.1:1", "http://192.168.1.2:8765", "http://100.64.0.1:8765",
                    "http://phone.local:8765", "http://[fe80::1]:8765"):
            self.assertTrue(paths.is_local(url), url)
        self.assertFalse(paths.is_local("https://example.com/t"))
        self.assertTrue(paths.is_tailnet("http://100.127.255.254:8765"))
        self.assertFalse(paths.is_tailnet("http://100.128.0.1:8765"))


class RaceTests(unittest.TestCase):
    """Selection must not wait for slow or dead candidates once the answer is known."""

    TAILNET, LAN, PUBLIC = "http://100.64.0.1:8765", "http://192.0.2.10:8765", "https://example.com/t"
    DEAD = "http://198.51.100.9:8765"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.plan: dict[str, tuple[float, str]] = {}
        self.finished: list[str] = []

    def prober(self, url: str) -> PathResult:
        delay, status = self.plan.get(url, (0.0, "unreachable"))
        time.sleep(delay)
        self.finished.append(url)
        return PathResult(url, status, round(delay * 1000, 1))

    def select(self, urls, *, status=None, wait_all=False):
        phone = config.PhoneConfig(urls=list(urls), token="t")
        selector = PathSelector(phone, Path(self.tmp.name), prober=self.prober,
                                status=status or (lambda: None), clock=Clock())
        started = time.monotonic()
        selection = selector.select(refresh=True, wait_all=wait_all)
        return selection, time.monotonic() - started

    def test_slow_unreachable_candidates_do_not_delay_a_lan_answer(self):
        self.plan = {self.TAILNET: (2.0, "unreachable"), self.DEAD: (2.0, "unreachable"),
                     self.LAN: (0.05, "ok"), self.PUBLIC: (1.5, "ok")}
        selection, elapsed = self.select([self.TAILNET, self.DEAD, self.LAN, self.PUBLIC])
        self.assertEqual(selection.url, self.LAN)
        self.assertLess(elapsed, 0.5)
        statuses = {r.url: r.status for r in selection.results}
        self.assertEqual(statuses[self.TAILNET], "pending")
        self.assertFalse(selection.results[0].reachable)

    def test_first_good_answer_waits_only_a_short_grace(self):
        self.plan = {self.TAILNET: (2.0, "unreachable"), self.DEAD: (2.0, "unreachable"),
                     self.PUBLIC: (0.05, "ok")}
        selection, elapsed = self.select([self.TAILNET, self.DEAD, self.PUBLIC])
        self.assertEqual(selection.url, self.PUBLIC)
        self.assertGreaterEqual(elapsed, paths.LAN_GRACE_S)
        self.assertLess(elapsed, 0.8)

    def test_lan_answer_within_the_grace_still_wins(self):
        self.plan = {self.PUBLIC: (0.0, "ok"), self.LAN: (0.1, "ok"), self.DEAD: (2.0, "unreachable")}
        selection, elapsed = self.select([self.PUBLIC, self.DEAD, self.LAN])
        self.assertEqual(selection.url, self.LAN)
        self.assertLess(elapsed, 0.5)
        # Too late for the grace: the public path is kept.
        self.plan[self.LAN] = (1.0, "ok")
        selection, elapsed = self.select([self.PUBLIC, self.DEAD, self.LAN])
        self.assertEqual(selection.url, self.PUBLIC)
        self.assertLess(elapsed, 0.8)

    def test_earlier_good_candidate_in_the_grace_keeps_list_order(self):
        self.plan = {self.TAILNET: (0.1, "ok"), self.PUBLIC: (0.0, "ok")}
        direct = lambda: _status("100.64.0.1", cur_addr="192.0.2.7:41641", relay="")  # noqa: E731
        selection, _ = self.select([self.TAILNET, self.PUBLIC], status=direct)
        self.assertEqual((selection.url, selection.results[0].status), (self.TAILNET, "direct"))

    def test_relayed_tailnet_is_used_only_when_nothing_else_answers(self):
        relayed = lambda: _status("100.64.0.1")  # noqa: E731
        self.plan = {self.TAILNET: (0.0, "ok"), self.PUBLIC: (0.4, "ok"), self.DEAD: (0.6, "unreachable")}
        selection, elapsed = self.select([self.TAILNET, self.PUBLIC, self.DEAD], status=relayed)
        self.assertEqual(selection.url, self.PUBLIC)
        self.assertLess(elapsed, 1.0)
        self.plan = {self.TAILNET: (0.0, "ok"), self.DEAD: (0.3, "unreachable")}
        selection, elapsed = self.select([self.TAILNET, self.DEAD], status=relayed)
        self.assertEqual((selection.url, selection.results[0].status), (self.TAILNET, "relayed"))
        self.assertGreaterEqual(elapsed, 0.3)

    def test_slow_tailscale_status_does_not_hold_up_other_paths(self):
        def slow_status():
            time.sleep(2.0)
            return _status("100.64.0.1")
        self.plan = {self.TAILNET: (0.0, "ok"), self.PUBLIC: (0.05, "ok")}
        selection, elapsed = self.select([self.TAILNET, self.PUBLIC], status=slow_status)
        self.assertEqual(selection.url, self.PUBLIC)
        self.assertLess(elapsed, 0.8)

    def test_wait_all_collects_every_result(self):
        self.plan = {self.LAN: (0.0, "ok"), self.DEAD: (0.3, "unreachable")}
        selection, elapsed = self.select([self.DEAD, self.LAN], wait_all=True)
        self.assertEqual(selection.url, self.LAN)
        self.assertGreaterEqual(elapsed, 0.3)
        self.assertEqual([r.status for r in selection.results], ["unreachable", "ok"])

    def test_wait_all_does_not_poll_an_expired_grace_deadline(self):
        self.plan = {self.LAN: (0.0, "ok"), self.DEAD: (0.3, "unreachable")}
        race = paths._Race(self.prober, lambda: None, set(), wait_all=True)
        original_get = race._events.get
        polls = 0

        def counted_get(*args, **kwargs):
            nonlocal polls
            polls += 1
            return original_get(*args, **kwargs)

        race._events.get = counted_get
        chosen, results, _reports = race.run([self.DEAD, self.LAN])
        self.assertEqual(chosen.url, self.LAN)
        self.assertEqual([result.status for result in results], ["unreachable", "ok"])
        self.assertLess(polls, 10)

    def test_unfinished_probes_run_on_daemon_threads(self):
        self.plan = {self.LAN: (0.0, "ok"), self.DEAD: (1.0, "unreachable")}
        self.select([self.DEAD, self.LAN])
        probes = [t for t in threading.enumerate() if t.name == "tentacle-probe"]
        self.assertTrue(probes)
        self.assertTrue(all(t.daemon for t in probes))


class FakePhonePathTests(unittest.TestCase):
    """Real HTTP probes against the fake phone and a closed port."""

    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        root = Path(cls.temporary.name) / "phone"
        root.mkdir()
        (root / "note.txt").write_text("hello\n", encoding="utf-8")
        cls.first = FakePhone(root).start()
        cls.second = FakePhone(root).start()

    @classmethod
    def tearDownClass(cls):
        cls.first.close()
        cls.second.close()
        cls.temporary.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.cfg = self.dir / "config.toml"

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = cli.main(["--config", str(self.cfg), *args])
        return code, stdout.getvalue(), stderr.getvalue()

    def write_config(self, urls):
        self.cfg.write_text("[phone]\nurl = " + json.dumps(urls[0]) + "\nurls = " + json.dumps(urls)
                            + '\ntoken = "t"\n[storage]\ndata_dir = ' + json.dumps(str(self.dir / "data"))
                            + '\n[asr]\nbackend = "none"\n', encoding="utf-8")

    def test_pair_with_several_urls_selects_and_stores_them(self):
        dead = _dead_url()
        link = ("tentacle://pair?name=Phone&token=t&url=" + dead.replace(":", "%3A").replace("/", "%2F")
                + "&url=" + self.second.url.replace(":", "%3A").replace("/", "%2F"))
        code, out, err = self.run_cli("pair", link, "--json")
        self.assertEqual((code, err), (0, ""))
        result = json.loads(out)
        self.assertEqual(result["base_url"], self.second.url)
        self.assertEqual([(p["url"], p["status"], p["chosen"]) for p in result["paths"]],
                         [(dead, "unreachable", False), (self.second.url, "ok", True)])
        loaded = config.load(self.cfg)
        self.assertEqual((loaded.phone.url, loaded.phone.urls), (dead, [dead, self.second.url]))
        # Pairing a single address afterwards clears the candidate list.
        code, _out, err = self.run_cli("pair", self.first.url, "--token", "t")
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(config.load(self.cfg).phone.urls, [])

    def test_ping_prints_each_candidate_and_the_chosen_one(self):
        dead = _dead_url()
        self.write_config([dead, self.first.url, self.second.url])
        code, out, err = self.run_cli("ping")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("Paths (in preference order):", out)
        lines = {line.split()[0]: line for line in out.splitlines() if line.startswith("  ")}
        self.assertIn("unreachable", lines[dead])
        self.assertIn("<- chosen", lines[self.first.url])
        self.assertIn(" ms", lines[self.second.url])
        self.assertNotIn("chosen", lines[self.second.url])
        code, out, err = self.run_cli("doctor", "--json")
        route = next(item for item in json.loads(out) if item["check"] == "phone route")
        self.assertTrue(route["ok"])
        self.assertIn("chosen " + self.first.url, route["detail"])
        # --url bypasses the candidates entirely.
        code, out, err = self.run_cli("--url", self.second.url, "ping", "--json")
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(json.loads(out)["base_url"], self.second.url)
        self.assertNotIn("paths", json.loads(out))

    def test_connection_error_invalidates_cache_and_retries_next_candidate(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / "note.txt").write_text("hello\n", encoding="utf-8")
            going = FakePhone(root).start()
            urls = [going.url, self.second.url]
            self.write_config(urls)
            cfg = config.load(self.cfg)
            client = PhoneClient(cfg.phone, cfg.data_dir)
            client.ping()
            self.assertEqual(client.url, going.url)
            going.close()
            # A new command reuses the cached path, finds it dead and moves on once.
            cfg = config.load(self.cfg)
            client = PhoneClient(cfg.phone, cfg.data_dir)
            self.assertEqual(client.stat("note.txt")["size"], 6)
            self.assertEqual(client.url, self.second.url)
            cache = json.loads((cfg.data_dir / paths.CACHE_NAME).read_text(encoding="utf-8"))
            self.assertEqual(cache["url"], self.second.url)

    def test_all_candidates_down_is_an_unreachable_error(self):
        self.write_config([_dead_url(), _dead_url()])
        cfg = config.load(self.cfg)
        with self.assertRaises(PhoneError) as caught:
            PhoneClient(cfg.phone, cfg.data_dir).ping()
        self.assertEqual(caught.exception.code, "unreachable")
        code, _out, err = self.run_cli("ls")
        self.assertEqual(code, 1)
        self.assertIn("no phone address is reachable", err)


if __name__ == "__main__":
    unittest.main()
