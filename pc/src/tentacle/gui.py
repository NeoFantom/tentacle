"""Loopback-only web viewer for notifications shared by the phone."""
from __future__ import annotations

import hmac
import json
import secrets
import shutil
import subprocess
import sys
import urllib.parse
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from pathlib import Path
from typing import Any, Callable

from .notifications import (
    OFF_MESSAGE,
    UPDATE_MESSAGE,
    NotificationFeed,
    SharingOff,
    UnsupportedPhone,
)
from .paths import is_lan, is_tailnet
from .phone import PhoneError


_SECRET_HEADER = "X-Tentacle-Key"
_MAX_WAIT = 60
_MAX_LIMIT = 500


class GuiServer(ThreadingHTTPServer):
    """A GUI HTTP server carrying only per-launch, non-persistent state."""

    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, cfg: Any, port: int):
        self.cfg = cfg
        self.secret = secrets.token_urlsafe(32)
        super().__init__(("127.0.0.1", port), _GuiHandler)
        host, selected_port = self.server_address[:2]
        self.url = f"http://{host}:{selected_port}/?k={urllib.parse.quote(self.secret)}"


def create_server(cfg: Any, port: int = 0) -> GuiServer:
    """Create, but do not start, a loopback-only notification GUI server."""
    if isinstance(port, bool) or not isinstance(port, int) or not 0 <= port <= 65535:
        raise ValueError("--port must be between 0 and 65535")
    return GuiServer(cfg, port)


class _GuiHandler(BaseHTTPRequestHandler):
    server: GuiServer

    def parse_request(self) -> bool:
        if not super().parse_request():
            return False
        # Gate even unknown methods before BaseHTTPRequestHandler dispatches them.
        if self.command != "GET" and not self._authorized_header():
            self._send_json(HTTPStatus.FORBIDDEN, {"error": "forbidden"})
            return False
        return True

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == "/":
                self._serve_page(parsed)
                return
            if not self._authorized_header():
                self._send_json(HTTPStatus.FORBIDDEN, {"error": "forbidden"})
                return
            if parsed.path == "/api/notifications":
                self._notifications(parsed.query)
            elif parsed.path == "/api/status":
                self._status(parsed.query)
            else:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except (BrokenPipeError, ConnectionResetError):
            pass  # A browser can cancel an obsolete long poll while filtering.

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_PUT(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_PATCH(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_OPTIONS(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_TRACE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def do_CONNECT(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._unsupported_method()

    def log_message(self, _format: str, *_args: object) -> None:
        # The page URL contains the local GUI key. Never copy request targets to logs.
        return

    def _authorized_header(self) -> bool:
        supplied = self.headers.get(_SECRET_HEADER, "")
        return _same_secret(supplied, self.server.secret)

    def _unsupported_method(self) -> None:
        if not self._authorized_header():
            self._send_json(HTTPStatus.FORBIDDEN, {"error": "forbidden"})
            return
        self.send_response(HTTPStatus.METHOD_NOT_ALLOWED)
        self.send_header("Allow", "GET")
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _serve_page(self, parsed: urllib.parse.SplitResult) -> None:
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        supplied = query.get("k", [""])
        if (set(query) != {"k"} or len(supplied) != 1
                or not _same_secret(supplied[0], self.server.secret)):
            self._send_json(HTTPStatus.FORBIDDEN, {"error": "forbidden"})
            return
        page = resources.files("tentacle").joinpath("gui.html").read_bytes()
        self._send(HTTPStatus.OK, page, "text/html; charset=utf-8", csp=True)

    def _notifications(self, raw_query: str) -> None:
        try:
            query = _query(raw_query, {"since", "wait", "app", "grep", "limit"})
            since_ms = _optional_int(query, "since", minimum=0)
            wait = _int(query, "wait", default=0, minimum=0, maximum=_MAX_WAIT)
            limit = _int(query, "limit", default=_MAX_LIMIT, minimum=1, maximum=_MAX_LIMIT)
            app = _optional_text(query, "app")
            grep = _optional_text(query, "grep")
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "bad_request", "message": str(exc)})
            return

        try:
            feed = self._feed()
            batch = feed.fetch(
                since_ms=since_ms,
                wait=wait,
                app=app,
                grep=grep,
                limit=limit,
            )
            payload = batch.to_dict()
            payload["base_url"] = feed.client.url
            payload["path_kind"] = _path_kind(feed.client)
            self._send_json(HTTPStatus.OK, payload)
        except SharingOff:
            self._send_json(HTTPStatus.CONFLICT, _status_payload("off", OFF_MESSAGE, enabled=False))
        except UnsupportedPhone:
            self._send_json(
                HTTPStatus.NOT_IMPLEMENTED,
                _status_payload("unsupported", UPDATE_MESSAGE, enabled=None),
            )
        except PhoneError as exc:
            self._send_json(
                HTTPStatus.BAD_GATEWAY,
                _status_payload("unreachable", _phone_error_message(exc), enabled=None),
            )

    def _status(self, raw_query: str) -> None:
        try:
            _query(raw_query, set())
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "bad_request", "message": str(exc)})
            return
        feed: NotificationFeed | None = None
        try:
            feed = self._feed()
            feed.fetch(wait=0, limit=1)
            self._send_json(HTTPStatus.OK, _status_payload(
                "connected",
                f"Connected via {_path_kind(feed.client)}.",
                enabled=True,
                base_url=feed.client.url,
                path_kind=_path_kind(feed.client),
            ))
        except SharingOff:
            self._send_json(HTTPStatus.OK, _status_payload(
                "off", OFF_MESSAGE, enabled=False,
                base_url=feed.client.url if feed is not None else "",
                path_kind=_path_kind(feed.client) if feed is not None else "",
            ))
        except UnsupportedPhone:
            self._send_json(
                HTTPStatus.OK,
                _status_payload("unsupported", UPDATE_MESSAGE, enabled=None),
            )
        except PhoneError as exc:
            self._send_json(HTTPStatus.OK, _status_payload(
                "unreachable", _phone_error_message(exc), enabled=None,
            ))

    def _feed(self) -> NotificationFeed:
        # PhoneClient instances are deliberately not shared between request threads.
        return NotificationFeed(self.server.cfg.phone, self.server.cfg.data_dir)

    def _send_json(self, status: HTTPStatus, payload: object) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def _send(self, status: HTTPStatus, body: bytes, content_type: str, *, csp: bool = False) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        if csp:
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; connect-src 'self'; script-src 'unsafe-inline'; "
                "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
        self.end_headers()
        self.wfile.write(body)


def _query(raw: str, allowed: set[str]) -> dict[str, str]:
    parsed = urllib.parse.parse_qs(raw, keep_blank_values=True)
    unknown = set(parsed) - allowed
    repeated = [name for name, values in parsed.items() if len(values) != 1]
    if unknown:
        raise ValueError("unknown query parameter")
    if repeated:
        raise ValueError("query parameter must not be repeated")
    return {name: values[0] for name, values in parsed.items()}


def _same_secret(supplied: str, expected: str) -> bool:
    return hmac.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8"))


def _int(
    query: dict[str, str], name: str, *, default: int, minimum: int, maximum: int,
) -> int:
    raw = query.get(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ValueError(f"{name} must be an integer") from None
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _optional_int(query: dict[str, str], name: str, *, minimum: int) -> int | None:
    raw = query.get(name)
    if raw is None:
        return None
    try:
        value = int(raw)
    except ValueError:
        raise ValueError(f"{name} must be an integer") from None
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


def _optional_text(query: dict[str, str], name: str) -> str | None:
    value = query.get(name)
    return value if value else None


def _status_payload(
    status: str,
    message: str,
    *,
    enabled: bool | None,
    base_url: str = "",
    path_kind: str = "",
) -> dict[str, object]:
    return {
        "connected": status in {"connected", "off"},
        "base_url": base_url,
        "path_kind": path_kind,
        "enabled": enabled,
        "status": status,
        "message": message,
    }


def _phone_error_message(exc: PhoneError) -> str:
    if exc.code == "not_paired":
        return "The phone is not paired. Pair it before opening the notification viewer."
    return "The phone is unreachable. Check that Tentacle is running and a configured path is available."


def _path_kind(client: Any) -> str:
    url = client.url
    selected = getattr(getattr(client, "paths", None), "last", None)
    if selected is not None:
        result = next((item for item in selected.results if item.url == url), None)
        if result is not None and result.status in {"direct", "relayed"}:
            return f"tailnet ({result.status})"
    if is_tailnet(url):
        return "tailnet"
    if is_lan(url):
        return "local network"
    if urllib.parse.urlsplit(url).scheme == "https":
        return "HTTPS"
    return "configured path"


def _read_proc_version(path: Path = Path("/proc/version")) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def open_browser(
    url: str,
    *,
    read_proc_version: Callable[[], str] = _read_proc_version,
    which: Callable[[str], str | None] = shutil.which,
    run: Callable[..., Any] = subprocess.run,
    browser_open: Callable[[str], Any] = webbrowser.open,
) -> str:
    """Open *url* with the host browser and return the selected mechanism."""
    if "microsoft" in read_proc_version().casefold():
        wslview = which("wslview")
        if wslview:
            run([wslview, url], check=False)
            return "wslview"
        cmd = which("cmd.exe") or "cmd.exe"
        run([cmd, "/c", "start", "", url], check=False)
        return "cmd.exe"
    browser_open(url)
    return "webbrowser"


def add_commands(subparsers: Any) -> None:
    parser = subparsers.add_parser("gui", help="open the local phone notification viewer")
    parser.add_argument("--port", type=int, default=0,
                        help="loopback port (default: choose a free port)")
    parser.add_argument("--no-open", action="store_true",
                        help="print the URL without opening a browser")
    parser.set_defaults(func=_run)


def _run(args: Any, cfg: Any) -> int:
    try:
        server = create_server(cfg, args.port)
    except (OSError, ValueError) as exc:
        print(f"GUI could not start: {exc}", file=sys.stderr)
        return 1
    print(server.url, flush=True)
    if not args.no_open:
        try:
            open_browser(server.url)
        except OSError as exc:
            print(f"Could not open a browser: {exc}", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
