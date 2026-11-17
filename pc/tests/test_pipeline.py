"""Offline pipeline integration, retry, daemon, and diagnostic coverage."""
from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from phone2agent import cli, config, pipeline
from phone2agent.agent import run_agent
from phone2agent.analyze import analyze_recording
from phone2agent.dispatch import dispatch_recording
from phone2agent.phone import PhoneClient
from phone2agent.store import Store
from tests.fakephone import FakePhone


def transcript(text="Review the project plan."):
    return {"backend": "command", "model": "fake", "language": "en", "duration_s": 12.5,
            "text": text, "segments": [{"id": 0, "start": 0.0, "end": 12.5,
                                        "speaker": "S1", "text": text}] if text else []}


def analysis():
    return {"title": "Project planning", "summary": "Review the project plan.",
            "participants": [], "items": [],
            "routes": [{"project": "demo", "confidence": 0.95,
                        "reason": "Project named in call", "notes_markdown": "Review the plan."}],
            "actions": [{"title": "Review plan", "project": "demo",
                         "instructions": "Review the project plan.", "reason": "Owner approval"}]}


def git(path, *args):
    return subprocess.run(["git", "-C", str(path), *args], check=True,
                          capture_output=True, text=True,
                          env={**os.environ, "GIT_TERMINAL_PROMPT": "0"}).stdout.strip()


class PipelineTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.projects = self.root / "projects"
        self.project = self.projects / "demo"
        self.project.mkdir(parents=True)
        self.cfg = config.Config(storage=config.StorageConfig(str(self.root / "data")),
                                 projects=config.ProjectsConfig(roots=[str(self.projects)], auto_push=False))
        self.cfg.path = self.root / "config.toml"
        self.cfg.path.write_text("", encoding="utf-8")
        self.store = Store.open(self.cfg)
        self.addCleanup(self.store.close)
        self.client = mock.Mock()

    def recording(self, status="downloaded", **fields):
        count = len(self.store.list_recordings())
        audio = self.root / f"audio-{count}.wav"
        audio.write_bytes(b"fake audio")
        return self.store.add_recording("calls", f"calls/{count}.wav", 10, count,
                                        local_path=str(audio), status=status, **fields)[0]

    def fake_stage(self, status, calls=None):
        def advance(cfg, store, recording_id):
            if calls is not None:
                calls.append((status, recording_id))
            store.update_recording(recording_id, status=status)
        return advance

    def run_pass(self, **kwargs):
        defaults = {"transcribe_fn": self.fake_stage("transcribed"),
                    "analyze_fn": self.fake_stage("analyzed"),
                    "dispatch_fn": self.fake_stage("filed"),
                    "run_approved_fn": mock.Mock(return_value=[])}
        defaults.update(kwargs)
        with mock.patch("phone2agent.pipeline.sync.sync_once", return_value={"downloaded": 0, "errors": 0}):
            return pipeline.run_once(self.cfg, self.store, self.client, **defaults)

    def test_transcribe_writes_contract_and_metadata(self):
        recording_id = self.recording()
        with mock.patch("phone2agent.pipeline.asr.transcribe", return_value=transcript()) as transcribe:
            pipeline.transcribe_recording(self.cfg, self.store, recording_id)
        row = self.store.get_recording(recording_id)
        self.assertEqual(("transcribed", 12.5), (row["status"], row["duration_s"]))
        transcribe.assert_called_once_with(Path(row["local_path"]), self.cfg.asr)
        artifact = self.store.latest_artifact(recording_id, "transcript")
        self.assertEqual(transcript(), json.loads(Path(artifact["path"]).read_text()))
        self.assertEqual({"backend": "command", "model": "fake", "speakers": 1, "duration_s": 12.5},
                         json.loads(artifact["meta"]))
        self.assertIn("[00:00] S1: Review", Path(artifact["path"]).with_suffix(".md").read_text())

    def test_transcribe_preserves_known_duration_and_counts_distinct_speakers(self):
        recording_id = self.recording(duration_s=0)
        data = transcript()
        data["segments"] += [{**data["segments"][0], "id": 1},
                             {**data["segments"][0], "id": 2, "speaker": None}]
        with mock.patch("phone2agent.pipeline.asr.transcribe", return_value=data):
            pipeline.transcribe_recording(self.cfg, self.store, recording_id)
        self.assertEqual(0, self.store.get_recording(recording_id)["duration_s"])
        self.assertEqual(1, json.loads(self.store.latest_artifact(recording_id, "transcript")["meta"])["speakers"])

    def test_silence_skips_without_analysis_or_failure(self):
        recording_id = self.recording()
        analyze = mock.Mock()
        with mock.patch("phone2agent.pipeline.asr.transcribe", return_value=transcript("")):
            result = self.run_pass(transcribe_fn=pipeline.transcribe_recording, analyze_fn=analyze)
        row = self.store.get_recording(recording_id)
        self.assertEqual(("skipped", 0, None), (row["status"], row["attempts"], row["error"]))
        self.assertEqual((1, 0), (result["skipped"], result["errors"]))
        analyze.assert_not_called()
        self.assertTrue(any("silence" in row["message"] for row in self.store.recent_events(10)))

    def test_transcribe_error_is_marked_only_once(self):
        recording_id = self.recording()
        with mock.patch("phone2agent.pipeline.asr.transcribe", side_effect=RuntimeError("decoder failed")):
            result = self.run_pass(transcribe_fn=pipeline.transcribe_recording)
        row = self.store.get_recording(recording_id)
        self.assertEqual(("failed", "transcribe", 1), (row["status"], row["failed_stage"], row["attempts"]))
        self.assertIn("decoder failed", row["error"])
        self.assertEqual(1, result["errors"])

    def test_full_pass_phone_to_committed_note_and_pending_task(self):
        git(self.project, "init", "-q")
        git(self.project, "config", "user.name", "Test Author")
        git(self.project, "config", "user.email", "author@example.invalid")
        git(self.project, "config", "commit.gpgsign", "false")
        phone_root = self.root / "phone"
        calls = phone_root / "calls"
        calls.mkdir(parents=True)
        (calls / "call.wav").write_bytes(b"fake audio")
        self.cfg.sources = [config.SourceConfig("calls", "calls", min_age_s=0)]
        asr_script = self.root / "asr.py"
        asr_script.write_text("import json, pathlib, sys\n"
                              "assert pathlib.Path(sys.argv[1]).read_bytes() == b'fake audio'\n"
                              f"pathlib.Path(sys.argv[2]).write_text({json.dumps(transcript())!r})\n")
        self.cfg.asr = config.AsrConfig(backend="command", command=[
            sys.executable, str(asr_script), "{input}", "{output}"])
        canned_analysis = self.root / "analysis.json"
        canned_analysis.write_text(json.dumps(analysis()))
        canned_filing = self.root / "filing.json"
        canned_filing.write_text(json.dumps({"files": ["notes/plan.md"],
                                             "commit_message": "Record project plan", "report": "Filed plan"}))
        self.cfg.agent = config.AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}",
            "--schema", "{schema_file}", "--cwd", "{cwd}", "--mode", "{mode}"])

        def runner(agent_cfg, prompt, **kwargs):
            canned = canned_analysis if kwargs["mode"] == "read-only" else canned_filing
            if kwargs["mode"] == "workspace-write":
                note = kwargs["cwd"] / "notes/plan.md"
                note.parent.mkdir(exist_ok=True)
                note.write_text("Review the plan.\n")
            return run_agent(dataclasses.replace(agent_cfg, command=[*agent_cfg.command, "--canned", str(canned)]),
                             prompt, **kwargs)

        with FakePhone(phone_root) as phone, mock.patch("phone2agent.pipeline.notify") as notify:
            self.cfg.phone = config.PhoneConfig(url=phone.url, token=phone.token)
            client = PhoneClient(self.cfg.phone)
            result = pipeline.run_once(self.cfg, self.store, client,
                analyze_fn=lambda c, s, r: analyze_recording(c, s, r, runner=runner),
                dispatch_fn=lambda c, s, r: dispatch_recording(c, s, r, runner=runner))
            self.assertEqual(["filed"], [row["status"] for row in self.store.list_recordings()])
            self.assertEqual((1, 1, 1, 1, 1, 0), tuple(result[key] for key in (
                "downloaded", "transcribed", "analyzed", "filed", "pending_tasks", "errors")))
            self.assertEqual("notes/plan.md", git(self.project, "show", "--pretty=format:", "--name-only", "HEAD"))
            self.assertEqual("", git(self.project, "status", "--porcelain"))
            task = self.store.list_tasks()[0]
            self.assertEqual("pending", task["status"])
            self.assertEqual(2, notify.call_count)
            bodies = "\n".join(call.args[2] for call in notify.call_args_list)
            for text in ("Project planning", "demo", "commit=", "pushed=no", "Review plan",
                         f"p2a tasks approve {task['id']}"):
                self.assertIn(text, bodies)
            notify.reset_mock()
            again = pipeline.run_once(self.cfg, self.store, client)
            self.assertEqual(0, again["filed"])
            self.assertEqual(1, len(self.store.list_tasks()))
            notify.assert_not_called()

    def test_due_retry_respects_backoff_and_resumes_analysis(self):
        recording_id = self.recording()
        attempts = []

        def flaky(cfg, store, recording_id):
            attempts.append(recording_id)
            if len(attempts) == 1:
                raise RuntimeError("agent temporarily unavailable")
            store.update_recording(recording_id, status="analyzed")

        first = self.run_pass(analyze_fn=flaky)
        failed = self.store.get_recording(recording_id)
        self.assertEqual(("analyze", 1, 1), (failed["failed_stage"], failed["attempts"], first["errors"]))
        due = datetime.fromisoformat(failed["next_try_at"])
        unused = mock.Mock(side_effect=AssertionError("must not retranscribe"))
        self.run_pass(now=due - timedelta(seconds=1), transcribe_fn=unused, analyze_fn=flaky)
        self.assertEqual(1, len(attempts))
        last = self.run_pass(now=due, transcribe_fn=unused, analyze_fn=flaky)
        unused.assert_not_called()
        self.assertEqual(2, len(attempts))
        self.assertEqual((1, 1, 0), (last["analyzed"], last["filed"], last["errors"]))
        self.assertEqual("filed", self.store.get_recording(recording_id)["status"])

    def test_repeated_retry_exhausts_attempts_and_notifies_once(self):
        recording_id = self.recording("transcribed")
        self.cfg.pipeline.max_attempts = 2
        fail = mock.Mock(side_effect=RuntimeError("agent unavailable"))
        with mock.patch("phone2agent.pipeline.notify") as notify:
            self.run_pass(analyze_fn=fail)
            notify.assert_not_called()
            due = self.store.get_recording(recording_id)["next_try_at"]
            self.run_pass(analyze_fn=fail, now=due)
            row = self.store.get_recording(recording_id)
            self.assertEqual((2, None), (row["attempts"], row["next_try_at"]))
            notify.assert_called_once()
            self.assertIn("permanently", notify.call_args.args[1])
            self.assertIn("agent unavailable", notify.call_args.args[2])
            self.run_pass(analyze_fn=fail, now=datetime.now().astimezone() + timedelta(days=1))
            self.assertEqual(2, fail.call_count)
            notify.assert_called_once()

    def test_retry_dispatch_does_not_repeat_transcribe_or_analyze(self):
        recording_id = self.recording("analyzed")
        row = self.store.mark_failed(recording_id, "dispatch", "agent busy", 3)
        unused = mock.Mock(side_effect=AssertionError("wrong stage"))
        dispatch = mock.Mock(side_effect=self.fake_stage("filed"))
        self.run_pass(now=row["next_try_at"], transcribe_fn=unused, analyze_fn=unused, dispatch_fn=dispatch)
        unused.assert_not_called()
        dispatch.assert_called_once_with(self.cfg, self.store, recording_id)

    def test_failure_does_not_starve_other_recordings_or_approved_tasks(self):
        first, second = self.recording(), self.recording()
        task_id = self.store.add_task("Approved work", "Do it", status="approved")

        def transcribe(cfg, store, recording_id):
            if recording_id == first:
                raise OSError("bad recording")
            store.update_recording(recording_id, status="transcribed")

        def approved(cfg, store):
            self.assertEqual("filed", store.get_recording(second)["status"])
            store.update_task(task_id, status="done")
            return [task_id]

        result = self.run_pass(transcribe_fn=transcribe, run_approved_fn=approved)
        self.assertEqual((1, 1, 1), (result["filed"], result["tasks_run"], result["errors"]))
        self.assertEqual("done", self.store.get_task(task_id)["status"])

    def test_stage_order_and_each_recording_advances_in_same_pass(self):
        analyzed = self.recording("analyzed")
        transcribed = self.recording("transcribed")
        downloaded = self.recording("downloaded")
        calls = []
        self.run_pass(transcribe_fn=self.fake_stage("transcribed", calls),
                      analyze_fn=self.fake_stage("analyzed", calls), dispatch_fn=self.fake_stage("filed", calls))
        self.assertEqual([("transcribed", downloaded), ("analyzed", downloaded), ("filed", downloaded),
                          ("analyzed", transcribed), ("filed", transcribed), ("filed", analyzed)], calls)

    def test_stage_returning_failed_without_exception_is_counted(self):
        recording_id = self.recording("analyzed")
        self.cfg.pipeline.max_attempts = 1
        with mock.patch("phone2agent.pipeline.notify") as notify:
            result = self.run_pass(dispatch_fn=lambda c, s, r: s.mark_failed(r, "dispatch", "all routes failed", 1))
        self.assertEqual(1, result["errors"])
        self.assertEqual(1, self.store.get_recording(recording_id)["attempts"])
        notify.assert_called_once()

    def test_filing_notification_uses_latest_result_for_each_project(self):
        recording_id = self.recording("analyzed")
        for index, error in enumerate(("old filing failure", None)):
            path = self.root / f"filing-{index}.json"
            path.write_text(json.dumps({"project": "demo", "commit": "abc" if index else None,
                                        "pushed": bool(index), "error": error}))
            self.store.add_artifact(recording_id, "filing", path)
        with mock.patch("phone2agent.pipeline.notify") as notify:
            self.run_pass()
        body = notify.call_args.args[2]
        self.assertEqual(1, body.count("demo:"))
        self.assertIn("commit=abc, pushed=yes", body)
        self.assertNotIn("old filing failure", body)

    def test_notification_preparation_error_does_not_stop_next_recording(self):
        self.recording("analyzed")
        self.recording("analyzed")
        stderr = io.StringIO()
        with mock.patch.object(self.store, "latest_artifact", side_effect=RuntimeError("artifact unavailable")), \
                contextlib.redirect_stderr(stderr):
            result = self.run_pass()
        self.assertEqual(2, result["filed"])
        self.assertIn("artifact unavailable", stderr.getvalue())

    def test_sync_exception_does_not_prevent_local_processing(self):
        self.recording("analyzed")
        with mock.patch("phone2agent.pipeline.sync.sync_once", side_effect=OSError("phone down")):
            result = pipeline.run_once(self.cfg, self.store, self.client,
                                      dispatch_fn=self.fake_stage("filed"))
        self.assertEqual((1, 1), (result["filed"], result["errors"]))

    def test_download_failure_notification(self):
        recording_id = self.recording("discovered")
        self.cfg.pipeline.max_attempts = 1

        def sync(cfg, store, client):
            store.mark_failed(recording_id, "download", "download corrupt", 1)
            return {"downloaded": 0, "errors": 0}

        with mock.patch("phone2agent.pipeline.sync.sync_once", side_effect=sync), \
                mock.patch("phone2agent.pipeline.notify") as notify:
            result = pipeline.run_once(self.cfg, self.store, self.client)
        self.assertEqual(1, result["errors"])
        self.assertIn("download", notify.call_args.args[2])

    def test_status_and_retry_cli(self):
        recording_id = self.recording("transcribed")
        self.store.mark_failed(recording_id, "analyze", "temporary error", 5)
        self.store.add_task("Review", "Instructions")
        sync_event = self.store.log_event("info", "Sync completed: {}")
        for i in range(12):
            self.store.log_event("info", f"Event {i}")
        parser = cli.build_parser()
        args = parser.parse_args(["status", "--json"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, args.func(args, self.cfg))
        data = json.loads(output.getvalue())
        self.assertEqual({"failed": 1}, data["counts"])
        self.assertEqual(1, data["pending_tasks"])
        self.assertEqual(10, len(data["events"]))
        self.assertEqual("Event 11", data["events"][0]["message"])
        self.assertEqual("analyze", data["failed"][0]["failed_stage"])
        self.assertEqual(self.store.conn.execute("SELECT at FROM events WHERE id=?", (sync_event,)).fetchone()[0],
                         data["last_sync"])
        args = parser.parse_args(["status"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, args.func(args, self.cfg))
        for text in ("stage=analyze", "temporary error", "next try=", "Pending tasks: 1"):
            self.assertIn(text, output.getvalue())
        args = parser.parse_args(["retry", str(recording_id)])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, args.func(args, self.cfg))
        row = self.store.get_recording(recording_id)
        self.assertEqual(("transcribed", 0, None), (row["status"], row["attempts"], row["next_try_at"]))
        args = parser.parse_args(["retry", "999"])
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, args.func(args, self.cfg))

    def test_lock_contention_run_and_daemon_exit_three(self):
        args = cli.build_parser().parse_args(["run", "--json"])
        with pipeline.single_instance(self.cfg), mock.patch("phone2agent.pipeline.run_once") as run:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                self.assertEqual(3, args.func(args, self.cfg))
                self.assertEqual(3, pipeline.daemon(self.cfg, self.store, self.client))
            self.assertIn("already running", stderr.getvalue())
            run.assert_not_called()
            code = ("from phone2agent import config, pipeline; import sys; "
                    "cfg=config.Config(storage=config.StorageConfig(sys.argv[1])); "
                    "sys.exit(pipeline.daemon(cfg))")
            child = subprocess.run([sys.executable, "-c", code, str(self.cfg.data_dir)],
                                   capture_output=True, text=True, timeout=5)
            self.assertEqual(3, child.returncode, child.stderr)
        with pipeline.single_instance(self.cfg):
            pass  # Release never unlinks the shared inode.

    def test_daemon_signals_finish_current_recording_and_restore_handlers(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signal=signum):
                first, second = self.recording(), self.recording()
                original = signal.getsignal(signum)

                def transcribe(cfg, store, recording_id):
                    self.assertEqual(first, recording_id)
                    signal.raise_signal(signum)
                    store.update_recording(recording_id, status="transcribed")

                def run(cfg, store, client, **kwargs):
                    return self.run_pass(transcribe_fn=transcribe, **kwargs)

                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(0, pipeline.daemon(self.cfg, self.store, self.client, run_once_fn=run))
                self.assertEqual("filed", self.store.get_recording(first)["status"])
                self.assertEqual("downloaded", self.store.get_recording(second)["status"])
                self.assertEqual(1, len(output.getvalue().splitlines()))
                self.assertIn("filed=1", output.getvalue())
                self.assertEqual(original, signal.getsignal(signum))
                self.store.update_recording(second, status="skipped")

    def test_daemon_waits_configured_interval_and_wakes_on_signal(self):
        self.cfg.sync.interval_s = 17
        wait = mock.Mock(side_effect=lambda timeout: signal.raise_signal(signal.SIGTERM))
        event = mock.Mock()
        event.is_set.side_effect = [False, True]
        event.wait = wait
        output = io.StringIO()
        with mock.patch("phone2agent.pipeline.threading.Event", return_value=event), \
                contextlib.redirect_stdout(output):
            self.assertEqual(0, pipeline.daemon(self.cfg, self.store, self.client,
                run_once_fn=lambda *a, **kw: {"filed": 0, "errors": 0}))
        wait.assert_called_once_with(17)
        event.set.assert_called_once()

    def test_run_cli_json_ignores_stage_stdout(self):
        args = cli.build_parser().parse_args(["run", "--json"])

        def run(*args):
            print("ASR progress")
            return {"sync": {}, "errors": 0, "filed": 1}

        output, error = io.StringIO(), io.StringIO()
        with mock.patch("phone2agent.pipeline.run_once", side_effect=run), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual(1, json.loads(output.getvalue())["filed"])
        self.assertIn("ASR progress", error.getvalue())

    def test_doctor_phone_up_down_missing_source_and_permissions(self):
        phone_root = self.root / "phone"
        phone_root.mkdir()
        self.cfg.sources = [config.SourceConfig("calls", ".")]
        self.cfg.asr = config.AsrConfig(backend="command", command=[sys.executable])
        self.cfg.agent = config.AgentConfig(kind="command", command=[sys.executable])
        with FakePhone(phone_root) as phone:
            self.cfg.phone = config.PhoneConfig(url=phone.url, token=phone.token, timeout_s=0.2)
            checks = pipeline.doctor(self.cfg)
            self.assertTrue(all(ok for _, ok, _ in checks), checks)
            ping = next(detail for name, _, detail in checks if name == "phone ping")
            self.assertIn("device=Fake Phone", ping)
            self.assertIn("all_files_access=True", ping)
            self.assertIn(("projects", True, "1 project(s)"), checks)
            self.cfg.sources.append(config.SourceConfig("missing", "missing"))
            self.assertIn(("source missing", False), [(name, ok) for name, ok, _ in pipeline.doctor(self.cfg)])
            with mock.patch("phone2agent.pipeline.PhoneClient.ping", return_value={
                    "device": "Fake Phone", "all_files_access": False}):
                self.assertIn(("phone ping", False), [(name, ok) for name, ok, _ in pipeline.doctor(self.cfg)])
        checks = pipeline.doctor(self.cfg)
        self.assertIn(("phone ping", False), [(name, ok) for name, ok, _ in checks])
        self.assertIn(("git", True), [(name, ok) for name, ok, _ in checks])

    def test_doctor_imports_only_and_reports_cuda(self):
        self.cfg.asr = config.AsrConfig(backend="funasr", device="cuda")
        torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False))
        imports = mock.Mock(side_effect=lambda name: torch if name == "torch" else SimpleNamespace())
        with mock.patch("phone2agent.pipeline.importlib.import_module", imports):
            checks = pipeline.doctor(self.cfg)
        self.assertIn(("ASR backend", True), [(name, ok) for name, ok, _ in checks])
        self.assertIn(("CUDA", False), [(name, ok) for name, ok, _ in checks])
        self.assertEqual({"funasr", "torch", "torchaudio", "av", "numpy"},
                         {call.args[0] for call in imports.call_args_list})
        self.cfg.asr.device = "auto"
        with mock.patch("phone2agent.pipeline.importlib.import_module", imports):
            self.assertIn(("CUDA", True), [(name, ok) for name, ok, _ in pipeline.doctor(self.cfg)])
        self.cfg.asr.backend = "faster-whisper"
        ctr = SimpleNamespace(get_cuda_device_count=lambda: 1)
        with mock.patch("phone2agent.pipeline.importlib.import_module", return_value=ctr):
            self.assertIn(("CUDA", True), [(name, ok) for name, ok, _ in pipeline.doctor(self.cfg)])

    def test_doctor_missing_dependencies_and_malformed_config_cli(self):
        self.cfg.notify.command = [str(self.root / "missing-notifier")]
        self.cfg.agent.binary = str(self.root / "missing-agent")
        with mock.patch("phone2agent.pipeline.importlib.import_module", side_effect=ImportError("ASR missing")):
            checks = pipeline.doctor(self.cfg)
        for name in ("ASR backend", "agent", "notify", "phone paired"):
            self.assertIn((name, False), [(name, ok) for name, ok, _ in checks])
        self.cfg.asr.backend = "none"
        self.cfg.path.write_text("[invalid")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, cli.main(["--config", str(self.cfg.path), "doctor", "--json"]))
        result = json.loads(output.getvalue())
        self.assertEqual(("config", False), (result[0]["check"], result[0]["ok"]))
        args = cli.build_parser().parse_args(["doctor"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, args.func(args, self.cfg))
        self.assertIn("✗ config", output.getvalue())


if __name__ == "__main__":
    unittest.main()
