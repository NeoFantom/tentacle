from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from phone2agent.agent import AgentResult, run_agent
from phone2agent.config import AgentConfig, Config, ProjectsConfig, StorageConfig
from phone2agent.store import Store


def git(path, *args):
    return subprocess.run(["git", "-C", str(path), *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                          env={**os.environ, "GIT_TERMINAL_PROMPT": "0"}).stdout.strip()


def init_repo(path):
    git(path, "init", "-q")
    git(path, "config", "user.name", "Test Author")
    git(path, "config", "user.email", "author@example.invalid")
    git(path, "config", "commit.gpgsign", "false")
    for name in ("dirty.txt", "staged.txt"):
        (path / name).write_text("initial\n", encoding="utf-8")
    git(path, "add", "--", "dirty.txt", "staged.txt")
    git(path, "commit", "-qm", "Initial")


class TaskTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "projects" / "demo"
        self.project.mkdir(parents=True)
        self.cfg = Config(
            storage=StorageConfig(data_dir=str(self.root / "data")),
            projects=ProjectsConfig(roots=[str(self.project.parent)], auto_commit=False,
                                    auto_push=False),
        )
        self.cfg.analyze.owner_name = "Owner"
        self.cfg.analyze.language = "English"
        self.store = Store.open(self.cfg)
        self.addCleanup(self.store.close)
        self.recording_id = self.store.add_recording(
            "calls", "call.wav", 10, 20, status="analyzed", app="phone",
            counterpart="Colleague", started_at="2026-04-05T10:00:00+08:00",
            duration_s=45,
        )[0]
        analysis = self.cfg.data_dir / "analyses" / f"{self.recording_id}.json"
        analysis.parent.mkdir(parents=True)
        analysis.write_text(json.dumps({"summary": "Discussed the demo."}), encoding="utf-8")
        self.store.add_artifact(self.recording_id, "analysis", analysis)

    def add_task(self, *, status="pending", project="demo", recording=True):
        return self.store.add_task(
            "Build demo", "Implement and verify it.",
            recording_id=self.recording_id if recording else None,
            project=project, reason="The call requested it.", status=status,
        )

    @staticmethod
    def successful_result(project="demo"):
        return ({"project": project, "files": ["result.txt"], "commit": None,
                 "pushed": False, "report": "Implemented and tested.", "error": None}, True)

    def test_approve_and_reject_only_pending_tasks(self):
        from phone2agent.tasks import TaskError, approve, reject

        approved = self.add_task()
        rejected = self.add_task(project=None)
        approve(self.cfg, self.store, approved, "Use the small design")
        reject(self.cfg, self.store, rejected, "No longer needed")
        self.assertEqual("approved", self.store.get_task(approved)["status"])
        self.assertEqual("Use the small design", self.store.get_task(approved)["owner_note"])
        self.assertEqual("rejected", self.store.get_task(rejected)["status"])
        self.assertEqual("No longer needed", self.store.get_task(rejected)["owner_note"])
        with self.assertRaisesRegex(TaskError, "pending"):
            approve(self.cfg, self.store, approved)
        with self.assertRaisesRegex(TaskError, "not found"):
            reject(self.cfg, self.store, 999)

    def test_run_project_task_renders_context_and_persists_results(self):
        from phone2agent.tasks import run_task

        task_id = self.add_task(status="approved")
        captured = {}

        def work(cfg, project, prompt, **kwargs):
            captured.update(project=project, prompt=prompt, kwargs=kwargs)
            return self.successful_result()

        with mock.patch("phone2agent.tasks._run_work", side_effect=work):
            result = run_task(self.cfg, self.store, task_id, runner=mock.sentinel.runner)

        self.assertIsNone(result["error"])
        self.assertEqual("done", self.store.get_task(task_id)["status"])
        self.assertEqual(self.project.resolve(), captured["kwargs"]["cwd"])
        self.assertEqual("task", captured["kwargs"]["kind"])
        self.assertEqual(task_id, captured["kwargs"]["identifier"])
        self.assertIs(mock.sentinel.runner, captured["kwargs"]["runner"])
        for text in ("Build demo", "Implement and verify it.", "Discussed the demo.",
                     "counterpart: Colleague", "Owner", "English", "`demo`", "none"):
            self.assertIn(text, captured["prompt"])
        self.assertNotIn("{{", captured["prompt"])
        json_path = self.cfg.data_dir / "tasks" / f"{task_id}.json"
        self.assertEqual(result, json.loads(json_path.read_text(encoding="utf-8")))
        markdown = json_path.with_suffix(".md").read_text(encoding="utf-8")
        self.assertIn("# Task", markdown)
        self.assertIn("Implemented and tested.", markdown)
        self.assertEqual(str(json_path.resolve()), self.store.get_task(task_id)["result_path"])

    def test_null_and_unknown_project_use_scratch_workspace(self):
        from phone2agent.tasks import run_task

        for project, expected in ((None, "task has no project"),
                                  ("missing", "unknown project missing")):
            with self.subTest(project=project):
                task_id = self.add_task(status="approved", project=project, recording=False)
                captured = {}

                def work(cfg, resolved, prompt, **kwargs):
                    captured.update(project=resolved, prompt=prompt, cwd=kwargs["cwd"])
                    return self.successful_result(None)

                with mock.patch("phone2agent.tasks._run_work", side_effect=work):
                    run_task(self.cfg, self.store, task_id)
                scratch = (self.cfg.data_dir / "workspaces" / f"task-{task_id}").resolve()
                self.assertIsNone(captured["project"])
                self.assertEqual(scratch, captured["cwd"])
                self.assertTrue(scratch.is_dir())
                self.assertIn(expected, captured["prompt"])
                self.assertIn("No recording context", captured["prompt"])

    def test_work_error_is_saved_and_task_reaches_failed(self):
        from phone2agent.tasks import run_task

        task_id = self.add_task(status="approved")
        failed = ({"project": "demo", "files": [], "commit": None, "pushed": False,
                   "report": "Agent stopped.", "error": "agent failed"}, False)
        with mock.patch("phone2agent.tasks._run_work", return_value=failed):
            result = run_task(self.cfg, self.store, task_id)
        self.assertEqual("agent failed", result["error"])
        self.assertEqual("failed", self.store.get_task(task_id)["status"])
        markdown = (self.cfg.data_dir / "tasks" / f"{task_id}.md").read_text()
        self.assertIn("## Error", markdown)
        self.assertIn("agent failed", markdown)
        self.assertIn("Task failed", self.store.recent_events(1)[0]["message"])

    def test_unexpected_work_exception_does_not_leave_task_running(self):
        from phone2agent.tasks import run_task

        task_id = self.add_task(status="approved")
        with mock.patch("phone2agent.tasks._run_work", side_effect=OSError("runner broke")):
            result = run_task(self.cfg, self.store, task_id)
        self.assertEqual("runner broke", result["error"])
        self.assertEqual("failed", self.store.get_task(task_id)["status"])

    def test_setup_failure_does_not_leave_task_running(self):
        from phone2agent.tasks import run_task

        task_id = self.add_task(status="approved")
        with mock.patch("phone2agent.tasks.get_project", side_effect=OSError("registry broke")):
            result = run_task(self.cfg, self.store, task_id)
        self.assertEqual("registry broke", result["error"])
        self.assertEqual("failed", self.store.get_task(task_id)["status"])

    def test_two_connections_only_one_can_claim_and_run_approved_task(self):
        from phone2agent.tasks import TaskError, run_task

        task_id = self.add_task(status="approved", project=None, recording=False)
        barrier = threading.Barrier(2)
        lock = threading.Lock()
        runner_calls = []
        outcomes = []

        def runner(agent_cfg, prompt, **kwargs):
            data = {"files": [], "commit_message": "", "report": "done"}
            with lock:
                runner_calls.append(threading.current_thread().name)
            return AgentResult(True, json.dumps(data), data, 0, kwargs["log_path"], 0.0)

        def worker():
            with Store.open(self.cfg) as connection:
                barrier.wait()
                try:
                    run_task(self.cfg, connection, task_id, runner=runner)
                    outcome = "ran"
                except TaskError:
                    outcome = "lost"
            with lock:
                outcomes.append(outcome)

        threads = [threading.Thread(target=worker, name=f"worker-{index}") for index in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())

        self.assertEqual(1, len(runner_calls))
        self.assertCountEqual(["ran", "lost"], outcomes)
        self.assertEqual("done", self.store.get_task(task_id)["status"])

    def test_run_task_requires_approved_and_run_approved_uses_snapshot_order(self):
        from phone2agent.tasks import TaskError, run_approved, run_task

        pending = self.add_task()
        first = self.add_task(status="approved")
        second = self.add_task(status="approved", project=None)
        with self.assertRaisesRegex(TaskError, "approved"):
            run_task(self.cfg, self.store, pending)
        failed = ({"project": "demo", "files": [], "commit": None, "pushed": False,
                   "report": "Stopped.", "error": "agent failed"}, False)
        with mock.patch("phone2agent.tasks._run_work",
                        side_effect=[failed, self.successful_result(None)]):
            self.assertEqual([first, second], run_approved(self.cfg, self.store))
        self.assertEqual("pending", self.store.get_task(pending)["status"])
        self.assertEqual("failed", self.store.get_task(first)["status"])
        self.assertEqual("done", self.store.get_task(second)["status"])

    def test_command_agent_runs_in_scratch_workspace(self):
        from phone2agent.tasks import run_task

        canned = self.root / "result.json"
        canned.write_text(json.dumps({"files": [], "commit_message": "",
                                      "report": "Nothing to change."}), encoding="utf-8")
        capture = self.root / "capture.json"
        self.cfg.agent = AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}",
            "--canned", str(canned), "--capture", str(capture),
            "--mode", "{mode}", "--cwd", "{cwd}",
        ])
        task_id = self.add_task(status="approved", project=None, recording=False)
        result = run_task(self.cfg, self.store, task_id)
        invocation = json.loads(capture.read_text(encoding="utf-8"))
        self.assertIsNone(result["error"])
        self.assertEqual("workspace-write", invocation["mode"])
        self.assertEqual(str(self.cfg.data_dir / "workspaces" / f"task-{task_id}"),
                         invocation["cwd"])

    def test_command_agent_project_commit_preserves_preexisting_work(self):
        from phone2agent.tasks import approve, run_task

        self.cfg.projects.auto_commit = True
        init_repo(self.project)
        (self.project / "dirty.txt").write_text("owner dirty\n", encoding="utf-8")
        (self.project / "staged.txt").write_text("owner staged\n", encoding="utf-8")
        git(self.project, "add", "--", "staged.txt")
        canned = self.root / "project-result.json"
        canned.write_text(json.dumps({
            "files": ["new.txt", "dirty.txt", "staged.txt"],
            "commit_message": "Complete approved task", "report": "Task complete.",
        }), encoding="utf-8")
        capture = self.root / "project-capture.json"
        self.cfg.agent = AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}",
            "--schema", "{schema_file}", "--cwd", "{cwd}", "--mode", "{mode}",
            "--canned", str(canned), "--capture", str(capture),
        ])
        task_id = self.add_task()
        approve(self.cfg, self.store, task_id, "Keep the public API small")

        def writer(*args, **kwargs):
            (kwargs["cwd"] / "new.txt").write_text("agent result\n", encoding="utf-8")
            (kwargs["cwd"] / "dirty.txt").write_text("agent touched dirty\n", encoding="utf-8")
            return run_agent(*args, **kwargs)

        result = run_task(self.cfg, self.store, task_id, runner=writer)
        self.assertIsNone(result["error"])
        self.assertEqual(["new.txt"], result["files"])
        self.assertIn("Skipped pre-existing dirty file: dirty.txt", result["report"])
        self.assertIn("Skipped pre-existing dirty file: staged.txt", result["report"])
        self.assertEqual("new.txt", git(self.project, "show", "--pretty=format:",
                                         "--name-only", "HEAD"))
        self.assertEqual("staged.txt", git(self.project, "diff", "--cached", "--name-only"))
        self.assertEqual("initial", git(self.project, "show", "HEAD:staged.txt"))
        prompt = json.loads(capture.read_text(encoding="utf-8"))["prompt"]
        self.assertIn("Keep the public API small", prompt)
        markdown = (self.cfg.data_dir / "tasks" / f"{task_id}.md").read_text()
        self.assertIn("Agent log:", markdown)
        self.assertIn(f"-task-{task_id}.log", markdown)

    def test_invalid_command_agent_result_fails_and_keeps_log(self):
        from phone2agent.tasks import run_task

        self.cfg.agent = AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}", "--invalid",
        ])
        task_id = self.add_task(status="approved", project=None, recording=False)
        result = run_task(self.cfg, self.store, task_id)
        self.assertIsNotNone(result["error"])
        self.assertEqual("failed", self.store.get_task(task_id)["status"])
        logs = list((self.cfg.data_dir / "logs" / "agent").glob(f"*-task-{task_id}.log"))
        self.assertEqual(1, len(logs))
        self.assertIn("Agent error", logs[0].read_text(encoding="utf-8"))

    def test_cli_registration_list_show_approve_reject_and_run(self):
        from phone2agent.tasks import add_commands

        parser = argparse.ArgumentParser()
        subparsers = parser.add_subparsers(dest="command", required=True)
        add_commands(subparsers)
        pending = self.add_task()

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            args = parser.parse_args(["tasks", "--json"])
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual(pending, json.loads(output.getvalue())[0]["id"])

        args = parser.parse_args(["tasks", "approve", str(pending), "-m", "Proceed"])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual("approved", self.store.get_task(pending)["status"])

        output = io.StringIO()
        args = parser.parse_args(["tasks", "show", str(pending)])
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual("Proceed", json.loads(output.getvalue())["owner_note"])

        reject_id = self.add_task()
        args = parser.parse_args(["tasks", "reject", str(reject_id)])
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual("rejected", self.store.get_task(reject_id)["status"])

        with mock.patch("phone2agent.tasks.run_task", return_value={"error": None}):
            args = parser.parse_args(["tasks", "run", str(pending)])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, args.func(args, self.cfg))

        now_id = self.add_task()
        with mock.patch("phone2agent.tasks.run_task", return_value={"error": "agent failed"}):
            args = parser.parse_args(["tasks", "approve", str(now_id), "--now"])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, args.func(args, self.cfg))


if __name__ == "__main__":
    unittest.main()
