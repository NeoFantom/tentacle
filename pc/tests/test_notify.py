from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from phone2agent.config import Config
from phone2agent.notify import notify


class NotifyTests(unittest.TestCase):
    def test_substitution_is_literal_truncated_and_does_not_use_shell(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "notification.json"
            script = (
                "import json, pathlib, sys; "
                "pathlib.Path(sys.argv[1]).write_text("
                "json.dumps(sys.argv[2:]), encoding='utf-8')"
            )
            cfg = Config()
            cfg.notify.command = [
                sys.executable, "-c", script, str(output),
                "title={title}", "body={body}", "both={title}/{body}",
            ]
            title = "literal {body}; $(touch ignored)"
            body = "x" * 1001 + "{title}"

            notify(cfg, title, body)

            self.assertEqual([
                f"title={title}",
                "body=" + "x" * 1000,
                f"both={title}/" + "x" * 1000,
            ], json.loads(output.read_text(encoding="utf-8")))

    def test_empty_command_does_nothing(self):
        cfg = Config()
        with mock.patch("phone2agent.notify.subprocess.run") as run:
            notify(cfg, "title", "body")
        run.assert_not_called()

    def test_nonzero_exit_is_reported_and_never_raised(self):
        cfg = Config()
        cfg.notify.command = ["notifier"]
        completed = subprocess.CompletedProcess(["notifier"], 7, stderr="bad option\n")
        stderr = io.StringIO()
        with mock.patch("phone2agent.notify.subprocess.run", return_value=completed) as run, \
                contextlib.redirect_stderr(stderr):
            notify(cfg, "title", "body")

        self.assertIn("exit code 7", stderr.getvalue())
        self.assertIn("bad option", stderr.getvalue())
        self.assertEqual(30, run.call_args.kwargs["timeout"])
        self.assertIs(subprocess.DEVNULL, run.call_args.kwargs["stdout"])
        self.assertFalse(run.call_args.kwargs["check"])

    def test_missing_command_and_timeout_are_tolerated(self):
        cfg = Config()
        cfg.notify.command = ["missing"]
        failures = [
            FileNotFoundError("not found"),
            subprocess.TimeoutExpired(["missing"], 30),
        ]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                stderr = io.StringIO()
                with mock.patch("phone2agent.notify.subprocess.run", side_effect=failure), \
                        contextlib.redirect_stderr(stderr):
                    notify(cfg, "title", "body")
                self.assertIn("Notification command failed", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
