from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from unittest import mock
import urllib.error
import urllib.parse
import urllib.request

from tentacle import cli, config, gui
from tentacle.phone import PhoneError
from tests.fakephone import FakePhone


class GuiTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.token = "test-private-phone-token"
        self.phone = FakePhone(self.root, token=self.token).start()
        self.addCleanup(self.phone.close)
        self.cfg = config.Config(phone=config.PhoneConfig(
            url=self.phone.url, token=self.token, timeout_s=2),
            storage=config.StorageConfig(data_dir=str(self.root / "data")))
        self.server = gui.create_server(self.cfg)
        self.addCleanup(self.server.server_close)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.thread.join, 2)
        self.addCleanup(self.server.shutdown)
        self.base = self.server.url.split("/?")[0]
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path, *, authenticated=True, method="GET", headers=None):
        actual = {"X-Tentacle-Key": self.server.secret} if authenticated else {}
        actual.update(headers or {})
        request = urllib.request.Request(self.base + path, headers=actual, method=method)
        try:
            response = self.opener.open(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, response_headers, body = response.status, str(response.headers), response.read()
        self.assertNotIn(self.token.encode(), body)
        self.assertNotIn(self.token, response_headers)
        self.assertIn("Cache-Control: no-store", response_headers)
        return status, response_headers, body

    def test_loopback_and_per_launch_secret(self):
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        self.assertGreater(self.server.server_address[1], 0)
        other = gui.create_server(self.cfg)
        self.addCleanup(other.server_close)
        self.assertNotEqual(self.server.secret, other.secret)
        self.assertGreaterEqual(len(self.server.secret), 32)

    def test_secret_required_for_every_path_and_method(self):
        for path in ("/", "/?k=wrong", "/api/status", "/api/notifications", "/missing",
                     "/?k=%E4%B8%AD", "/api/status?k=" + self.server.secret):
            with self.subTest(path=path):
                self.assertEqual(self.request(path, authenticated=False)[0], 403)
        for path in ("/api/status", "/api/notifications", "/missing"):
            self.assertEqual(self.request(path, headers={"X-Tentacle-Key": "wrong"})[0], 403)
        for method in ("POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE", "CONNECT", "CUSTOM"):
            with self.subTest(method=method):
                self.assertEqual(self.request("/api/status", method=method, authenticated=False)[0], 403)

    def test_static_page_and_xss_safe_rendering(self):
        status, headers, body = self.request("/?k=" + self.server.secret, authenticated=False)
        self.assertEqual(status, 200)
        self.assertIn("Content-Type: text/html", headers)
        self.assertIn("Content-Security-Policy:", headers)
        text = body.decode()
        self.assertIn("Notifications", text)
        self.assertIn("textContent", text)
        self.assertNotIn("innerHTML", text)
        self.assertNotIn("<script src=", text)
        self.assertIn("prefers-color-scheme: dark", text)
        self.assertIn("localStorage", text)
        self.assertIn("X-Tentacle-Key", text)
        self.assertIn("Removed", text)
        expected = self.phone.add_phone_notification(text='<img src=x onerror="alert(1)">')
        status, _, data = self.request("/api/notifications")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(data)["notifications"], [expected])

    def test_endpoint_proxies_and_applies_shared_filters(self):
        self.phone.add_phone_notification(posted_ms=100, title="Old")
        expected = self.phone.add_phone_notification(posted_ms=200, title="Delivery", text="Ready")
        self.phone.add_phone_notification(posted_ms=300, app="Other", package="com.example.other")
        status, _, body = self.request("/api/notifications?since=150&app=CHAT&grep=READY&limit=1")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload["notifications"], [expected])
        self.assertEqual(payload["base_url"], self.phone.url)
        self.assertTrue(payload["path_kind"])
        self.assertEqual(self.phone.notification_queries[-1]["since"], 150)

    def test_status_connected_off_old_and_unreachable(self):
        status, _, body = self.request("/api/status")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertTrue(payload["connected"])
        self.assertTrue(payload["enabled"])
        self.assertEqual(payload["base_url"], self.phone.url)
        self.assertTrue(payload["path_kind"])
        self.phone.set_notifications_enabled(False)
        for error_style in (False, True):
            self.phone.notifications_off_403 = error_style
            status, _, body = self.request("/api/status")
            payload = json.loads(body)
            self.assertEqual(payload["status"], "off")
            self.assertFalse(payload["enabled"])
            self.assertIn("Share notifications with the computer", payload["message"])
            self.assertNotEqual(self.request("/api/notifications")[0], 200)
        self.phone.notifications_supported = False
        self.assertEqual(json.loads(self.request("/api/status")[2])["status"], "unsupported")
        with mock.patch.object(gui.NotificationFeed, "fetch", side_effect=PhoneError("unreachable", self.token)):
            self.assertEqual(json.loads(self.request("/api/status")[2])["status"], "unreachable")
            self.assertEqual(self.request("/api/notifications")[0], 502)

    def test_gui_long_poll_wakes_on_arrival(self):
        self.phone.add_phone_notification(posted_ms=100, updated_ms=100)
        timer = threading.Timer(
            0.15,
            lambda: self.phone.add_phone_notification(posted_ms=101, updated_ms=101, text="Live"),
        )
        timer.start()
        try:
            started = time.monotonic()
            status, _, body = self.request("/api/notifications?since=100&wait=1")
        finally:
            timer.join(timeout=2)
        self.assertEqual(status, 200)
        self.assertGreaterEqual(time.monotonic() - started, 0.1)
        self.assertEqual(json.loads(body)["notifications"][0]["text"], "Live")

    def test_endpoint_replaces_updated_key_and_wakes_on_removal(self):
        self.phone.add_phone_notification(
            key="shared-key", posted_ms=100, updated_ms=100, title="Original",
        )
        timer = threading.Timer(0.15, lambda: self.phone.add_phone_notification(
            key="shared-key", posted_ms=100, updated_ms=200, title="Updated",
        ))
        timer.start()
        try:
            status, _, body = self.request("/api/notifications?since=100&wait=1")
        finally:
            timer.join(timeout=2)
        self.assertEqual(status, 200)
        changed = json.loads(body)["notifications"]
        self.assertEqual([(item["key"], item["title"], item["updated_ms"])
                          for item in changed], [("shared-key", "Updated", 200)])
        self.assertEqual(self.phone.notification_queries[-1]["since"], 100)

        timer = threading.Timer(0.15, lambda: self.phone.add_phone_notification(
            key="shared-key", posted_ms=100, updated_ms=300, title="Updated", removed_ms=250,
        ))
        timer.start()
        try:
            status, _, body = self.request("/api/notifications?since=200&wait=1")
        finally:
            timer.join(timeout=2)
        self.assertEqual(status, 200)
        removed = json.loads(body)["notifications"]
        self.assertEqual(len(removed), 1)
        self.assertEqual((removed[0]["key"], removed[0]["removed_ms"], removed[0]["updated_ms"]),
                         ("shared-key", 250, 300))
        self.assertEqual(self.phone.notification_queries[-1]["since"], 200)
        self.assertEqual([item["key"] for item in json.loads(
            self.request("/api/notifications")[2])["notifications"]], ["shared-key"])

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_merges_rows_by_key_and_uses_updated_cursor(self):
        page = Path(gui.__file__).with_name("gui.html").read_text(encoding="utf-8")
        script = page.split("<script>", 1)[1].split("</script>", 1)[0]
        harness = r'''
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.className = ""; this.textContent = ""; this.value = ""; }
  _add(child) { if (child && child.tag === "#fragment") this.children.push(...child.children); else this.children.push(child); }
  append(...children) { for (const child of children) this._add(child); }
  replaceChildren(...children) { this.children = []; this.append(...children); }
  addEventListener() {}
}
const elements = new Map(["results", "status-text", "status-dot", "app-filter", "search"].map(
  id => [id, new Element(id)]
));
globalThis.document = {
  getElementById: id => elements.get(id),
  createElement: tag => new Element(tag),
  createDocumentFragment: () => new Element("#fragment"),
  addEventListener() {}, visibilityState: "visible"
};
globalThis.Option = class extends Element {
  constructor(text, value) { super("option"); this.textContent = text; this.value = value; }
};
globalThis.location = {search: "?k=test"};
globalThis.localStorage = {getItem() { return null; }, setItem() {}};
globalThis.window = {addEventListener() {}};
const notification = (key, posted, updated, title, removed = null) => ({
  key, package: "example.app", app: "Example", title, text: "body", sub_text: "",
  category: "msg", posted_ms: posted, updated_ms: updated, ongoing: false, removed_ms: removed
});
let notificationCalls = 0;
let initialRow = null;
const requested = [];
function rows(root) {
  const found = [];
  function visit(value) {
    if (!value) return;
    if ((value.className || "").split(" ").includes("notification")) found.push(value);
    for (const child of value.children || []) visit(child);
  }
  visit(root);
  return found;
}
function textOf(value) {
  return (value.textContent || "") + (value.children || []).map(textOf).join("");
}
globalThis.fetch = async path => {
  requested.push(String(path));
  if (String(path).startsWith("/api/status")) {
    return {ok: true, json: async () => ({status: "connected", message: "Connected"})};
  }
  notificationCalls += 1;
  if (notificationCalls === 1) {
    return {ok: true, json: async () => ({notifications: [], now_ms: 100, path_kind: "test"})};
  }
  if (notificationCalls === 2) {
    return {ok: true, json: async () => ({
      notifications: [notification("same", 100, 100, "Original")], now_ms: 100, path_kind: "test"
    })};
  }
  if (notificationCalls === 3) {
    initialRow = rows(elements.get("results"))[0];
    return {ok: true, json: async () => ({notifications: [
      notification("new", 50, 300, "Older new key"),
      notification("same", 100, 200, "Updated", 190),
      notification("same", 100, 150, "Stale")
    ], now_ms: 300, path_kind: "test"})};
  }
  const actual = rows(elements.get("results"));
  process.stdout.write(JSON.stringify({
    count: actual.length,
    texts: actual.map(textOf),
    classes: actual.map(row => row.className),
    sameRow: actual.includes(initialRow),
    firstPoll: requested.filter(path => path.startsWith("/api/notifications"))[1],
    lastRequest: requested.filter(path => path.startsWith("/api/notifications")).at(-1)
  }));
  process.exit(0);
};
'''
        completed = subprocess.run(
            [shutil.which("node"), "-"], input=harness + script,
            check=True, capture_output=True, text=True, timeout=3,
        )
        state = json.loads(completed.stdout)
        self.assertEqual(state["count"], 2)
        self.assertIn("Older new key", state["texts"][0])
        self.assertIn("Updated", state["texts"][1])
        self.assertIn("Removed", state["texts"][1])
        self.assertNotIn("Stale", "".join(state["texts"]))
        self.assertIn("unread", state["classes"][0])
        self.assertIn("removed", state["classes"][1])
        self.assertTrue(state["sameRow"])
        self.assertIn("since=0", state["firstPoll"])
        self.assertIn("since=300", state["lastRequest"])

    def test_invalid_queries_and_unknown_paths(self):
        for query in ("wait=61", "wait=-1", "wait=no", "limit=0", "limit=501", "since=-1",
                      "since=no", "app=a&app=b", "unknown=a"):
            with self.subTest(query=query):
                self.assertEqual(self.request("/api/notifications?" + query)[0], 400)
        self.assertEqual(self.request("/api/status?wait=0")[0], 400)
        self.assertEqual(self.request("/missing")[0], 404)


class GuiLauncherTests(unittest.TestCase):
    def test_wsl_prefers_wslview(self):
        run, browser = mock.Mock(), mock.Mock()
        selected = gui.open_browser("http://127.0.0.1:9876/?k=test", read_proc_version=lambda: "Microsoft WSL",
                                    which=lambda name: "/fake/wslview" if name == "wslview" else None,
                                    run=run, browser_open=browser)
        self.assertEqual(selected, "wslview")
        run.assert_called_once_with(["/fake/wslview", "http://127.0.0.1:9876/?k=test"], check=False)
        browser.assert_not_called()

    def test_wsl_falls_back_to_cmd_start_with_empty_title(self):
        run, browser = mock.Mock(), mock.Mock()
        selected = gui.open_browser("http://127.0.0.1:9876/?k=test", read_proc_version=lambda: "microsoft",
                                    which=lambda _: None, run=run, browser_open=browser)
        self.assertEqual(selected, "cmd.exe")
        run.assert_called_once_with(["cmd.exe", "/c", "start", "", "http://127.0.0.1:9876/?k=test"], check=False)
        browser.assert_not_called()

    def test_other_system_uses_webbrowser(self):
        run, browser = mock.Mock(), mock.Mock()
        self.assertEqual(gui.open_browser("http://127.0.0.1:9876/?k=test", read_proc_version=lambda: "Linux",
                                         run=run, browser_open=browser), "webbrowser")
        browser.assert_called_once_with("http://127.0.0.1:9876/?k=test")
        run.assert_not_called()

    def test_no_open_and_ctrl_c_close_server(self):
        server = mock.Mock(url="http://127.0.0.1:9876/?k=test")
        server.serve_forever.side_effect = KeyboardInterrupt
        output = io.StringIO()
        with mock.patch.object(gui, "create_server", return_value=server) as create, \
                mock.patch.object(gui, "open_browser") as browser, \
                mock.patch.object(config, "load", return_value=config.Config()), \
                contextlib.redirect_stdout(output):
            self.assertEqual(cli.main(["gui", "--no-open", "--port", "9876"]), 0)
        self.assertEqual(create.call_args.args[1], 9876)
        self.assertEqual(output.getvalue(), server.url + "\n")
        server.server_close.assert_called_once()
        browser.assert_not_called()

    def test_invalid_port(self):
        for port in (-1, 65536):
            with self.subTest(port=port), self.assertRaises(ValueError):
                gui.create_server(config.Config(), port)


if __name__ == "__main__":
    unittest.main()
