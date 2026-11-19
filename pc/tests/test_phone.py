from __future__ import annotations

import io
import http.client
import os
import socket
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
from urllib.parse import urlsplit

from tentacle import __version__
from tentacle.config import PhoneConfig
from tentacle.phone import PhoneClient, PhoneError, parse_pair_string

from tests.fakephone import FakePhone


class _PrefixProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._forward()

    def do_HEAD(self):
        self._forward()

    def _forward(self):
        if not self.path.startswith("/tentacle/"):
            self.send_error(404)
            return
        upstream = urlsplit(self.server.upstream)
        connection = http.client.HTTPConnection(upstream.hostname, upstream.port, timeout=2)
        headers = {key: value for key, value in self.headers.items()
                   if key.lower() not in {"connection", "host"}}
        connection.request(self.command, self.path[len("/tentacle"):], headers=headers)
        response = connection.getresponse()
        body = response.read()
        self.send_response(response.status)
        for key, value in response.getheaders():
            if key.lower() not in {"connection", "date", "server", "transfer-encoding"}:
                self.send_header(key, value)
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        connection.close()

    def log_message(self, _format, *_args):
        pass


class PrefixProxy:
    def __init__(self, upstream: str):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _PrefixProxyHandler)
        self.server.upstream = upstream
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}/tentacle"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class _Response:
    def __init__(self, body=b"", *, status=200, headers=None, read_error=None):
        self._body = io.BytesIO(body)
        self.status = status
        self.headers = headers or {}
        self._read_error = read_error
        self.closed = False

    def read(self, size=-1):
        if self._read_error is not None:
            raise self._read_error
        return self._body.read(size)

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        self.close()


class PairStringTests(unittest.TestCase):
    def test_new_pair_uri_preserves_encoded_https_prefix(self):
        self.assertEqual(
            parse_pair_string(
                "tentacle://pair?name=My%20Phone&token=a%2Bb&"
                "url=https%3A%2F%2Fexample.com%2Ftentacle"
            ),
            ("https://example.com/tentacle", "a+b", "My Phone"),
        )

    def test_pair_uri(self):
        self.assertEqual(
            parse_pair_string("tentacle://phone.local:8765?token=a%2Bb&name=My%20Phone"),
            ("http://phone.local:8765", "a+b", "My Phone"),
        )

    def test_http_url_with_separate_token(self):
        self.assertEqual(
            parse_pair_string("https://phone.example:8765/", "secret"),
            ("https://phone.example:8765", "secret", ""),
        )

    def test_http_url_preserves_path_prefix(self):
        self.assertEqual(
            parse_pair_string("https://example.com/tentacle/", "secret"),
            ("https://example.com/tentacle", "secret", ""),
        )

    def test_invalid_pair_strings(self):
        for value in (
            "tentacle://phone.local:8765?name=Phone",
            "tentacle://phone.local?token=t",
            "tentacle://pair?name=Phone&token=t",
            "tentacle://pair?name=Phone&url=https%3A%2F%2Fexample.com",
            "tentacle://user@pair?token=t&url=https%3A%2F%2Fexample.com",
            "tentacle://pair?token=a&token=b&url=https%3A%2F%2Fexample.com",
            "tentacle://pair?token=t&url=https%3A%2F%2Fexample.com&url=",
            "tentacle://pair?token=t&url=https%3A%2F%2Fexample.com&url=ftp%3A%2F%2Fexample.com",
            "tentacle://pair?token=t&url=tentacle%3A%2F%2Fphone.local%3A8765%3Ftoken%3Dnested",
            "ftp://phone.local:8765",
            "http://phone.local",
            "http://phone.local:8765/path?query=invalid",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_pair_string(value)


class PhoneClientTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "Music").mkdir()
        (self.root / "Music" / "hello.txt").write_text("hello phone", encoding="utf-8")
        (self.root / "Music" / "empty.txt").write_bytes(b"")
        (self.root / "Music" / "song.M4A").write_bytes(b"0123456789" * 100)
        (self.root / "Music" / "Nested").mkdir()
        (self.root / "Music" / "Nested" / "deep.m4a").write_bytes(b"deep")
        (self.root / ".hidden.txt").write_text("hidden", encoding="utf-8")
        (self.root / ".secret").mkdir()
        (self.root / ".secret" / "hidden.m4a").write_bytes(b"secret")
        (self.root / "Android" / "data").mkdir(parents=True)
        (self.root / "outside-link").symlink_to(self.root.parent)
        os.utime(self.root / "Music" / "hello.txt", (1_700_000_000, 1_700_000_000))
        os.utime(self.root / "Music" / "song.M4A", (1_710_000_000, 1_710_000_000))
        self.server = FakePhone(self.root, token="t")
        self.server.__enter__()
        self.client = PhoneClient(PhoneConfig(url=self.server.url, token="t", timeout_s=2))

    def tearDown(self):
        self.server.__exit__(None, None, None)
        self.temp.cleanup()

    def test_ping_listing_stat_find_and_read(self):
        self.assertEqual(self.client.ping()["api"], 1)
        listing = self.client.ls("Music")
        self.assertEqual(
            {item["name"] for item in listing["entries"]},
            {"Nested", "empty.txt", "hello.txt", "song.M4A"},
        )
        item = self.client.stat("Music/hello.txt")
        self.assertEqual(item["size"], 11)

        found = self.client.find(root="Music", ext=["m4a"], name="SONG", limit=10)
        self.assertEqual([entry["name"] for entry in found["entries"]], ["song.M4A"])
        self.assertEqual(self.client.read_bytes("Music/hello.txt", 5), b"hello")
        self.assertEqual(self.client.read_bytes("Music/empty.txt", 5), b"")
        self.assertEqual(self.client.read_bytes("Music/hello.txt", 0), b"")

    def test_ping_is_cached_and_rejects_other_apps(self):
        expected = self.client.ping()
        with mock.patch.object(self.client, "_json") as request:
            self.assertIs(self.client.ping(), expected)
            request.assert_not_called()
        client = PhoneClient(PhoneConfig(url=self.server.url, token="t", timeout_s=2))
        with mock.patch.object(client, "_json", return_value={"app": "other"}):
            with self.assertRaises(PhoneError) as caught:
                client.ping()
        self.assertEqual(caught.exception.code, "wrong_app")
        with mock.patch.object(client, "_json", return_value={"app": "tentacle", "api": 2}):
            with self.assertRaises(PhoneError) as caught:
                client.ping()
        self.assertEqual(caught.exception.code, "unsupported_api")

    def test_operations_reject_wrong_app_before_access(self):
        with FakePhone(self.root, token="t", app="other") as server:
            client = PhoneClient(PhoneConfig(url=server.url, token="t", timeout_s=2))
            operations = (
                lambda: client.ls("Music"),
                lambda: client.read_bytes("Music/hello.txt", 5),
                lambda: client.download("Music/hello.txt", self.root / "wrong-app.txt"),
            )
            for operation in operations:
                with self.subTest(operation=operation), self.assertRaises(PhoneError) as caught:
                    operation()
                self.assertEqual(caught.exception.code, "wrong_app")
        self.assertFalse((self.root / "wrong-app.txt").exists())

    def test_path_prefix_and_find_specific_timeout(self):
        client = PhoneClient(PhoneConfig(
            url="https://example.com/tentacle/", token="t", timeout_s=3,
            find_timeout_s=120,
        ))
        ping = _Response(b'{"app": "tentacle", "api": 1}')
        response = _Response(b'{"entries": []}')
        with mock.patch.object(client._opener, "open", side_effect=[ping, response]) as opened:
            client.find(root="Recordings")
        request, = opened.call_args.args
        self.assertEqual(request.full_url, "https://example.com/tentacle/v1/find?root=Recordings&type=file&hidden=0")
        self.assertEqual(request.get_header("User-agent"), f"tentacle/{__version__}")
        self.assertEqual(opened.call_args.kwargs["timeout"], 120)
        self.assertEqual(opened.call_args_list[0].kwargs["timeout"], 3)

    def test_path_prefix_proxy_covers_requests_downloads_and_errors(self):
        with PrefixProxy(self.server.url) as proxy:
            client = PhoneClient(PhoneConfig(url=proxy.url, token="t", timeout_s=2))
            self.assertEqual(client.ping()["api"], 1)
            self.assertEqual(client.stat("Music/hello.txt")["size"], 11)
            destination = self.root / "prefixed-download.txt"
            client.download("Music/hello.txt", destination)
            self.assertEqual(destination.read_bytes(), b"hello phone")
            with self.assertRaises(PhoneError) as caught:
                client.stat("missing.txt")
            self.assertEqual((caught.exception.code, caught.exception.status), ("not_found", 404))

    def test_find_filters_depth_hidden_sort_and_truncation(self):
        shallow = self.client.find(root="Music", type="file", max_depth=1, sort="name")
        self.assertEqual(
            [entry["name"] for entry in shallow["entries"]],
            ["empty.txt", "hello.txt", "song.M4A"],
        )
        directories = self.client.find(root="Music", type="dir", max_depth=1)
        self.assertEqual([entry["name"] for entry in directories["entries"]], ["Nested"])
        window = self.client.find(
            root="Music",
            type="file",
            since_ms=1_705_000_000_000,
            until_ms=1_715_000_000_000,
        )
        self.assertEqual([entry["name"] for entry in window["entries"]], ["song.M4A"])
        self.assertEqual(self.client.find(ext="m4a")["entries"][0]["name"], "deep.m4a")
        hidden = self.client.find(ext="m4a", hidden=True, sort="name")
        self.assertIn("hidden.m4a", [entry["name"] for entry in hidden["entries"]])
        limited = self.client.find(root="Music", type="any", limit=1)
        self.assertEqual(len(limited["entries"]), 1)
        self.assertTrue(limited["truncated"])

    def test_file_head_ranges_and_denied_paths(self):
        with self.client._open("file", {"path": "Music/song.M4A"}, method="HEAD") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(int(response.headers["Content-Length"]), 1000)
            self.assertTrue(response.headers["ETag"].startswith('"1000-'))
            self.assertEqual(response.read(), b"")
        with self.client._open(
            "file", {"path": "Music/song.M4A"}, headers={"Range": "bytes=-4"}
        ) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.headers["Content-Range"], "bytes 996-999/1000")
            self.assertEqual(response.read(), b"6789")
        with self.client._open(
            "file", {"path": "Music/song.M4A"}, headers={"Range": "bytes=10-14"}
        ) as response:
            self.assertEqual(response.read(), b"01234")
        with self.assertRaises(PhoneError) as caught:
            self.client._open("file", {"path": "Music/song.M4A"}, headers={"Range": "bytes=2000-"})
        self.assertEqual((caught.exception.code, caught.exception.status), ("range_not_satisfiable", 416))
        for denied in ("Android/data", "outside-link"):
            with self.subTest(path=denied), self.assertRaises(PhoneError) as caught:
                self.client.stat(denied)
            self.assertEqual((caught.exception.code, caught.exception.status), ("forbidden", 403))

    def test_server_error_is_mapped(self):
        with self.assertRaises(PhoneError) as caught:
            self.client.stat("missing.txt")
        self.assertEqual(caught.exception.code, "not_found")
        self.assertEqual(caught.exception.status, 404)

        wrong = PhoneClient(PhoneConfig(url=self.server.url, token="wrong", timeout_s=2))
        with self.assertRaises(PhoneError) as caught:
            wrong.ping()
        self.assertEqual(caught.exception.code, "unauthorized")
        self.assertEqual(caught.exception.status, 401)

    def test_unpaired_client(self):
        client = PhoneClient(PhoneConfig())
        with self.assertRaises(PhoneError) as caught:
            client.ping()
        self.assertEqual(caught.exception.code, "not_paired")
        self.assertIsNone(caught.exception.status)
        with self.assertRaises(PhoneError) as caught:
            client.read_bytes("anything", 0)
        self.assertEqual(caught.exception.code, "not_paired")

    def test_bypasses_environment_proxy(self):
        proxy_env = {
            "http_proxy": "http://127.0.0.1:1",
            "HTTP_PROXY": "http://127.0.0.1:1",
            "no_proxy": "",
            "NO_PROXY": "",
        }
        with mock.patch.dict(os.environ, proxy_env):
            client = PhoneClient(PhoneConfig(url=self.server.url, token="t", timeout_s=2))
            self.assertEqual(client.ping()["api"], 1)

    def test_download_and_resume_matching_etag(self):
        source = self.root / "Music" / "song.M4A"
        content = source.read_bytes()
        destination = self.root / "downloads" / "song.m4a"
        part = Path(str(destination) + ".part")
        etag_file = Path(str(part) + ".etag")
        destination.parent.mkdir()
        part.write_bytes(content[:333])
        mtime_ms = source.stat().st_mtime_ns // 1_000_000
        etag_file.write_text(f'"{len(content)}-{mtime_ms}"', encoding="ascii")
        progress: list[tuple[int, int | None]] = []

        result = self.client.download(
            "Music/song.M4A",
            destination,
            expected_size=len(content),
            progress=lambda done, total: progress.append((done, total)),
        )

        self.assertEqual(result, destination)
        self.assertEqual(destination.read_bytes(), content)
        self.assertFalse(part.exists())
        self.assertFalse(etag_file.exists())
        self.assertEqual(progress[-1], (len(content), len(content)))

    def test_download_rejects_wrong_expected_size(self):
        destination = self.root / "wrong.bin"
        with self.assertRaises(PhoneError) as caught:
            self.client.download("Music/song.M4A", destination, expected_size=2)
        self.assertEqual(caught.exception.code, "size_mismatch")
        self.assertFalse(destination.exists())


class DownloadProtocolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.client = PhoneClient(PhoneConfig(url="http://phone.test:8765", token="t"))

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def head(size: str | None = "6", etag: str = '"old"') -> _Response:
        headers = {"ETag": etag}
        if size is not None:
            headers["Content-Length"] = size
        return _Response(headers=headers)

    def partial(self, content=b"ab") -> tuple[Path, Path, Path]:
        destination = self.root / "file.bin"
        part = Path(str(destination) + ".part")
        etag = Path(str(part) + ".etag")
        part.write_bytes(content)
        etag.write_text('"old"', encoding="ascii")
        return destination, part, etag

    def test_invalid_head_size_requires_expected_size(self):
        for value in (None, "bad", "-1", "+6"):
            with self.subTest(value=value), mock.patch.object(
                self.client, "_open", return_value=self.head(value)
            ):
                with self.assertRaises(PhoneError) as caught:
                    self.client.download("file.bin", self.root / "output.bin")
                self.assertEqual(caught.exception.code, "bad_response")

        responses = [self.head("bad"), _Response(b"abcdef")]
        with mock.patch.object(self.client, "_open", side_effect=responses):
            result = self.client.download("file.bin", self.root / "known.bin", expected_size=6)
        self.assertEqual(result.read_bytes(), b"abcdef")

    def test_resume_restarts_when_server_ignores_range(self):
        destination, _, _ = self.partial()
        ignored = _Response(b"abcdef", status=200, headers={"ETag": '"old"'})
        full = _Response(b"abcdef", headers={"ETag": '"old"'})
        with mock.patch.object(self.client, "_open", side_effect=[self.head(), ignored, full]) as opened:
            self.client.download("file.bin", destination)
        self.assertEqual(destination.read_bytes(), b"abcdef")
        self.assertTrue(ignored.closed)
        self.assertEqual(opened.call_count, 3)

    def test_resume_restarts_for_changed_or_missing_etag(self):
        for response_etag in ('"new"', ""):
            with self.subTest(etag=response_etag):
                destination, part, sidecar = self.partial()
                resumed = _Response(
                    b"cdef",
                    status=206,
                    headers={"Content-Range": "bytes 2-5/6", "ETag": response_etag},
                )
                full = _Response(b"ABCDEF", headers={"ETag": '"new"'})
                with mock.patch.object(self.client, "_open", side_effect=[self.head(), resumed, full]):
                    self.client.download("file.bin", destination)
                self.assertEqual(destination.read_bytes(), b"ABCDEF")
                self.assertFalse(part.exists())
                self.assertFalse(sidecar.exists())
                destination.unlink()

    def test_resume_restarts_for_wrong_numeric_content_range(self):
        destination, _, _ = self.partial()
        wrong_range = _Response(
            b"cdef",
            status=206,
            headers={"Content-Range": "bytes 2-5/99", "ETag": '"old"'},
        )
        full = _Response(b"abcdef", headers={"ETag": '"old"'})
        with mock.patch.object(self.client, "_open", side_effect=[self.head(), wrong_range, full]):
            self.client.download("file.bin", destination)
        self.assertEqual(destination.read_bytes(), b"abcdef")

    def test_short_transfer_preserves_destination_and_partial(self):
        destination = self.root / "file.bin"
        destination.write_bytes(b"previous")
        with mock.patch.object(
            self.client, "_open", side_effect=[self.head(), _Response(b"abc", headers={"ETag": '"old"'})]
        ):
            with self.assertRaises(PhoneError) as caught:
                self.client.download("file.bin", destination)
        self.assertEqual(caught.exception.code, "size_mismatch")
        self.assertEqual(destination.read_bytes(), b"previous")
        self.assertEqual(Path(str(destination) + ".part").read_bytes(), b"abc")

    def test_body_timeout_is_classified_and_preserves_destination(self):
        destination = self.root / "file.bin"
        destination.write_bytes(b"previous")
        timed_out = _Response(headers={"ETag": '"old"'}, read_error=socket.timeout())
        with mock.patch.object(self.client, "_open", side_effect=[self.head(), timed_out]):
            with self.assertRaises(PhoneError) as caught:
                self.client.download("file.bin", destination)
        self.assertEqual(caught.exception.code, "timeout")
        self.assertEqual(destination.read_bytes(), b"previous")


if __name__ == "__main__":
    unittest.main()
