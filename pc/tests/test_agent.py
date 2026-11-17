from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest
from importlib import resources
from pathlib import Path
from unittest import mock

from phone2agent.agent import _argv, run_agent
from phone2agent.config import AgentConfig
from phone2agent.jsonschema_lite import ValidationError, validate


FAKE_AGENT = Path(__file__).with_name("fake_agent.py").resolve()


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="agent tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.schema = self.root / "filing.json"
        self.schema.write_text(resources.files("phone2agent").joinpath(
            "schemas/filing.schema.json").read_text(encoding="utf-8"), encoding="utf-8")
        self.cfg = AgentConfig(kind="command", timeout_s=5, command=[
            sys.executable, str(FAKE_AGENT), "--prompt", "{prompt_file}",
            "--output", "{output_file}", "--schema", "{schema_file}",
            "--cwd", "{cwd}", "--mode", "{mode}"])

    def run_fake(self, **kwargs):
        return run_agent(self.cfg, "A multilingual prompt: 测试 $(literal)",
                         cwd=self.root, mode="read-only", log_path=self.root / "logs/run.log",
                         **kwargs)

    def test_codex_argv_no_execution(self):
        cfg = AgentConfig(binary="custom-codex", model="chosen-model", extra_args=["-c", "x=true"])
        with mock.patch("phone2agent.agent.subprocess.Popen") as popen:
            args = _argv(cfg, cwd=self.root, mode="read-only", prompt_file="prompt file",
                         output_file="output file", schema_file="schema file")
        popen.assert_not_called()
        self.assertEqual(args, ["custom-codex", "exec", "--skip-git-repo-check",
                              "--sandbox", "read-only", "--cd", str(self.root),
                              "--output-last-message", "output file", "--output-schema",
                              "schema file", "--model", "chosen-model", "-c", "x=true", "-"])
        args = _argv(AgentConfig(), cwd=self.root, mode="workspace-write",
                     prompt_file="p", output_file="o", schema_file=None)
        self.assertNotIn("--model", args)
        self.assertNotIn("--output-schema", args)

    def test_command_templates_stdin_logs_and_schema(self):
        capture = self.root / "capture.json"
        self.cfg.command += ["--capture", str(capture)]
        result = self.run_fake(output_schema=self.schema)
        self.assertTrue(result.ok, result.log_path.read_text())
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.json["report"], "ok")
        self.assertGreater(result.duration_s, 0)
        captured = json.loads(capture.read_text())
        self.assertEqual(captured["prompt"], captured["stdin"])
        self.assertEqual(captured["cwd"], str(self.root))
        self.assertEqual(captured["cwd_arg"], str(self.root))
        self.assertEqual(captured["mode"], "read-only")
        self.assertEqual(captured["schema"], str(self.schema))
        log = result.log_path.read_text()
        self.assertIn("fake stdout", log)
        self.assertIn("fake stderr", log)
        self.assertEqual(list(result.log_path.parent.iterdir()), [result.log_path])

    def test_fenced_json(self):
        self.cfg.command += ["--fenced"]
        result = self.run_fake(output_schema=self.schema)
        self.assertTrue(result.ok)
        self.assertTrue(result.final_message.startswith("```json"))

    def test_nonzero_even_with_valid_json(self):
        self.cfg.command += ["--exit-code", "7"]
        result = self.run_fake(output_schema=self.schema)
        self.assertFalse(result.ok)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(result.json["report"], "ok")

    def test_invalid_and_missing_output(self):
        for flag in ("--invalid", "--no-output"):
            with self.subTest(flag=flag):
                self.cfg.command.append(flag)
                result = self.run_fake(output_schema=self.schema)
                self.assertFalse(result.ok)
                self.assertIn("Agent error", result.log_path.read_text())
                self.cfg.command.pop()
        self.cfg.command.append("--no-output")
        result = self.run_fake()
        self.assertTrue(result.ok)
        self.assertEqual(result.final_message, "")
        self.assertIsNone(result.json)

    def test_schema_failure_logged_with_path(self):
        canned = self.root / "canned.json"
        canned.write_text('{"files": [3], "commit_message": "", "report": "ok"}', encoding="utf-8")
        self.cfg.command += ["--canned", str(canned)]
        result = self.run_fake(output_schema=self.schema)
        self.assertFalse(result.ok)
        self.assertIn("$.files[0]", result.log_path.read_text())

    def test_launch_error_and_bad_configuration(self):
        self.cfg.command = [str(self.root / "missing-executable")]
        result = self.run_fake()
        self.assertFalse(result.ok)
        self.assertIsNone(result.returncode)
        self.cfg.command = []
        self.assertFalse(self.run_fake().ok)
        self.cfg.kind = "unknown"
        self.assertFalse(self.run_fake().ok)
        with self.assertRaises(ValueError):
            run_agent(self.cfg, "", cwd=self.root, mode="danger-full-access",
                      log_path=self.root / "unused.log")

    def test_timeout(self):
        self.cfg.timeout_s = 0.15
        self.cfg.command += ["--sleep", "10"]
        started = time.monotonic()
        result = self.run_fake()
        self.assertFalse(result.ok)
        self.assertIsNotNone(result.returncode)
        self.assertLess(time.monotonic() - started, 3)
        self.assertIn("timed out", result.log_path.read_text())

    @unittest.skipUnless(os.name == "posix" and Path("/proc").exists(), "requires POSIX /proc")
    def test_timeout_kills_descendant(self):
        pid_file = self.root / "child.pid"
        self.cfg.timeout_s = 0.5
        self.cfg.command += ["--child-pid", str(pid_file), "--sleep", "10"]
        result = self.run_fake()
        self.assertFalse(result.ok)
        pid = int(pid_file.read_text())
        # A terminated child may briefly remain a zombie until its parent is reaped.
        for _ in range(30):
            stat = Path(f"/proc/{pid}/stat")
            if not stat.exists() or stat.read_text().split()[2] == "Z":
                break
            time.sleep(0.01)
        else:
            os.kill(pid, 9)
            self.fail("descendant survived agent timeout")


class SchemaTests(unittest.TestCase):
    def test_nested_path_bounds_and_types(self):
        schema = {"type": "object", "properties": {"routes": {"type": "array", "items": {
            "type": "object", "required": ["confidence"], "additionalProperties": False,
            "properties": {"confidence": {"type": "number", "minimum": 0, "maximum": 1}}}}}}
        validate({"routes": [{"confidence": 0}, {"confidence": 1}]}, schema)
        for value in (-0.1, 1.1, True, "0.5", float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaisesRegex(
                    ValidationError, r"\$\.routes\[0\]\.confidence"):
                validate({"routes": [{"confidence": value}]}, schema)
        for value, path in (({}, "confidence"), ({"confidence": 0.5, "other": 1}, "other")):
            with self.assertRaisesRegex(ValidationError, path):
                validate({"routes": [value]}, schema)

    def test_nullable_enum_and_remaining_types(self):
        for value in (None, "project"):
            validate(value, {"type": ["string", "null"]})
        for value in (False, 1, []):
            with self.assertRaises(ValidationError):
                validate(value, {"type": ["string", "null"]})
        validate(False, {"type": "boolean"})
        for value in (1, 1.0):
            validate(value, {"type": "integer"})
        for value in (1.5, True):
            with self.assertRaises(ValidationError):
                validate(value, {"type": "integer"})
        with self.assertRaises(ValidationError):
            validate(True, {"enum": [1]})
        validate("todo", {"enum": ["todo", "info"]})
        with self.assertRaises(ValidationError):
            validate("other", {"enum": ["todo", "info"]})


if __name__ == "__main__":
    unittest.main()
