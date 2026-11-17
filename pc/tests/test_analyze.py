from __future__ import annotations

import contextlib
import copy
import io
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock

from phone2agent.agent import AgentResult
from phone2agent.analyze import AnalysisError, analyze_recording, build_prompt
from phone2agent.cli import build_parser, main
from phone2agent.config import AgentConfig, Config, ProjectsConfig, StorageConfig
from phone2agent.jsonschema_lite import validate
from phone2agent.projects import Project
from phone2agent.store import Store


def canned_analysis():
    return {
        "title": "Call about the demo", "summary": "The demo needs review.",
        "participants": [{"name": "Owner", "role": "owner"}],
        "items": [{"kind": "todo", "text": "Review the demo.", "owner": "Owner",
                   "due": None, "project": "demo", "quote": "review the demo"}],
        "routes": [{"project": "demo", "confidence": 0.9, "reason": "Explicit mention",
                    "notes_markdown": "- Review the demo."}],
        "actions": [{"title": "Prepare review", "project": "demo",
                     "instructions": "Prepare a review of the demo.", "reason": "Approval needed"}],
    }


class AnalyzeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        project_root = self.root / "projects"
        (project_root / "demo").mkdir(parents=True)
        self.cfg = Config(storage=StorageConfig(data_dir=str(self.root / "data")),
                          projects=ProjectsConfig(roots=[str(project_root)]))
        self.cfg.analyze.owner_name = "Owner"
        self.cfg.analyze.language = "English"
        self.store = Store.open(self.cfg)
        self.addCleanup(self.store.close)
        self.id = self.store.add_recording(
            "calls", "recordings/example.wav", 100, 1000, status="transcribed",
            app="phone", counterpart="Colleague", started_at="2026-01-01T10:00:00+00:00",
            duration_s=70.5)[0]
        self.transcript = {"backend": "command", "model": "test", "language": "en",
                           "duration_s": 70.5, "text": "review the demo",
                           "segments": [{"id": 0, "start": 65.2, "end": 67,
                                         "speaker": "S1", "text": "review the demo"},
                                        {"id": 1, "start": 68, "end": 70.5,
                                         "speaker": None, "text": "Agreed."}]}
        self.transcript_path = self.cfg.data_dir / "transcripts" / f"{self.id}.json"
        self.transcript_path.parent.mkdir()
        self.transcript_path.write_text(json.dumps(self.transcript), encoding="utf-8")
        self.store.add_artifact(self.id, "transcript", self.transcript_path)

    def fake_result(self, data=None, ok=True):
        return AgentResult(ok, json.dumps(data if data is not None else canned_analysis()),
                           data if data is not None else canned_analysis(),
                           0 if ok else 7, self.cfg.data_dir / "logs/agent/fake.log", 0.1)

    def test_build_prompt(self):
        prompt = build_prompt(self.cfg, self.store.get_recording(self.id), self.transcript,
                              [Project("demo", self.root / "projects/demo", "Demo summary", False, False)])
        for value in ("Owner", "English", "source: calls", "app: phone",
                      "counterpart: Colleague", "started_at: 2026-01-01T10:00:00+00:00",
                      "duration: 70.5", "demo — Demo summary", "[01:05] S1: review the demo",
                      "[01:08] Agreed."):
            self.assertIn(value, prompt)
        self.assertNotIn("{{", prompt)

    def test_build_prompt_truncates_head_and_tail(self):
        transcript = {"segments": [], "text": "HEAD" + "x" * 130_000 + "TAIL"}
        prompt = build_prompt(self.cfg, self.store.get_recording(self.id), transcript, [])
        rendered = prompt.split("\nHEAD", 1)[1].split("\n\n## What to produce", 1)[0]
        self.assertEqual(120_000, len("HEAD" + rendered))
        self.assertIn("middle omitted", rendered)
        self.assertTrue(rendered.endswith("TAIL"))
        transcript["text"] = "x" * 120_000
        prompt = build_prompt(self.cfg, self.store.get_recording(self.id), transcript, [])
        self.assertNotIn("middle omitted", prompt)

    def test_happy_path_invocation_and_persistence(self):
        def runner(agent_cfg, prompt, **kwargs):
            self.assertIs(agent_cfg, self.cfg.agent)
            self.assertEqual(self.cfg.data_dir, kwargs["cwd"])
            self.assertEqual("read-only", kwargs["mode"])
            self.assertTrue(kwargs["output_schema"].is_file())
            schema = json.loads(kwargs["output_schema"].read_text())
            validate(canned_analysis(), schema)
            self.assertEqual(self.cfg.data_dir / "logs/agent", kwargs["log_path"].parent)
            self.assertTrue(kwargs["log_path"].name.endswith(f"-analyze-{self.id}.log"))
            self.assertIn("review the demo", prompt)
            return self.fake_result()
        result = analyze_recording(self.cfg, self.store, self.id, runner=runner)
        self.assertEqual(canned_analysis(), result)
        self.assertEqual("analyzed", self.store.get_recording(self.id)["status"])
        artifact = self.store.latest_artifact(self.id, "analysis")
        path = self.cfg.data_dir / "analyses" / f"{self.id}.json"
        self.assertEqual(str(path), artifact["path"])
        self.assertEqual(result, json.loads(path.read_text()))
        self.assertEqual([], json.loads(artifact["meta"])["unknown_projects"])
        self.assertEqual([path], list(path.parent.iterdir()))

    def test_full_fake_command_pipeline_and_relative_artifact_path(self):
        canned = self.root / "analysis.json"
        canned.write_text(json.dumps(canned_analysis()), encoding="utf-8")
        self.cfg.agent = AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}", "--canned", str(canned)])
        self.store.add_artifact(self.id, "transcript", f"transcripts/{self.id}.json")
        result = analyze_recording(self.cfg, self.store, self.id)
        self.assertEqual("Call about the demo", result["title"])
        self.assertEqual(1, len(list((self.cfg.data_dir / "logs/agent").glob("*.log"))))

    def test_unknown_projects_corrected_and_recorded_only_in_artifact_meta(self):
        data = canned_analysis()
        original = copy.deepcopy(data)
        data["routes"].append({**data["routes"][0], "project": "invented"})
        data["items"][0]["project"] = "invented"
        data["actions"][0]["project"] = "invented"
        result = analyze_recording(self.cfg, self.store, self.id,
                                   runner=lambda *a, **kw: self.fake_result(data))
        self.assertEqual(original["routes"], result["routes"])
        self.assertIsNone(result["items"][0]["project"])
        self.assertIsNone(result["actions"][0]["project"])
        self.assertEqual("invented", data["items"][0]["project"])
        self.assertNotIn("meta", result)
        corrections = json.loads(self.store.latest_artifact(self.id, "analysis")["meta"])["unknown_projects"]
        self.assertEqual(3, len(corrections))
        self.assertIn({"path": "$.routes[1].project", "project": "invented", "action": "dropped"}, corrections)

    def test_relative_data_dir_saves_an_unambiguous_artifact_path(self):
        self.cfg.storage.data_dir = os.path.relpath(self.root / "data", Path.cwd())
        analyze_recording(self.cfg, self.store, self.id, runner=lambda *a, **kw: self.fake_result())
        artifact = self.store.latest_artifact(self.id, "analysis")
        self.assertTrue(Path(artifact["path"]).is_absolute())
        args = build_parser().parse_args(["show", str(self.id)])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, args.func(args, self.cfg))

    def test_runner_failure_records_backoff_and_exhaustion(self):
        stamp = "2026-01-02T03:04:05+00:00"
        runner = lambda *a, **kw: self.fake_result(ok=False)
        with mock.patch("phone2agent.store.timeutil.now_iso", return_value=stamp):
            with self.assertRaisesRegex(AnalysisError, "return code 7"):
                analyze_recording(self.cfg, self.store, self.id, runner=runner)
        row = self.store.get_recording(self.id)
        self.assertEqual("failed", row["status"])
        self.assertEqual("analyze", row["failed_stage"])
        self.assertEqual(1, row["attempts"])
        self.assertEqual(datetime.fromisoformat(stamp) + timedelta(minutes=5),
                         datetime.fromisoformat(row["next_try_at"]))
        self.assertIsNone(self.store.latest_artifact(self.id, "analysis"))
        self.store.update_recording(self.id, status="transcribed")
        self.cfg.pipeline.max_attempts = 2
        with self.assertRaises(AnalysisError):
            analyze_recording(self.cfg, self.store, self.id, runner=runner)
        self.assertIsNone(self.store.get_recording(self.id)["next_try_at"])

    def test_missing_recording_and_wrong_status_preserve_state(self):
        runner = mock.Mock()
        with self.assertRaisesRegex(AnalysisError, "not found"):
            analyze_recording(self.cfg, self.store, 999, runner=runner)
        self.store.update_recording(self.id, status="downloaded")
        with self.assertRaisesRegex(AnalysisError, "transcribed"):
            analyze_recording(self.cfg, self.store, self.id, runner=runner)
        self.assertEqual("downloaded", self.store.get_recording(self.id)["status"])
        runner.assert_not_called()

    def test_missing_transcript_and_invalid_json_mark_failed(self):
        for content in (None, "not json"):
            with self.subTest(content=content):
                self.store.update_recording(self.id, status="transcribed")
                if content is None:
                    self.transcript_path.unlink()
                else:
                    self.transcript_path.write_text(content, encoding="utf-8")
                runner = mock.Mock()
                with self.assertRaises(AnalysisError):
                    analyze_recording(self.cfg, self.store, self.id, runner=runner)
                self.assertEqual("failed", self.store.get_recording(self.id)["status"])
                runner.assert_not_called()

    def test_no_transcript_artifact_marks_failed(self):
        recording_id = self.store.add_recording("calls", "other.wav", 1, 2, status="transcribed")[0]
        with self.assertRaisesRegex(AnalysisError, "no transcript artifact"):
            analyze_recording(self.cfg, self.store, recording_id)
        self.assertEqual("failed", self.store.get_recording(recording_id)["status"])

    def test_schema_invalid_runner_and_runner_exception(self):
        for runner in (lambda *a, **kw: self.fake_result({"title": "invalid"}),
                       mock.Mock(side_effect=OSError("could not run"))):
            with self.subTest(runner=runner):
                self.store.update_recording(self.id, status="transcribed")
                with self.assertRaises(AnalysisError):
                    analyze_recording(self.cfg, self.store, self.id, runner=runner)
                self.assertEqual("failed", self.store.get_recording(self.id)["status"])
                self.assertFalse((self.cfg.data_dir / "analyses" / f"{self.id}.json").exists())

    def test_atomic_write_failure_cleans_temporary_and_marks_failed(self):
        with mock.patch("phone2agent.analyze.os.replace", side_effect=OSError("replace failed")):
            with self.assertRaisesRegex(AnalysisError, "replace failed"):
                analyze_recording(self.cfg, self.store, self.id,
                                  runner=lambda *a, **kw: self.fake_result())
        self.assertEqual([], list((self.cfg.data_dir / "analyses").iterdir()))
        self.assertIsNone(self.store.latest_artifact(self.id, "analysis"))

    def test_cli_registration_analyze_print_show_and_errors(self):
        parser = build_parser()
        parser.parse_args(["projects", "--json"])
        args = parser.parse_args(["analyze", str(self.id), "--print"])
        output = io.StringIO()
        with mock.patch("phone2agent.analyze.analyze_recording", return_value=canned_analysis()), \
                contextlib.redirect_stdout(output):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual(canned_analysis(), json.loads(output.getvalue()))
        analyze_recording(self.cfg, self.store, self.id, runner=lambda *a, **kw: self.fake_result())
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            args = parser.parse_args(["show", str(self.id)])
            self.assertEqual(0, args.func(args, self.cfg))
        for text in ("Artifacts:", "transcript:", "analysis:", "Call about the demo",
                     "The demo needs review.", "[todo] Review the demo."):
            self.assertIn(text, output.getvalue())
        for argv in (["show", "999"], ["analyze", str(self.id)]):
            with mock.patch("phone2agent.cli.config.load", return_value=self.cfg), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, main(argv))


if __name__ == "__main__":
    unittest.main()
