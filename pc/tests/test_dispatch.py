from __future__ import annotations

import contextlib
import concurrent.futures
import copy
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

from phone2agent.agent import run_agent
from phone2agent.cli import build_parser
from phone2agent.config import AgentConfig, Config, ProjectsConfig, StorageConfig
from phone2agent.dispatch import DispatchError, _queue_actions, dispatch_recording, route_manually
from phone2agent.store import Store


def analysis():
    return {"title": "Plan the demo", "summary": "Review the demo before release.",
            "participants": [], "items": [],
            "routes": [{"project": "demo", "confidence": 0.9, "reason": "Named project",
                        "notes_markdown": "- Review the demo."}],
            "actions": [{"title": "Review demo", "project": "demo",
                         "instructions": "Review and test the demo.", "reason": "Owner approval"}]}


def git(path, *args):
    return subprocess.run(["git", "-C", str(path), *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                          env={**os.environ, "GIT_TERMINAL_PROMPT": "0"}).stdout.strip()


def init_repo(path):
    path.mkdir(parents=True, exist_ok=True)
    git(path, "init", "-q")
    git(path, "config", "user.name", "Test Author")
    git(path, "config", "user.email", "author@example.invalid")
    git(path, "config", "commit.gpgsign", "false")
    for name in ("existing.md", "staged.md"):
        (path / name).write_text("initial\n", encoding="utf-8")
    git(path, "add", "--", "existing.md", "staged.md")
    git(path, "commit", "-qm", "Initial")


class DispatchTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.project = self.root / "projects" / "demo"
        init_repo(self.project)
        self.cfg = Config(storage=StorageConfig(data_dir=str(self.root / "data")),
                          projects=ProjectsConfig(roots=[str(self.project.parent)], auto_push=False))
        self.cfg.analyze.owner_name = "Owner"
        self.cfg.analyze.language = "English"
        self.store = Store.open(self.cfg)
        self.addCleanup(self.store.close)
        self.id = self.store.add_recording("calls", "calls/demo.wav", 10, 1000,
            status="analyzed", started_at="2026-01-02T03:04:05+00:00", counterpart="Colleague")[0]
        self.analysis_path = self.cfg.data_dir / "analyses" / f"{self.id}.json"
        self.analysis_path.parent.mkdir()
        self.set_analysis(analysis())
        self.store.add_artifact(self.id, "analysis", f"analyses/{self.id}.json")
        self.canned = self.root / "canned.json"
        self.capture = self.root / "capture.json"
        self.cfg.agent = AgentConfig(kind="command", command=[
            sys.executable, str(Path(__file__).with_name("fake_agent.py")),
            "--prompt", "{prompt_file}", "--output", "{output_file}",
            "--schema", "{schema_file}", "--cwd", "{cwd}", "--mode", "{mode}",
            "--canned", str(self.canned), "--capture", str(self.capture)])
        self.set_result(["note.md"])

    def set_analysis(self, data):
        self.analysis_path.write_text(json.dumps(data), encoding="utf-8")

    def set_result(self, files, report="Filed notes"):
        self.canned.write_text(json.dumps({"files": files, "commit_message": "Record call notes",
                                           "report": report}), encoding="utf-8")

    def writer(self, changes=None):
        changes = {"note.md": "call notes\n"} if changes is None else changes
        def runner(*args, **kwargs):
            for name, text in changes.items():
                path = kwargs["cwd"] / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
            return run_agent(*args, **kwargs)
        return runner

    def dispatch(self, runner=None):
        return dispatch_recording(self.cfg, self.store, self.id,
                                  runner=self.writer() if runner is None else runner)

    def test_command_agent_commit_only_listed_files_and_prompt(self):
        result = self.dispatch(self.writer({"note.md": "notes", "unlisted.md": "unlisted"}))
        filing = result["filings"][0]
        self.assertIsNone(filing["error"])
        self.assertEqual(["note.md"], filing["files"])
        self.assertEqual("note.md", git(self.project, "show", "--pretty=format:", "--name-only", "HEAD"))
        self.assertEqual("?? unlisted.md", git(self.project, "status", "--porcelain"))
        self.assertEqual("filed", self.store.get_recording(self.id)["status"])
        self.assertEqual("pending", self.store.get_task(result["tasks"][0])["status"])
        artifact = self.store.latest_artifact(self.id, "filing")
        self.assertEqual(filing, json.loads(Path(artifact["path"]).read_text()))
        capture = json.loads(self.capture.read_text())
        self.assertEqual("workspace-write", capture["mode"])
        for text in ("Owner", "English", "Colleague", "2026-01-02-plan-the-demo.md", "- Review the demo."):
            self.assertIn(text, capture["prompt"])
        self.assertNotIn("{{", capture["prompt"])
        self.assertNotIn("but you may include it", capture["prompt"])
        self.assertIn("fake stdout", next((self.cfg.data_dir / "logs/agent").glob("*.log")).read_text())

    def test_pre_dirty_listed_and_unrelated_staged_are_preserved(self):
        (self.project / "existing.md").write_text("user change\n")
        (self.project / "staged.md").write_text("staged user change\n")
        git(self.project, "add", "--", "staged.md")
        self.set_result(["note.md", "existing.md", "staged.md"])
        result = self.dispatch(self.writer({"note.md": "notes", "existing.md": "agent appended\n"}))
        filing = result["filings"][0]
        self.assertEqual(["note.md"], filing["files"])
        self.assertIn("Skipped pre-existing dirty file: existing.md", filing["report"])
        self.assertIn("Skipped pre-existing dirty file: staged.md", filing["report"])
        self.assertEqual("staged.md", git(self.project, "diff", "--cached", "--name-only"))
        self.assertEqual("initial", git(self.project, "show", "HEAD:staged.md"))
        self.assertEqual("initial", git(self.project, "show", "HEAD:existing.md"))

    def test_pre_dirty_untracked_never_committed(self):
        (self.project / "note.md").write_text("user notes")
        filing = self.dispatch()["filings"][0]
        self.assertIsNone(filing["commit"])
        self.assertIn("pre-existing dirty", filing["report"])
        self.assertEqual("?? note.md", git(self.project, "status", "--porcelain"))

    def test_unsafe_path_prevents_partial_commit(self):
        self.set_result(["note.md", "../escape.md"])
        original = git(self.project, "rev-parse", "HEAD")
        result = self.dispatch()["filings"][0]
        self.assertIn("unsafe file path", result["error"])
        self.assertEqual(original, git(self.project, "rev-parse", "HEAD"))
        self.assertEqual("", git(self.project, "diff", "--cached", "--name-only"))
        self.assertEqual("failed", self.store.get_recording(self.id)["status"])

    def test_symlink_escape_is_rejected(self):
        (self.project / "outside").symlink_to(self.root, target_is_directory=True)
        self.set_result(["outside/data.md"])
        result = self.dispatch(self.writer({}))["filings"][0]
        self.assertIn("outside the project", result["error"])

    def test_below_threshold_and_unknown_go_to_inbox_with_cli(self):
        data = analysis()
        data["routes"][0]["confidence"] = 0.2
        data["routes"].append({**data["routes"][0], "project": "unknown", "confidence": 0.99})
        self.set_analysis(data)
        runner = mock.Mock()
        result = self.dispatch(runner)
        runner.assert_not_called()
        self.assertTrue(result["inbox"])
        inbox = self.cfg.data_dir / "inbox" / f"{self.id}.md"
        text = inbox.read_text()
        for value in (data["title"], data["summary"], "Named project", "0.2", "Review the demo.", "unknown"):
            self.assertIn(value, text)
        self.dispatch(runner)
        self.assertEqual(text, inbox.read_text())
        args = build_parser().parse_args(["inbox", "--json"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, args.func(args, self.cfg))
        self.assertEqual({"id": self.id, "title": data["title"], "project": "unknown", "confidence": 0.99},
                         json.loads(output.getvalue())[0])
        self.assertTrue(any("inbox" in row["message"] for row in self.store.recent_events(20)))

    def test_no_routes_still_goes_to_inbox(self):
        data = analysis()
        data["routes"] = []
        self.set_analysis(data)
        self.assertTrue(self.dispatch(mock.Mock())["inbox"])
        self.assertEqual("filed", self.store.get_recording(self.id)["status"])

    def test_dedupe_actions_and_idempotent_redispatch(self):
        data = analysis()
        data["actions"].append(copy.deepcopy(data["actions"][0]))
        self.set_analysis(data)
        first = self.dispatch()
        runner = mock.Mock(side_effect=AssertionError("must not refile"))
        second = self.dispatch(runner)
        self.assertEqual(first, second)
        runner.assert_not_called()
        self.assertEqual(1, len(self.store.list_tasks()))
        self.assertEqual(1, len(self.store.artifacts(self.id, "filing")))

    def test_auto_approve_only_queues_until_pipeline(self):
        self.cfg.tasks.require_approval = False
        self.dispatch()
        self.assertEqual("approved", self.store.list_tasks()[0]["status"])

    def test_manual_override_removes_inbox_and_is_idempotent(self):
        data = analysis()
        data["routes"][0]["confidence"] = 0.1
        self.set_analysis(data)
        self.dispatch(mock.Mock())
        result = route_manually(self.cfg, self.store, self.id, "demo", runner=self.writer())
        self.assertIsNone(result["error"])
        self.assertFalse((self.cfg.data_dir / "inbox" / f"{self.id}.md").exists())
        self.assertEqual(result, route_manually(self.cfg, self.store, self.id, "demo", runner=mock.Mock()))
        self.dispatch(mock.Mock(side_effect=AssertionError("manual filing must be retained")))
        self.assertFalse((self.cfg.data_dir / "inbox" / f"{self.id}.md").exists())
        with self.assertRaisesRegex(DispatchError, "unknown project"):
            route_manually(self.cfg, self.store, self.id, "missing", runner=mock.Mock())

    def test_failed_manual_route_keeps_inbox(self):
        data = analysis()
        data["routes"][0]["confidence"] = 0.1
        self.set_analysis(data)
        self.dispatch(mock.Mock())
        self.cfg.agent.command += ["--exit-code", "7"]
        result = route_manually(self.cfg, self.store, self.id, "demo", runner=run_agent)
        self.assertIn("return code 7", result["error"])
        self.assertTrue((self.cfg.data_dir / "inbox" / f"{self.id}.md").exists())

    def test_manual_route_files_whole_inbox_including_other_project_notes(self):
        data = analysis()
        data["routes"][0]["confidence"] = 0.1
        data["routes"].append({**data["routes"][0], "project": "unknown",
                               "notes_markdown": "Second unresolved project's notes"})
        self.set_analysis(data)
        self.dispatch(mock.Mock())
        result = route_manually(self.cfg, self.store, self.id, "demo", runner=self.writer())
        self.assertIsNone(result["error"])
        prompt = json.loads(self.capture.read_text())["prompt"]
        self.assertIn("- Review the demo.", prompt)
        self.assertIn("Second unresolved project's notes", prompt)
        self.assertFalse(self.dispatch(mock.Mock())["inbox"])

    def test_manual_route_existing_project_consumes_new_inbox_notes(self):
        data = analysis()
        data["routes"].append({**data["routes"][0], "project": "unknown",
                               "notes_markdown": "Unresolved notes for manual override"})
        self.set_analysis(data)
        self.dispatch()
        result = route_manually(self.cfg, self.store, self.id, "demo",
            runner=self.writer({"note.md": "notes with manual addition"}))
        self.assertIsNone(result["error"])
        self.assertIn("Unresolved notes for manual override", json.loads(self.capture.read_text())["prompt"])
        self.assertFalse((self.cfg.data_dir / "inbox" / f"{self.id}.md").exists())

    def test_manual_commit_failure_keeps_inbox(self):
        data = analysis()
        data["routes"][0]["confidence"] = 0.1
        self.set_analysis(data)
        self.dispatch(mock.Mock())
        with mock.patch("phone2agent.dispatch.gitops.commit_files", side_effect=RuntimeError("hook failed")):
            result = route_manually(self.cfg, self.store, self.id, "demo", runner=self.writer())
        self.assertIn("hook failed", result["error"])
        self.assertTrue((self.cfg.data_dir / "inbox" / f"{self.id}.md").exists())

    def test_successful_manual_route_repairs_failed_dispatch_status(self):
        self.cfg.agent.command += ["--exit-code", "7"]
        self.dispatch(run_agent)
        self.assertEqual("failed", self.store.get_recording(self.id)["status"])
        self.cfg.agent.command = self.cfg.agent.command[:-2]
        route_manually(self.cfg, self.store, self.id, "demo", runner=self.writer())
        row = self.store.get_recording(self.id)
        self.assertEqual("filed", row["status"])
        self.assertIsNone(row["failed_stage"])
        self.assertIsNone(row["next_try_at"])

    def test_task_dedupe_across_concurrent_store_connections(self):
        barrier = threading.Barrier(4)
        def queue(_):
            with Store.open(self.cfg) as store:
                barrier.wait(timeout=5)
                return _queue_actions(self.cfg, store, self.id, analysis()["actions"])
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            ids = list(pool.map(queue, range(4)))
        self.assertTrue(all(task_ids == ids[0] for task_ids in ids))
        self.assertEqual(1, len(self.store.list_tasks()))

    def test_non_git_project(self):
        plain = self.project.parent / "plain"
        plain.mkdir()
        data = analysis()
        data["routes"][0]["project"] = "plain"
        self.set_analysis(data)
        result = self.dispatch()["filings"][0]
        self.assertIsNone(result["commit"])
        self.assertFalse(result["pushed"])
        self.assertIn("Non-git", result["report"])
        self.assertTrue((plain / "note.md").exists())

    def test_no_push_glob_and_real_local_push(self):
        bare = self.root / "origin.git"
        git(self.root, "init", "--bare", "-q", str(bare))
        git(self.project, "remote", "add", "origin", str(bare))
        branch = git(self.project, "branch", "--show-current")
        git(self.project, "push", "-qu", "origin", branch)
        initial = git(bare, "rev-parse", f"refs/heads/{branch}")
        self.cfg.projects.auto_push = True
        self.cfg.projects.no_push = ["de*"]
        result = self.dispatch()["filings"][0]
        self.assertFalse(result["pushed"])
        self.assertIn("no_push", result["report"])
        self.assertEqual(initial, git(bare, "rev-parse", f"refs/heads/{branch}"))
        self.cfg.projects.no_push = []
        other = self.store.add_recording("calls", "calls/second.wav", 20, 2000, status="analyzed")[0]
        self.store.add_artifact(other, "analysis", self.analysis_path)
        result = dispatch_recording(self.cfg, self.store, other,
            runner=self.writer({"note.md": "more notes"}))["filings"][0]
        self.assertTrue(result["pushed"])
        self.assertEqual(result["commit"], git(bare, "rev-parse", f"refs/heads/{branch}"))

    def test_push_failure_recorded_without_refiling_or_retrying(self):
        self.cfg.projects.auto_push = True
        with mock.patch("phone2agent.dispatch.gitops.has_upstream", return_value=True), \
                mock.patch("phone2agent.dispatch.gitops.push", return_value=(False, "remote rejected")) as push:
            result = self.dispatch()["filings"][0]
            self.assertIn("remote rejected", result["error"])
            self.assertEqual("filed", self.store.get_recording(self.id)["status"])
            self.dispatch(mock.Mock(side_effect=AssertionError("no retry")))
            push.assert_called_once()

    def test_all_routes_failed_backoff_json_event_and_retry(self):
        self.cfg.agent.command += ["--exit-code", "7"]
        result = self.dispatch(run_agent)
        self.assertIn("return code 7", result["filings"][0]["error"])
        row = self.store.get_recording(self.id)
        self.assertEqual(("failed", "dispatch", 1), (row["status"], row["failed_stage"], row["attempts"]))
        self.assertIsNotNone(row["next_try_at"])
        self.assertTrue(any(e["level"] == "error" for e in self.store.recent_events(20)))
        self.store.reset_for_retry(self.id)
        self.cfg.agent.command = self.cfg.agent.command[:-2]
        self.assertIsNone(self.dispatch()["filings"][0]["error"])
        self.assertEqual("filed", self.store.get_recording(self.id)["status"])

    def test_partial_project_failure_still_filed_and_success_skipped_on_replay(self):
        other = self.project.parent / "other"
        other.mkdir()
        data = analysis()
        data["routes"].append({**data["routes"][0], "project": "other"})
        self.set_analysis(data)
        def runner(*args, **kwargs):
            if kwargs["cwd"] == other:
                raise OSError("agent unavailable")
            return self.writer()(*args, **kwargs)
        self.dispatch(runner)
        self.assertEqual("filed", self.store.get_recording(self.id)["status"])
        called = []
        def retry(*args, **kwargs):
            called.append(kwargs["cwd"])
            return self.writer()(*args, **kwargs)
        self.dispatch(retry)
        self.assertEqual([other], called)

    def test_duplicate_project_routes_grouped_before_agent(self):
        data = analysis()
        data["routes"].append({**data["routes"][0], "notes_markdown": "Second note"})
        self.set_analysis(data)
        result = self.dispatch()
        self.assertEqual(1, len(result["filings"]))
        self.assertIn("Second note", json.loads(self.capture.read_text())["prompt"])

    def test_non_ascii_title_slug_and_include_transcript_clause(self):
        data = analysis()
        data["title"] = "通话"
        self.set_analysis(data)
        self.cfg.projects.include_transcript = True
        self.dispatch()
        prompt = json.loads(self.capture.read_text())["prompt"]
        self.assertIn(f"2026-01-02-call-{self.id}.md", prompt)
        self.assertIn(" — but you may include it", prompt)

    def test_auto_commit_disabled_and_no_upstream(self):
        self.cfg.projects.auto_commit = False
        result = self.dispatch()["filings"][0]
        self.assertIsNone(result["commit"])
        self.assertFalse(result["pushed"])

    def test_auto_push_without_upstream_keeps_local_commit(self):
        self.cfg.projects.auto_push = True
        result = self.dispatch()["filings"][0]
        self.assertIsNotNone(result["commit"])
        self.assertIsNone(result["error"])
        self.assertFalse(result["pushed"])
        self.assertIn("no upstream", result["report"])

    def test_nested_project_uses_repository_relative_snapshot_and_commit(self):
        nested = self.project / "nested"
        nested.mkdir()
        self.cfg.projects.roots = []
        self.cfg.projects.extra = [str(nested)]
        data = analysis()
        data["routes"][0]["project"] = "nested"
        self.set_analysis(data)
        (self.project / "staged.md").write_text("user staged\n")
        git(self.project, "add", "--", "staged.md")
        (nested / "dirty.md").write_text("user notes\n")
        self.set_result(["note.md", "dirty.md"])
        result = self.dispatch()["filings"][0]
        self.assertEqual(["nested/note.md"], result["files"])
        self.assertIn("pre-existing dirty file: nested/dirty.md", result["report"])
        self.assertEqual("staged.md", git(self.project, "diff", "--cached", "--name-only"))

    def test_invalid_agent_schema_fails_without_staging(self):
        self.canned.write_text(json.dumps({"files": ["note.md"]}))
        result = self.dispatch()["filings"][0]
        self.assertIn("agent failed", result["error"])
        self.assertIsNone(result["commit"])
        self.assertEqual("", git(self.project, "diff", "--cached", "--name-only"))

    def test_missing_analysis_and_invalid_status(self):
        runner = mock.Mock()
        with self.assertRaisesRegex(DispatchError, "not found"):
            dispatch_recording(self.cfg, self.store, 999, runner=runner)
        self.store.update_recording(self.id, status="downloaded")
        with self.assertRaisesRegex(DispatchError, "status analyzed"):
            self.dispatch(runner)
        self.assertEqual("downloaded", self.store.get_recording(self.id)["status"])
        self.store.update_recording(self.id, status="analyzed")
        self.analysis_path.unlink()
        with self.assertRaises(DispatchError):
            self.dispatch(runner)
        runner.assert_not_called()
        self.assertEqual("dispatch", self.store.get_recording(self.id)["failed_stage"])

    def test_cli_registration_and_handlers(self):
        parser = build_parser()
        for argv in (["dispatch", str(self.id)], ["route", str(self.id), "demo"]):
            args = parser.parse_args(argv)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, args.func(args, self.cfg))
        args = parser.parse_args(["dispatch", "999"])
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, args.func(args, self.cfg))


if __name__ == "__main__":
    unittest.main()
