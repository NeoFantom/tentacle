"""A small, faithful in-process implementation of the phone HTTP API v1.

The fake server deliberately exposes a caller-provided temporary directory as
the primary storage root.  It is shared test infrastructure: client, sync, and
pipeline tests can exercise the real HTTP protocol without network access or
Android-specific paths.
"""
from __future__ import annotations

import email.utils
import hmac
import json
import mimetypes
import os
import re
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


_ANDROID_PRIMARY = Path("/storage/emulated/0")
_RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)\Z")


class _ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self, address, root: Path, token: str, timezone: str, clock_offset_s: float,
    ):
        self.root = root.resolve(strict=True)
        self.token = token
        self.timezone = timezone
        self.clock_offset_s = clock_offset_s
        super().__init__(address, _Handler)


class _Handler(BaseHTTPRequestHandler):
    server: _Server
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *args) -> None:
        pass

    def do_GET(self) -> None:
        self._at_request_boundary(self._dispatch, head=False)

    def do_HEAD(self) -> None:
        self._at_request_boundary(self._dispatch, head=True)

    def do_POST(self) -> None:
        self._at_request_boundary(self._method_not_allowed)

    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_POST
    do_OPTIONS = do_POST
    do_TRACE = do_POST
    do_CONNECT = do_POST

    def _at_request_boundary(self, action, **kwargs) -> None:
        try:
            action(**kwargs)
        except (BrokenPipeError, ConnectionResetError):
            # Bounded reads intentionally close the response before the fake
            # server has finished writing a potentially large file.  That is
            # a normal client action, not an API failure to report or log.
            self.close_connection = True

    def _method_not_allowed(self) -> None:
        if not self._authorized():
            self._send_error(HTTPStatus.UNAUTHORIZED, "unauthorized", "Invalid bearer token")
            return
        self._send_error(
            HTTPStatus.METHOD_NOT_ALLOWED,
            "method_not_allowed",
            "Only GET and file HEAD requests are supported",
            headers={"Allow": "GET, HEAD"},
        )

    def _dispatch(self, *, head: bool) -> None:
        try:
            if not self._authorized():
                raise _ApiError(401, "unauthorized", "Invalid bearer token")
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query, keep_blank_values=True)
            if head and parsed.path != "/v1/file":
                raise _ApiError(405, "method_not_allowed", "HEAD is only supported for /v1/file")
            if parsed.path == "/v1/ping":
                self._require_no_unknown(query, set())
                self._ping()
            elif parsed.path == "/v1/ls":
                self._ls(query)
            elif parsed.path == "/v1/stat":
                self._stat(query)
            elif parsed.path == "/v1/find":
                self._find(query)
            elif parsed.path == "/v1/file":
                self._file(query, head=head)
            else:
                raise _ApiError(404, "not_found", "Endpoint not found")
        except _ApiError as exc:
            self._send_error(exc.status, exc.code, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            raise
        except (OSError, ValueError) as exc:
            self._send_error(500, "internal", str(exc) or "Internal server error")

    def _authorized(self) -> bool:
        supplied = self.headers.get("Authorization", "")
        expected = f"Bearer {self.server.token}"
        return hmac.compare_digest(supplied.encode(), expected.encode())

    @staticmethod
    def _one(query: dict[str, list[str]], name: str, default: str | None = None) -> str | None:
        values = query.get(name)
        if not values:
            return default
        if len(values) != 1:
            raise _ApiError(400, "bad_request", f"Query parameter {name} must appear once")
        return values[0]

    @staticmethod
    def _require_no_unknown(query: dict[str, list[str]], allowed: set[str]) -> None:
        unknown = set(query) - allowed
        if unknown:
            raise _ApiError(400, "bad_request", f"Unknown query parameter: {sorted(unknown)[0]}")

    @classmethod
    def _bool(cls, query: dict[str, list[str]], name: str, default: bool = False) -> bool:
        value = cls._one(query, name, "1" if default else "0")
        if value not in {"0", "1"}:
            raise _ApiError(400, "bad_request", f"{name} must be 0 or 1")
        return value == "1"

    @classmethod
    def _int(
        cls,
        query: dict[str, list[str]],
        name: str,
        default: int | None,
        *,
        minimum: int | None = None,
        maximum: int | None = None,
    ) -> int | None:
        raw = cls._one(query, name, None)
        if raw is None or raw == "":
            return default
        try:
            value = int(raw)
        except ValueError:
            raise _ApiError(400, "bad_request", f"{name} must be an integer") from None
        if minimum is not None and value < minimum:
            raise _ApiError(400, "bad_request", f"{name} must be at least {minimum}")
        if maximum is not None and value > maximum:
            raise _ApiError(400, "bad_request", f"{name} must be at most {maximum}")
        return value

    def _resolve(self, text: str | None) -> Path:
        value = text or ""
        if value in {"", "/", "."}:
            lexical = self.server.root
        else:
            supplied = Path(value)
            if supplied == _ANDROID_PRIMARY:
                lexical = self.server.root
            elif supplied.is_relative_to(_ANDROID_PRIMARY):
                lexical = self.server.root.joinpath(*supplied.relative_to(_ANDROID_PRIMARY).parts)
            elif supplied.is_absolute():
                lexical = supplied
            else:
                lexical = self.server.root / supplied

        # Deny these paths before resolution too, so a symlink located below a
        # protected Android directory cannot escape the policy check.
        self._deny_android_path(lexical)
        try:
            resolved = lexical.resolve(strict=False)
        except (OSError, RuntimeError):
            raise _ApiError(403, "forbidden", "Path cannot be resolved") from None
        if not resolved.is_relative_to(self.server.root):
            raise _ApiError(403, "forbidden", "Path is outside the storage root")
        self._deny_android_path(resolved)
        return resolved

    def _deny_android_path(self, path: Path) -> None:
        try:
            relative = path.relative_to(self.server.root)
        except ValueError:
            return
        parts = [part.casefold() for part in relative.parts]
        if len(parts) >= 2 and parts[0] == "android" and parts[1] in {"data", "obb"}:
            raise _ApiError(403, "forbidden", "Android app-private storage is not readable")

    @staticmethod
    def _kind(path: Path) -> str:
        if path.is_file():
            return "file"
        if path.is_dir():
            return "dir"
        return "other"

    def _entry(self, path: Path) -> dict[str, object]:
        resolved = path.resolve(strict=True)
        if not resolved.is_relative_to(self.server.root):
            raise _ApiError(403, "forbidden", "Path is outside the storage root")
        self._deny_android_path(resolved)
        stat = resolved.stat()
        kind = self._kind(resolved)
        return {
            "name": resolved.name or self.server.root.name,
            "path": str(resolved),
            "type": kind,
            "size": stat.st_size if kind == "file" else 0,
            "mtime_ms": stat.st_mtime_ns // 1_000_000,
        }

    def _existing(self, text: str | None, *, directory: bool = False, file: bool = False) -> Path:
        path = self._resolve(text)
        if not path.exists():
            raise _ApiError(404, "not_found", "Path not found")
        if directory and not path.is_dir():
            raise _ApiError(400, "bad_request", "Path is not a directory")
        if file and not path.is_file():
            raise _ApiError(400, "bad_request", "Path is not a file")
        return path

    def _ping(self) -> None:
        self._send_json(
            200,
            {
                "app": "phone2agent",
                "api": 1,
                "version": "0.1.0",
                "device": "Fake Phone",
                "android_sdk": 35,
                "roots": [{"id": "primary", "path": str(self.server.root), "label": "Internal"}],
                "all_files_access": True,
                "time_ms": time.time_ns() // 1_000_000 + int(self.server.clock_offset_s * 1000),
                "timezone": self.server.timezone,
            },
        )

    def _ls(self, query: dict[str, list[str]]) -> None:
        self._require_no_unknown(query, {"path", "hidden"})
        directory = self._existing(self._one(query, "path", ""), directory=True)
        hidden = self._bool(query, "hidden")
        entries = []
        for child in directory.iterdir():
            if not hidden and child.name.startswith("."):
                continue
            try:
                entries.append(self._entry(child))
            except (FileNotFoundError, _ApiError):
                # Android/data, dangling links, and links outside roots are
                # absent from directory listings rather than breaking them.
                continue
        entries.sort(key=lambda item: (item["type"] != "dir", str(item["name"]).casefold(), str(item["name"])))
        self._send_json(200, {"path": str(directory), "entries": entries})

    def _stat(self, query: dict[str, list[str]]) -> None:
        self._require_no_unknown(query, {"path"})
        value = self._one(query, "path", None)
        if value is None:
            raise _ApiError(400, "bad_request", "path is required")
        self._send_json(200, self._entry(self._existing(value)))

    def _find(self, query: dict[str, list[str]]) -> None:
        allowed = {"root", "ext", "name", "since_ms", "until_ms", "type", "max_depth", "limit", "sort", "hidden"}
        self._require_no_unknown(query, allowed)
        root = self._existing(self._one(query, "root", ""), directory=True)
        hidden = self._bool(query, "hidden")
        extensions = self._extensions(self._one(query, "ext", ""))
        name = (self._one(query, "name", "") or "").casefold()
        since = self._int(query, "since_ms", None)
        until = self._int(query, "until_ms", None)
        if since is not None and until is not None and since > until:
            raise _ApiError(400, "bad_request", "since_ms must not exceed until_ms")
        wanted_type = self._one(query, "type", "file")
        if wanted_type not in {"file", "dir", "any"}:
            raise _ApiError(400, "bad_request", "type must be file, dir, or any")
        max_depth = self._int(query, "max_depth", 12, minimum=0)
        limit = self._int(query, "limit", 500, minimum=1, maximum=5000)
        sort = self._one(query, "sort", "mtime_desc")
        if sort not in {"mtime_desc", "mtime_asc", "name"}:
            raise _ApiError(400, "bad_request", "sort must be mtime_desc, mtime_asc, or name")

        entries: list[dict[str, object]] = []
        for current, directory_names, file_names in os.walk(root, followlinks=False):
            current_path = Path(current)
            depth = len(current_path.relative_to(root).parts)
            kept_directories = []
            for directory_name in directory_names:
                candidate = current_path / directory_name
                if not hidden and directory_name.startswith("."):
                    continue
                try:
                    resolved = self._resolve(str(candidate))
                    if not resolved.is_dir() or candidate.is_symlink():
                        continue
                    kept_directories.append(directory_name)
                except _ApiError:
                    continue
            directory_names[:] = kept_directories if depth < max_depth else []

            names: list[str] = []
            if depth < max_depth:
                names.extend(kept_directories)
                names.extend(file_names)
            for child_name in names:
                if not hidden and child_name.startswith("."):
                    continue
                candidate = current_path / child_name
                try:
                    entry = self._entry(candidate)
                except (FileNotFoundError, OSError, _ApiError):
                    continue
                if wanted_type != "any" and entry["type"] != wanted_type:
                    continue
                if extensions and entry["type"] == "file":
                    suffix = Path(str(entry["name"])).suffix.removeprefix(".").casefold()
                    if suffix not in extensions:
                        continue
                elif extensions:
                    continue
                if name and name not in str(entry["name"]).casefold():
                    continue
                mtime_ms = int(entry["mtime_ms"])
                if since is not None and mtime_ms < since:
                    continue
                if until is not None and mtime_ms > until:
                    continue
                entries.append(entry)

        if sort == "mtime_desc":
            entries.sort(key=lambda item: (-int(item["mtime_ms"]), str(item["path"]).casefold()))
        elif sort == "mtime_asc":
            entries.sort(key=lambda item: (int(item["mtime_ms"]), str(item["path"]).casefold()))
        else:
            entries.sort(key=lambda item: (str(item["name"]).casefold(), str(item["path"]).casefold()))
        truncated = len(entries) > limit
        self._send_json(200, {"root": str(root), "entries": entries[:limit], "truncated": truncated})

    @staticmethod
    def _extensions(raw: str | None) -> set[str]:
        if not raw:
            return set()
        extensions = set()
        for value in raw.split(","):
            if not value or value.startswith(".") or "/" in value or "\\" in value:
                raise _ApiError(400, "bad_request", "ext must be comma-separated extensions without dots")
            extensions.add(value.casefold())
        return extensions

    def _file(self, query: dict[str, list[str]], *, head: bool) -> None:
        self._require_no_unknown(query, {"path"})
        value = self._one(query, "path", None)
        if value is None:
            raise _ApiError(400, "bad_request", "path is required")
        path = self._existing(value, file=True)
        stat = path.stat()
        size = stat.st_size
        mtime_ms = stat.st_mtime_ns // 1_000_000
        etag = f'"{size}-{mtime_ms}"'
        start, end, status = 0, size - 1, HTTPStatus.OK
        range_header = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        if range_header is not None and (if_range is None or if_range.strip() == etag):
            start, end = self._parse_range(range_header, size)
            status = HTTPStatus.PARTIAL_CONTENT

        length = 0 if size == 0 else end - start + 1
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Last-Modified", email.utils.formatdate(stat.st_mtime, usegmt=True))
        self.send_header("ETag", etag)
        self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head or length == 0:
            return
        with path.open("rb") as stream:
            stream.seek(start)
            remaining = length
            while remaining:
                chunk = stream.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    @staticmethod
    def _parse_range(header: str, size: int) -> tuple[int, int]:
        match = _RANGE_RE.fullmatch(header.strip())
        if match is None or size == 0:
            raise _ApiError(416, "range_not_satisfiable", "Requested range is not satisfiable")
        first, last = match.groups()
        if not first and not last:
            raise _ApiError(416, "range_not_satisfiable", "Requested range is not satisfiable")
        if first:
            start = int(first)
            end = int(last) if last else size - 1
            if start >= size or end < start:
                raise _ApiError(416, "range_not_satisfiable", "Requested range is not satisfiable")
            end = min(end, size - 1)
        else:
            suffix = int(last)
            if suffix <= 0:
                raise _ApiError(416, "range_not_satisfiable", "Requested range is not satisfiable")
            start = max(0, size - suffix)
            end = size - 1
        return start, end

    def _send_json(self, status: int, payload: object, *, headers: dict[str, str] | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _send_error(
        self,
        status: int,
        code: str,
        message: str,
        *,
        headers: dict[str, str] | None = None,
    ) -> None:
        self._send_json(status, {"error": code, "message": message}, headers=headers)


class FakePhone:
    """Run the fake phone API in a background thread.

    Use as ``with FakePhone(root) as phone:``.  ``phone.url`` is available
    after entering the context, and is also populated immediately for callers
    that explicitly invoke ``start()``.
    """

    def __init__(
        self,
        root_dir: str | os.PathLike[str],
        token: str = "t",
        timezone: str = "Asia/Shanghai",
        clock_offset_s: float = 0,
    ):
        self.root_dir = Path(root_dir)
        self.token = token
        self.timezone = timezone
        self.clock_offset_s = clock_offset_s
        self.url = ""
        self._server: _Server | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> "FakePhone":
        if self._server is not None:
            return self
        if not self.root_dir.is_dir():
            raise ValueError(f"fake phone root is not a directory: {self.root_dir}")
        self._server = _Server(
            ("127.0.0.1", 0), self.root_dir, self.token,
            self.timezone, self.clock_offset_s,
        )
        host, port = self._server.server_address[:2]
        self.url = f"http://{host}:{port}"
        self._thread = threading.Thread(target=self._server.serve_forever, name="fake-phone", daemon=True)
        self._thread.start()
        return self

    def close(self) -> None:
        server, thread = self._server, self._thread
        if server is None:
            return
        server.shutdown()
        server.server_close()
        if thread is not None:
            thread.join(timeout=2)
        self._server = None
        self._thread = None
        self.url = ""

    def __enter__(self) -> "FakePhone":
        return self.start()

    def __exit__(self, exc_type, exc, traceback) -> None:
        self.close()
