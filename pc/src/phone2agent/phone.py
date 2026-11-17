"""HTTP client for the read-only phone2agent API.

``PhoneClient`` instances are cheap and contain no shared mutable state.  The
caller supplies the central ``PhoneConfig``; this module never reads config or
environment variables directly.
"""
from __future__ import annotations

import http.client
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable, Iterable


class PhoneError(Exception):
    """A phone API, transport, or local client failure."""

    def __init__(self, code: str, message: str, status: int | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status

    def __str__(self) -> str:
        return self.message


def parse_pair_string(text: str, token: str | None = None) -> tuple[str, str, str]:
    """Parse an app pairing URI, or an HTTP base URL plus a separate token."""
    value = text.strip()
    try:
        parsed = urllib.parse.urlsplit(value)
        # Accessing .port performs urllib's port validation.
        port = parsed.port
    except ValueError as exc:
        raise ValueError("invalid pairing address") from exc

    if parsed.scheme == "p2a":
        if not parsed.hostname or port is None or parsed.username or parsed.password:
            raise ValueError("pair string must contain host and port")
        if parsed.path not in ("", "/") or parsed.fragment:
            raise ValueError("invalid pair string")
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        pair_token = query.get("token", [""])[0]
        if not pair_token:
            raise ValueError("pair string has no token")
        name = query.get("name", [""])[0]
        return urllib.parse.urlunsplit(("http", parsed.netloc, "", "", "")), pair_token, name

    if parsed.scheme not in ("http", "https"):
        raise ValueError("pairing address must use p2a, http, or https")
    if not parsed.hostname or port is None or parsed.username or parsed.password:
        raise ValueError("phone URL must contain host and port")
    if parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise ValueError("phone URL must be a base URL")
    if not token:
        raise ValueError("a token is required with an HTTP URL")
    base = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))
    return base, token, ""


class PhoneClient:
    """Client for API v1. A client may be used by one thread at a time."""

    def __init__(self, phone_cfg: Any):
        self.url = str(phone_cfg.url).strip().rstrip("/")
        self.token = phone_cfg.resolved_token().strip()
        self.timeout = float(phone_cfg.timeout_s)
        if phone_cfg.use_system_proxy:
            self._opener = urllib.request.build_opener()
        else:
            self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _ensure_paired(self) -> None:
        if not self.url or not self.token:
            raise PhoneError("not_paired", "phone URL and token are not configured", None)

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
    ):
        self._ensure_paired()
        query = urllib.parse.urlencode(params or {}, doseq=True)
        url = f"{self.url}/v1/{endpoint}"
        if query:
            url += "?" + query
        request_headers = {"Authorization": f"Bearer {self.token}"}
        request_headers.update(headers or {})
        request = urllib.request.Request(url, headers=request_headers, method=method)
        try:
            return self._opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as exc:
            raise self._http_error(exc) from exc
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
            raise self._transport_error(exc) from exc

    def _json(self, endpoint: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            with self._open(endpoint, params) as response:
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
        return self._json("ping")

    def ls(self, path: str = "", hidden: bool = False) -> dict[str, Any]:
        return self._json("ls", {"path": path, "hidden": int(hidden)})

    def stat(self, path: str) -> dict[str, Any]:
        return self._json("stat", {"path": path})

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
        return self._json("find", {key: value for key, value in params.items() if value is not None})

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
