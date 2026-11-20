"""Choose the fastest working base URL among the phone's ordered candidates.

The pairing link can carry several base URLs (tailnet, Wi-Fi, other LAN,
public URL).  Every candidate speaks the same phone API; this module races
short ``GET /v1/ping`` probes against all of them at once and decides as soon
as the answer cannot improve.  A reachable LAN address always wins and is
taken the moment it answers: the phone reports its
current Wi-Fi/Ethernet base URLs in ``/v1/ping`` (``lan``), the computer
remembers them and probes them too, so being on the same network as the phone
is detected automatically even after the phone's address changed.  A tailnet
candidate (100.64.0.0/10) counts only while Tailscale uses a
direct path to it: when the local ``tailscale`` CLI reports the peer as
relayed and another candidate works, that one wins.  Once the first good
candidate answers, the race waits only a short grace period for a LAN answer;
slow or dead candidates never delay the choice (their probes finish in the
background).  The winner is cached for a short time in the data directory so
consecutive commands do not probe again.

Standard library only.  The caller supplies the configuration values; this
module never reads configuration files or environment variables.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import shutil
import socket
import queue
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable

from . import __version__

PROBE_TIMEOUT_S = 2.5
# After the first good (non-LAN) answer, how long a LAN answer may still win.
LAN_GRACE_S = 0.25
STATUS_TIMEOUT_S = 3.5
REACHABLE = frozenset({"ok", "direct", "relayed"})
CACHE_TTL_S = 60.0
CACHE_NAME = "path-cache.json"
TAILNET = ipaddress.ip_network("100.64.0.0/10")


@dataclass
class PathResult:
    """Outcome of probing one candidate.

    ``status`` is ``ok`` (reachable; not a tailnet address or path unknown),
    ``direct`` / ``relayed`` (reachable tailnet address with the path reported
    by the local Tailscale client), ``unreachable``, or ``pending`` (the race
    was decided before this probe answered).
    """

    url: str
    status: str
    rtt_ms: float | None = None
    detail: str = ""
    lan: list[str] | None = field(default=None, repr=False)  # None: phone did not report

    @property
    def reachable(self) -> bool:
        return self.status in REACHABLE

    def to_json(self) -> dict[str, Any]:
        return {"url": self.url, "status": self.status, "rtt_ms": self.rtt_ms, "detail": self.detail}


@dataclass
class Selection:
    url: str
    results: list[PathResult] = field(default_factory=list)
    cached: bool = False

    def to_json(self) -> list[dict[str, Any]]:
        return [dict(result.to_json(), chosen=result.url == self.url) for result in self.results]


def candidates(phone_cfg: Any) -> list[str]:
    """Ordered, de-duplicated base URLs: ``phone.urls`` or else ``phone.url``."""
    raw = list(getattr(phone_cfg, "urls", None) or []) or [getattr(phone_cfg, "url", "")]
    seen: list[str] = []
    for url in raw:
        value = str(url).strip().rstrip("/")
        if value and value not in seen:
            seen.append(value)
    return seen


def _host_ip(url: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        host = urllib.parse.urlsplit(url).hostname or ""
        return ipaddress.ip_address(host)
    except ValueError:
        return None


def is_tailnet(url: str) -> bool:
    ip = _host_ip(url)
    return isinstance(ip, ipaddress.IPv4Address) and ip in TAILNET


def is_local(url: str) -> bool:
    """Loopback, LAN, link-local and tailnet hosts never go through a proxy."""
    ip = _host_ip(url)
    if ip is None:
        host = (urllib.parse.urlsplit(url).hostname or "").lower()
        return host == "localhost" or host.endswith((".local", ".ts.net"))
    return ip.is_loopback or ip.is_private or ip.is_link_local or (
        isinstance(ip, ipaddress.IPv4Address) and ip in TAILNET)


def is_lan(url: str) -> bool:
    """A private (RFC 1918 / ULA) or link-local address that is not a tailnet one."""
    ip = _host_ip(url)
    if ip is None or ip.is_loopback:
        return False
    if isinstance(ip, ipaddress.IPv4Address) and ip in TAILNET:
        return False
    return ip.is_private or ip.is_link_local


def _lan_urls(payload: dict[str, Any]) -> list[str] | None:
    raw = payload.get("lan")
    if not isinstance(raw, list):
        return None
    return [str(u).strip().rstrip("/") for u in raw
            if isinstance(u, str) and u.startswith("http://") and is_lan(u.strip())]


def opener(url: str, use_system_proxy: bool) -> urllib.request.OpenerDirector:
    if use_system_proxy and not is_local(url):
        return urllib.request.build_opener()
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def probe(url: str, token: str, use_system_proxy: bool = False,
          timeout: float = PROBE_TIMEOUT_S) -> PathResult:
    """One ``GET /v1/ping``; reachable only if a Tentacle API v1 answers."""
    request = urllib.request.Request(f"{url}/v1/ping", headers={
        "Authorization": f"Bearer {token}", "User-Agent": f"tentacle/{__version__}"})
    started = time.perf_counter()
    try:
        with opener(url, use_system_proxy).open(request, timeout=timeout) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        exc.close()
        return PathResult(url, "unreachable", detail=f"HTTP {exc.code}")
    except (urllib.error.URLError, OSError, ValueError) as exc:
        reason = getattr(exc, "reason", exc)
        if isinstance(reason, (TimeoutError, socket.timeout)):
            reason = "timed out"
        return PathResult(url, "unreachable", detail=str(reason))
    rtt = round((time.perf_counter() - started) * 1000, 1)
    if not isinstance(payload, dict) or payload.get("app") != "tentacle" or payload.get("api") != 1:
        return PathResult(url, "unreachable", rtt, "not a Tentacle API v1 server")
    return PathResult(url, "ok", rtt, lan=_lan_urls(payload))


def tailscale_status(run: Callable[..., Any] = subprocess.run) -> dict[str, Any] | None:
    """``tailscale status --json`` of the local client, or None if unavailable."""
    executable = shutil.which("tailscale")
    if executable is None:
        return None
    try:
        completed = run([executable, "status", "--json"], capture_output=True, text=True,
                        timeout=3, check=False)
        if completed.returncode != 0:
            return None
        status = json.loads(completed.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        return None
    return status if isinstance(status, dict) else None


def peer_path(status: dict[str, Any] | None, ip: str) -> str | None:
    """``direct`` / ``relayed`` for the peer owning ``ip``; None if unknown."""
    if not status:
        return None
    peers = status.get("Peer") or {}
    if not isinstance(peers, dict):
        return None
    for peer in peers.values():
        if not isinstance(peer, dict) or ip not in (peer.get("TailscaleIPs") or []):
            continue
        if peer.get("CurAddr"):
            return "direct"
        if peer.get("Relay") or peer.get("PeerRelay"):
            return "relayed"
        return None
    return None


def _cache_key(urls: Iterable[str]) -> str:
    return hashlib.sha256("\n".join(urls).encode("utf-8")).hexdigest()[:16]


class PathSelector:
    """Probe, choose and cache one base URL among ``candidates(phone_cfg)``."""

    def __init__(
        self,
        phone_cfg: Any,
        cache_dir: Path | None = None,
        *,
        prober: Callable[[str], PathResult] | None = None,
        status: Callable[[], dict[str, Any] | None] = tailscale_status,
        clock: Callable[[], float] = time.time,
        ttl_s: float = CACHE_TTL_S,
    ):
        self.urls = candidates(phone_cfg)
        self.cache_path = Path(cache_dir) / CACHE_NAME if cache_dir is not None else None
        token = phone_cfg.resolved_token().strip()
        use_proxy = bool(getattr(phone_cfg, "use_system_proxy", False))
        self._probe = prober or (lambda url: probe(url, token, use_proxy))
        self._status = status
        self._clock = clock
        self.ttl_s = ttl_s
        self.last: Selection | None = None

    @property
    def multiple(self) -> bool:
        return len(self.urls) > 1

    def _load(self) -> dict[str, Any]:
        if self.cache_path is None:
            return {}
        try:
            data = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        if not isinstance(data, dict) or data.get("key") != _cache_key(self.urls):
            return {}
        return data

    def learned_lan(self) -> list[str]:
        """LAN base URLs the phone reported last time (kept until replaced)."""
        raw = self._load().get("lan")
        return [u for u in raw if isinstance(u, str) and is_lan(u)] if isinstance(raw, list) else []

    def cached(self) -> str | None:
        data = self._load()
        try:
            url, saved = data["url"], float(data["time"])
        except (KeyError, TypeError, ValueError):
            return None
        age = self._clock() - saved
        if not url or (url not in self.urls and url not in self.learned_lan()) \
                or not 0 <= age < self.ttl_s:
            return None
        return url

    def _remember(self, url: str, lan: list[str] | None = None) -> None:
        if self.cache_path is None:
            return
        lan = self.learned_lan() if lan is None else lan
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.cache_path.with_suffix(".tmp")
            temporary.write_text(json.dumps({"url": url, "time": self._clock(),
                                             "key": _cache_key(self.urls), "lan": lan}),
                                 encoding="utf-8")
            os.replace(temporary, self.cache_path)
        except OSError:
            pass  # The cache is only an optimisation.

    def invalidate(self) -> None:
        """Forget the chosen URL but keep the learned LAN addresses."""
        if self.cache_path is not None and self.cache_path.exists():
            self._remember("", self.learned_lan())

    def select(self, *, refresh: bool = False, exclude: Iterable[str] = (),
               wait_all: bool = False) -> Selection:
        """Return the chosen URL. Raises ``LookupError`` if none is reachable.

        ``wait_all`` waits for every probe so the results form a full table
        (``tentacle ping``/``doctor``); otherwise the race stops early.
        """
        excluded = set(exclude)
        if not self.urls:
            raise LookupError("phone URL is not configured")
        if len(self.urls) == 1 and not refresh and (is_lan(self.urls[0]) or not self.cache_path):
            # Nothing better to find; keep single-URL behaviour without an extra request.
            self.last = Selection(self.urls[0])
            return self.last
        if not refresh and not excluded:
            url = self.cached()
            if url is not None:
                self.last = Selection(url, cached=True)
                return self.last
        pending = [url for url in self.learned_lan() + self.urls if url not in excluded]
        race = _Race(self._probe, self._status, excluded, wait_all)
        chosen, results, reports = race.run(list(dict.fromkeys(pending)))
        self.last = Selection(chosen.url if chosen else "", results)
        if chosen is None:
            details = "; ".join(f"{r.url}: {r.detail or r.status}" for r in results)
            raise LookupError(f"no phone address is reachable ({details})")
        # The phone tells us its current LAN addresses; remember them for next time.
        learned = list(dict.fromkeys(u for lan in reports for u in lan)) if reports else None
        self._remember(chosen.url, learned)
        return self.last


class _Race:
    """Probe candidates concurrently and pick one as soon as waiting cannot help.

    Order of preference: a reachable LAN address (chosen the moment it
    answers); otherwise the first good candidate in list order (non-tailnet
    ``ok`` or tailnet ``direct``/``ok``), chosen once a short grace for LAN
    answers has passed; a relayed tailnet path only when nothing else works.
    Probes run on daemon threads, so unfinished ones never block the caller
    or interpreter exit.
    """

    def __init__(self, probe: Callable[[str], PathResult],
                 status: Callable[[], dict[str, Any] | None],
                 excluded: set[str], wait_all: bool):
        self._probe = probe
        self._status_call = status
        self._excluded = excluded
        self._wait_all = wait_all
        self._events: queue.Queue = queue.Queue()
        self._order: list[str] = []
        self._results: dict[str, PathResult] = {}
        self._outstanding = 0
        self._deadline = 0.0
        self._status_state = "none"  # none | waiting | done
        self._status: dict[str, Any] | None = None

    def _launch(self, url: str) -> None:
        self._order.append(url)
        self._outstanding += 1
        self._deadline = max(self._deadline, time.monotonic() + PROBE_TIMEOUT_S + 0.5)

        def work() -> None:
            try:
                result = self._probe(url)
            except Exception as exc:  # noqa: BLE001 - a broken probe is an unreachable path
                result = PathResult(url, "unreachable", detail=str(exc))
            self._events.put(("probe", result))
        threading.Thread(target=work, name="tentacle-probe", daemon=True).start()

    def _launch_status(self) -> None:
        self._status_state = "waiting"
        self._status_deadline = time.monotonic() + STATUS_TIMEOUT_S

        def work() -> None:
            try:
                value = self._status_call()
            except Exception:  # noqa: BLE001
                value = None
            self._events.put(("status", value))
        threading.Thread(target=work, name="tentacle-tailscale-status", daemon=True).start()

    def _classify(self, result: PathResult) -> None:
        if result.reachable and is_tailnet(result.url) and result.status == "ok":
            path = peer_path(self._status, urllib.parse.urlsplit(result.url).hostname or "")
            if path:
                result.status = path

    def _awaiting_status(self, result: PathResult) -> bool:
        return result.reachable and is_tailnet(result.url) and self._status_state == "waiting"

    def run(self, urls: list[str]) -> tuple[PathResult | None, list[PathResult], list[list[str]]]:
        for url in urls:
            self._launch(url)
        if any(is_tailnet(u) for u in urls):
            # Ask the local Tailscale client in parallel with the probes.
            self._launch_status()
        reports: list[list[str]] = []
        grace_until: float | None = None
        while True:
            chosen = self._decide(grace_until)
            if chosen is not None:
                break
            done = self._outstanding == 0 and self._status_state != "waiting"
            now = time.monotonic()
            if done or now >= self._deadline:
                break
            waits = [self._deadline - now]
            if grace_until is not None:
                waits.append(grace_until - now)
            if self._status_state == "waiting":
                waits.append(self._status_deadline - now)
            try:
                kind, value = self._events.get(timeout=max(0.0, min(waits)))
            except queue.Empty:
                if self._status_state == "waiting" and time.monotonic() >= self._status_deadline:
                    self._status_state = "done"
                    for result in self._results.values():
                        self._classify(result)
                continue
            if kind == "status":
                self._status_state, self._status = "done", value
                for result in self._results.values():
                    self._classify(result)
            else:
                self._outstanding -= 1
                result: PathResult = value
                self._results[result.url] = result
                if self._status_state == "done":
                    self._classify(result)
                if result.reachable and result.lan is not None:
                    reports.append(result.lan)
                    # A newly reported LAN address joins the race right away.
                    for url in result.lan:
                        if url not in self._order and url not in self._excluded:
                            self._launch(url)
            if grace_until is None and any(self._good(r) for r in self._results.values()):
                grace_until = time.monotonic() + LAN_GRACE_S
        final = chosen or self._final()
        results = [self._results.get(url) or PathResult(url, "pending", detail="not waited for")
                   for url in self._order]
        return final, results, reports

    def _good(self, result: PathResult) -> bool:
        return result.status in {"ok", "direct"} and not self._awaiting_status(result)

    def _decide(self, grace_until: float | None) -> PathResult | None:
        if self._wait_all:
            return None
        for url in self._order:
            result = self._results.get(url)
            if result is not None and result.reachable and is_lan(url):
                return result
        if grace_until is not None and time.monotonic() >= grace_until:
            return self._first(self._good)
        return None

    def _first(self, predicate: Callable[[PathResult], bool]) -> PathResult | None:
        for url in self._order:
            result = self._results.get(url)
            if result is not None and predicate(result):
                return result
        return None

    def _final(self) -> PathResult | None:
        return (self._first(lambda r: r.reachable and is_lan(r.url))
                or self._first(lambda r: r.status in {"ok", "direct"})
                or self._first(lambda r: r.reachable))
