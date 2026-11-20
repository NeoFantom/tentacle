"""HTTP client for the tentacle API.

``PhoneClient`` instances are cheap and cache only their phone's ping response.
The caller supplies the central ``PhoneConfig``; this module never reads config
or environment variables directly.
"""
from __future__ import annotations

import http.client
import json
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import tzinfo
from pathlib import Path
from typing import Any, Callable, Iterable
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import __version__
from .paths import PathSelector, opener


class PhoneError(Exception):
    """A phone API, transport, or local client failure."""

    def __init__(self, code: str, message: str, status: int | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status

    def __str__(self) -> str:
        return self.message


def parse_pair(text: str, token: str | None = None) -> tuple[list[str], str, str]:
    """Parse a pairing string into ``(base_urls, token, name)``.

    Accepts the app's pairing URI (one or more ``url=`` fields, in preference
    order), the legacy ``tentacle://host:port?token=`` form, or an HTTP(S) base
    URL plus a separate token.
    """
    value = text.strip()
    try:
        parsed = urllib.parse.urlsplit(value)
        # Accessing .port performs urllib's port validation.
        port = parsed.port
    except ValueError as exc:
        raise ValueError("invalid pairing address") from exc

    if parsed.scheme == "tentacle" and parsed.hostname == "pair" and port is None:
        if parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.fragment:
            raise ValueError("invalid pair string")
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        if any(len(query.get(field, [])) > 1 for field in ("token", "name")):
            raise ValueError("pair string contains duplicate fields")
        pair_token = query.get("token", [""])[0]
        pair_urls = query.get("url", [])
        if not pair_token:
            raise ValueError("pair string has no token")
        if not pair_urls or not all(pair_urls):
            raise ValueError("pair string has no URL")
        bases: list[str] = []
        for pair_url in pair_urls:
            try:
                pair_url_scheme = urllib.parse.urlsplit(pair_url).scheme
            except ValueError as exc:
                raise ValueError("invalid phone URL") from exc
            if pair_url_scheme not in {"http", "https"}:
                raise ValueError("pair string URL must use http or https")
            base = parse_pair(pair_url, pair_token)[0][0]
            if base not in bases:
                bases.append(base)
        return bases, pair_token, query.get("name", [""])[0]

    if parsed.scheme == "tentacle":
        if not parsed.hostname or port is None or parsed.username or parsed.password:
            raise ValueError("pair string must contain host and port")
        if parsed.path not in ("", "/") or parsed.fragment:
            raise ValueError("invalid pair string")
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        pair_token = query.get("token", [""])[0]
        if not pair_token:
            raise ValueError("pair string has no token")
        name = query.get("name", [""])[0]
        return [urllib.parse.urlunsplit(("http", parsed.netloc, "", "", ""))], pair_token, name

    if parsed.scheme not in ("http", "https"):
        raise ValueError("pairing address must use tentacle, http, or https")
    if not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("phone URL must contain a host")
    if parsed.query or parsed.fragment:
        raise ValueError("phone URL must not contain a query or fragment")
    if not token:
        raise ValueError("a token is required with an HTTP URL")
    base = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))
    return [base], token, ""


def parse_pair_string(text: str, token: str | None = None) -> tuple[str, str, str]:
    """Parse a pairing string into ``(first_base_url, token, name)``."""
    urls, pair_token, name = parse_pair(text, token)
    return urls[0], pair_token, name


class PhoneClient:
    """Client for API v1. A client may be used by one thread at a time."""

    def __init__(self, phone_cfg: Any, cache_dir: Path | None = None):
        """``cache_dir`` (the data directory) keeps the chosen path between
        commands when ``phone_cfg.urls`` lists several candidates."""
        self.token = phone_cfg.resolved_token().strip()
        self.timeout = float(phone_cfg.timeout_s)
        self.find_timeout = float(getattr(phone_cfg, "find_timeout_s", 120))
        self._ping_cache: dict[str, Any] | None = None
        self.last_round_trip_ms: float | None = None
        self._use_system_proxy = bool(phone_cfg.use_system_proxy)
        self.paths = PathSelector(phone_cfg, cache_dir)
        self.url = ""
        if not self.paths.multiple:
            self._set_url(self.paths.urls[0] if self.paths.urls else "")

    def _set_url(self, url: str) -> None:
        self.url = url
        self._opener = opener(url, self._use_system_proxy)

    def select_path(self, *, refresh: bool = False, exclude: tuple[str, ...] = (),
                    wait_all: bool = False) -> None:
        """Probe the configured candidates and use the chosen one (``wait_all``:
        wait for every probe, for a complete path table)."""
        try:
            selection = self.paths.select(refresh=refresh, exclude=exclude, wait_all=wait_all)
        except LookupError as exc:
            raise PhoneError("unreachable", str(exc), None) from exc
        self._set_url(selection.url)

    def _ensure_paired(self) -> None:
        if not self.paths.urls or not self.token:
            raise PhoneError("not_paired", "phone URL and token are not configured", None)
        if not self.url:
            self.select_path()

    @staticmethod
    def _transport_error(exc: BaseException) -> PhoneError:
        reason = exc.reason if isinstance(exc, urllib.error.URLError) else exc
        if isinstance(reason, (TimeoutError, socket.timeout)):
            return PhoneError("timeout", "phone request timed out", None)
        return PhoneError("unreachable", f"phone is unreachable: {reason}", None)

    @staticmethod
    def _http_error(exc: urllib.error.HTTPError) -> PhoneError:
        code = "http_error"
        message = f"phone returned HTTP {exc.code}"
        try:
            payload = json.loads(exc.read().decode("utf-8"))
            if isinstance(payload, dict):
                code = str(payload.get("error") or code)
                message = str(payload.get("message") or payload.get("error") or message)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            pass
        return PhoneError(code, message, exc.code)

    def _open(
        self,
        endpoint: str,
        params: dict[str, Any] | None = None,
        *,
        method: str = "GET",
        headers: dict[str, str] | None = None,
        data: bytes | None = None,
        timeout: float | None = None,
        _retried: bool = False,
    ):
        self._ensure_paired()
        if endpoint != "ping" and self._ping_cache is None:
            self.ping()
        query = urllib.parse.urlencode(params or {}, doseq=True)
        url = f"{self.url}/v1/{endpoint}"
        if query:
            url += "?" + query
        request_headers = {
            "Authorization": f"Bearer {self.token}",
            "User-Agent": f"tentacle/{__version__}",
        }
        request_headers.update(headers or {})
        request = urllib.request.Request(url, headers=request_headers, data=data, method=method)
        try:
            return self._opener.open(request, timeout=self.timeout if timeout is None else timeout)
        except urllib.error.HTTPError as exc:
            raise self._http_error(exc) from exc
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
            error = self._transport_error(exc)
            if not self.paths.multiple or _retried:
                raise error from exc
        # The chosen path stopped working: forget it and retry once on another.
        failed = self.url
        self.paths.invalidate()
        try:
            self.select_path(exclude=(failed,))
        except PhoneError:
            raise error from None
        return self._open(
            endpoint,
            params,
            method=method,
            headers=headers,
            data=data,
            timeout=timeout,
            _retried=True,
        )

    def _json(
        self,
        endpoint: str,
        params: dict[str, Any] | None = None,
        *,
        method: str = "GET",
        body: dict[str, Any] | None = None,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        data = None
        headers = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers = {"Content-Type": "application/json; charset=utf-8"}
        try:
            with self._open(
                endpoint, params, method=method, headers=headers, data=data, timeout=timeout,
            ) as response:
                payload = json.load(response)
        except PhoneError:
            raise
        except (TimeoutError, socket.timeout) as exc:
            raise self._transport_error(exc) from exc
        except (http.client.HTTPException, OSError) as exc:
            raise self._transport_error(exc) from exc
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise PhoneError("bad_response", "phone returned invalid JSON", None) from exc
        if not isinstance(payload, dict):
            raise PhoneError("bad_response", "phone returned invalid JSON", None)
        return payload

    def ping(self) -> dict[str, Any]:
        if self._ping_cache is None:
            started = time.perf_counter()
            reply = self._json("ping")
            self.last_round_trip_ms = (time.perf_counter() - started) * 1000
            if reply.get("app") != "tentacle":
                raise PhoneError("wrong_app", "configured URL is not a Tentacle phone server", None)
            if reply.get("api") != 1:
                raise PhoneError("unsupported_api", "phone server does not support API v1", None)
            self._ping_cache = reply
        return self._ping_cache

    def phone_timezone(self) -> tzinfo | None:
        """Return the phone's reported zone, or ``None`` for per-instant local time."""
        name = self.ping().get("timezone")
        if isinstance(name, str) and name:
            try:
                return ZoneInfo(name)
            except (ZoneInfoNotFoundError, ValueError):
                pass
        return None

    def ls(self, path: str = "", hidden: bool = False) -> dict[str, Any]:
        return self._json("ls", {"path": path, "hidden": int(hidden)})

    def stat(self, path: str) -> dict[str, Any]:
        return self._json("stat", {"path": path})

    def requests(self, wait: int = 0) -> dict[str, Any]:
        """List app requests, optionally waiting for a new request."""
        wait_s = max(0, min(60, int(wait)))
        payload = self._json(
            "requests",
            {"wait": wait_s},
            timeout=max(self.timeout, wait_s + 5),
        )
        requests = payload.get("requests")
        if not isinstance(requests, list) or type(payload.get("now_ms")) is not int:
            raise PhoneError("bad_response", "phone returned an invalid requests response", None)
        required = {
            "id": str,
            "created_ms": int,
            "updated_ms": int,
            "status": str,
            "message": str,
            "has_log": bool,
        }
        for request in requests:
            if not isinstance(request, dict) or any(
                type(request.get(name)) is not expected for name, expected in required.items()
            ):
                raise PhoneError("bad_response", "phone returned an invalid request object", None)
        return payload

    def codes(self, since_ms: int | None = None, wait: int = 0) -> dict[str, Any]:
        """List verification codes received after *since_ms*.

        ``wait`` is bounded to the phone API's 60-second limit. Callers use a
        smaller value when they need time to handle their own deadline.
        """
        wait_s = max(0, min(60, int(wait)))
        payload = self._json(
            "codes",
            ({"wait": wait_s} if since_ms is None
             else {"since": int(since_ms), "wait": wait_s}),
            timeout=max(self.timeout, wait_s + 5),
        )
        codes = payload.get("codes")
        if (not isinstance(codes, list) or type(payload.get("now_ms")) is not int
                or type(payload.get("enabled")) is not bool):
            raise PhoneError("bad_response", "phone returned an invalid codes response", None)
        required = {
            "id": str,
            "code": str,
            "sender": str,
            "source": str,
            "snippet": str,
            "received_ms": int,
        }
        for code in codes:
            if (not isinstance(code, dict) or any(
                    type(code.get(name)) is not expected
                    for name, expected in required.items()
                ) or code["source"] not in {"notification", "sms"}):
                raise PhoneError("bad_response", "phone returned an invalid code object", None)
        return payload

    def send_notification(
        self,
        text: str,
        *,
        notification_id: str,
        title: str | None = None,
        urgent: bool = False,
    ) -> dict[str, Any]:
        """Ask the phone to show a notification.

        The caller supplies the ID so retrying a request after a transport
        failure remains idempotent.
        """
        body: dict[str, Any] = {
            "text": text,
            "level": "urgent" if urgent else "normal",
            "id": notification_id,
        }
        if title is not None:
            body["title"] = title
        payload = self._json("notify", method="POST", body=body)
        required = {"id": str, "level": str, "downgraded": bool, "shown": bool}
        if (any(type(payload.get(name)) is not expected for name, expected in required.items())
                or payload["id"] != notification_id
                or payload["level"] not in {"normal", "urgent"}
                or ("reason" in payload and type(payload["reason"]) is not str)):
            raise PhoneError("bad_response", "phone returned an invalid notify response", None)
        return payload

    def notification(self, notification_id: str, wait: int = 0) -> dict[str, Any]:
        """Get notification state, optionally waiting for acknowledgement or dismissal."""
        wait_s = max(0, min(60, int(wait)))
        endpoint = "notify/" + urllib.parse.quote(notification_id, safe="")
        payload = self._json(
            endpoint,
            {"wait": wait_s},
            timeout=max(self.timeout, wait_s + 5),
        )
        required = {"id": str, "level": str, "state": str}
        if (any(type(payload.get(name)) is not expected for name, expected in required.items())
                or payload["id"] != notification_id
                or payload["level"] not in {"normal", "urgent"}
                or payload["state"] not in {"shown", "acknowledged", "dismissed", "expired"}
                or ("acknowledged_ms" in payload
                    and type(payload["acknowledged_ms"]) is not int)):
            raise PhoneError("bad_response", "phone returned an invalid notification response", None)
        return payload

    def update_request(self, request_id: str, status: str, message: str) -> dict[str, Any]:
        """Update one request's status and phone-visible message."""
        endpoint = "requests/" + urllib.parse.quote(request_id, safe="")
        return self._json(
            endpoint,
            method="POST",
            body={"status": status, "message": message},
        )

    def request_log(self, request_id: str) -> bytes:
        """Download the log snapshot attached to a bug request."""
        endpoint = "requests/" + urllib.parse.quote(request_id, safe="") + "/log"
        try:
            with self._open(endpoint) as response:
                return response.read()
        except PhoneError:
            raise
        except (http.client.HTTPException, OSError) as exc:
            raise self._transport_error(exc) from exc

    def find(
        self,
        root: str | None = None,
        ext: str | Iterable[str] | None = None,
        name: str | None = None,
        since_ms: int | None = None,
        until_ms: int | None = None,
        type: str = "file",
        max_depth: int | None = None,
        limit: int | None = None,
        sort: str | None = None,
        hidden: bool = False,
    ) -> dict[str, Any]:
        if ext is not None and not isinstance(ext, str):
            ext = ",".join(str(item).lstrip(".") for item in ext)
        params = {
            "root": root,
            "ext": ext,
            "name": name,
            "since_ms": since_ms,
            "until_ms": until_ms,
            "type": type,
            "max_depth": max_depth,
            "limit": limit,
            "sort": sort,
            "hidden": int(hidden),
        }
        return self._json(
            "find",
            {key: value for key, value in params.items() if value is not None},
            timeout=self.find_timeout,
        )

    def read_bytes(self, path: str, limit: int) -> bytes:
        if limit < 0:
            raise ValueError("limit must be non-negative")
        self._ensure_paired()
        if limit == 0:
            return b""
        try:
            # A plain GET also handles empty files; reading and then closing the
            # response remains bounded even when the remote file is large.
            with self._open("file", {"path": path}) as response:
                return response.read(limit)
        except PhoneError:
            raise
        except (http.client.HTTPException, OSError) as exc:
            raise self._transport_error(exc) from exc

    def download(
        self,
        path: str,
        dest: str | Path,
        resume: bool = True,
        expected_size: int | None = None,
        progress: Callable[[int, int | None], None] | None = None,
    ) -> Path:
        destination = Path(dest)
        part = Path(str(destination) + ".part")
        etag_path = Path(str(part) + ".etag")
        destination.parent.mkdir(parents=True, exist_ok=True)

        with self._open("file", {"path": path}, method="HEAD") as head:
            remote_etag = head.headers.get("ETag", "")
            remote_size_text = head.headers.get("Content-Length")
        if remote_size_text is not None and re.fullmatch(r"\d+", remote_size_text):
            remote_size = int(remote_size_text)
        else:
            remote_size = None
        if expected_size is not None and expected_size < 0:
            raise ValueError("expected_size must be non-negative")
        if remote_size is None and expected_size is None:
            raise PhoneError("bad_response", "phone returned an invalid Content-Length", None)
        final_size = expected_size if expected_size is not None else remote_size
        assert final_size is not None
        if expected_size is not None and remote_size is not None and expected_size != remote_size:
            raise PhoneError(
                "size_mismatch",
                f"phone file size is {remote_size} bytes; expected {expected_size}",
                None,
            )

        offset = 0
        if resume and part.is_file() and remote_etag and etag_path.is_file():
            try:
                saved_etag = etag_path.read_text(encoding="ascii").strip()
            except (OSError, UnicodeDecodeError):
                saved_etag = ""
            if saved_etag == remote_etag:
                offset = part.stat().st_size
                if offset > final_size:
                    offset = 0

        if not resume or offset == 0:
            # Opening in wb below discards any stale partial content.
            offset = 0
        elif offset == final_size:
            destination.parent.mkdir(parents=True, exist_ok=True)
            part.replace(destination)
            etag_path.unlink(missing_ok=True)
            if progress:
                progress(offset, final_size)
            return destination

        headers: dict[str, str] = {}
        if offset:
            headers = {"Range": f"bytes={offset}-", "If-Range": remote_etag}
        response = self._open("file", {"path": path}, headers=headers)
        try:
            if offset:
                content_range = response.headers.get("Content-Range", "")
                range_match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", content_range)
                valid_range = False
                if range_match:
                    range_start, range_end, range_total = map(int, range_match.groups())
                    valid_range = (
                        range_start == offset
                        and range_end == final_size - 1
                        and range_total == final_size
                    )
                response_etag = response.headers.get("ETag", "")
                if response.status != 206 or not valid_range or response_etag != remote_etag:
                    response.close()
                    offset = 0
                    response = self._open("file", {"path": path})

            response_etag = response.headers.get("ETag", "")

            active_etag = response_etag or remote_etag
            if active_etag:
                etag_path.write_text(active_etag, encoding="ascii")
            else:
                etag_path.unlink(missing_ok=True)
            mode = "ab" if offset else "wb"
            transferred = offset
            with part.open(mode) as output:
                while True:
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
                    transferred += len(chunk)
                    if progress:
                        progress(transferred, final_size)
        except PhoneError:
            raise
        except (http.client.HTTPException, OSError) as exc:
            raise self._transport_error(exc) from exc
        finally:
            response.close()

        actual_size = part.stat().st_size
        if final_size is not None and actual_size != final_size:
            raise PhoneError(
                "size_mismatch",
                f"downloaded {actual_size} bytes; expected {final_size}",
                None,
            )
        part.replace(destination)
        etag_path.unlink(missing_ok=True)
        return destination
