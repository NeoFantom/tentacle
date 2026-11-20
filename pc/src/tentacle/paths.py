"""Choose the fastest working base URL among the phone's ordered candidates.

The pairing link can carry several base URLs (tailnet, Wi-Fi, other LAN,
public URL).  Every candidate speaks the same phone API; this module probes
them with a short ``GET /v1/ping`` and picks the first one that works, in
order, except that a reachable LAN address always wins: the phone reports its
current Wi-Fi/Ethernet base URLs in ``/v1/ping`` (``lan``), the computer
remembers them and probes them too, so being on the same network as the phone
is detected automatically even after the phone's address changed.  A tailnet
candidate (100.64.0.0/10) counts only while Tailscale uses a
direct path to it: when the local ``tailscale`` CLI reports the peer as
relayed and a later non-tailnet candidate works, the later one wins.  The
winner is cached for a short time in the data directory so consecutive
commands do not probe again.

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
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable

from . import __version__

PROBE_TIMEOUT_S = 2.5
CACHE_TTL_S = 60.0
CACHE_NAME = "path-cache.json"
TAILNET = ipaddress.ip_network("100.64.0.0/10")


@dataclass
class PathResult:
    """Outcome of probing one candidate.

    ``status`` is ``ok`` (reachable; not a tailnet address or path unknown),
    ``direct`` / ``relayed`` (reachable tailnet address with the path reported
    by the local Tailscale client) or ``unreachable``.
    """

    url: str
    status: str
    rtt_ms: float | None = None
    detail: str = ""
    lan: list[str] | None = field(default=None, repr=False)  # None: phone did not report

    @property
    def reachable(self) -> bool:
        return self.status != "unreachable"

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

    def select(self, *, refresh: bool = False, exclude: Iterable[str] = ()) -> Selection:
        """Return the chosen URL. Raises ``LookupError`` if none is reachable."""
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
        learned = self.learned_lan()
        pending = [url for url in learned + self.urls if url not in excluded]
        pending = list(dict.fromkeys(pending))
        results = self._probe_all(pending)
        # The phone tells us its current LAN addresses; try any we have not tried yet.
        reports = [r.lan for r in results if r.reachable and r.lan is not None]
        reported = list(dict.fromkeys(u for lan in reports for u in lan))
        extra = [u for u in reported if u not in pending and u not in excluded]
        if extra:
            results += self._probe_all(extra)
        if reports:
            learned = reported
        reachable_tailnet = [r for r in results if r.reachable and is_tailnet(r.url)]
        if reachable_tailnet:
            status = self._status()
            for result in reachable_tailnet:
                path = peer_path(status, urllib.parse.urlsplit(result.url).hostname or "")
                if path:
                    result.status = path
        chosen = next((r for r in results if r.reachable and is_lan(r.url)), None)
        if chosen is None:
            chosen = next((r for r in results if r.status in {"ok", "direct"}), None)
        if chosen is None:
            chosen = next((r for r in results if r.reachable), None)
        self.last = Selection(chosen.url if chosen else "", results)
        if chosen is None:
            details = "; ".join(f"{r.url}: {r.detail or r.status}" for r in results)
            raise LookupError(f"no phone address is reachable ({details})")
        self._remember(chosen.url, learned)
        return self.last

    def _probe_all(self, urls: list[str]) -> list[PathResult]:
        if not urls:
            return []
        with ThreadPoolExecutor(max_workers=len(urls)) as pool:
            return list(pool.map(self._probe, urls))
