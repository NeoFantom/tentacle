from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from phone2agent.gitops import (
    GitError,
    changed_files,
    commit_files,
    has_upstream,
    is_repo,
    normalize_files,
    push,
    repo_root,
)
from phone2agent import gitops


class GitOpsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Test User")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        (self.repo / "base.txt").write_text("base\n", encoding="utf-8")
        self.git("add", "base.txt")
        self.git("commit", "-m", "initial")

    def git(self, *args: str, cwd: Path | None = None, check: bool = True):
        return subprocess.run(
            ["git", "-C", str(cwd or self.repo), *args],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            check=check,
        )

    def committed_names(self, revision: str = "HEAD") -> set[str]:
        result = self.git("show", "--pretty=format:", "--name-only", revision)
        return set(result.stdout.splitlines())

    def test_repo_detection_and_changed_files_include_untracked_and_rename_sides(self):
        outside = self.root / "outside"
        outside.mkdir()
        self.assertTrue(is_repo(self.repo))
        self.assertFalse(is_repo(outside))
        self.assertEqual(self.repo.resolve(), repo_root(self.repo))
        (self.repo / "untracked name.txt").write_text("new\n", encoding="utf-8")
        self.git("mv", "base.txt", "renamed.txt")
        self.assertEqual(
            {"base.txt", "renamed.txt", "untracked name.txt"},
            changed_files(self.repo),
        )

    def test_commit_only_listed_files_and_preserve_unrelated_staged_change(self):
        (self.repo / "wanted.txt").write_text("wanted\n", encoding="utf-8")
        (self.repo / "other.txt").write_text("other\n", encoding="utf-8")
        (self.repo / "staged.txt").write_text("staged\n", encoding="utf-8")
        self.git("add", "staged.txt")

        with self.assertLogs("phone2agent.gitops", level="WARNING"):
            commit = commit_files(
                self.repo, ["wanted.txt", "missing.txt"], "file wanted",
            )

        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), commit)
        self.assertEqual({"wanted.txt"}, self.committed_names())
        self.assertEqual(
            "A  staged.txt\n?? other.txt",
            self.git("status", "--short").stdout.strip(),
        )

    def test_literal_pathspec_and_normalization_from_project_subdirectory(self):
        project = self.repo / "project"
        project.mkdir()
        literal = project / ":(glob)*.txt"
        literal.write_text("literal\n", encoding="utf-8")
        (project / "other.txt").write_text("other\n", encoding="utf-8")
        self.assertEqual(["project/:(glob)*.txt"], normalize_files(project, [literal.name]))
        commit_files(project, [literal.name], "literal name")
        self.assertEqual({"project/:(glob)*.txt"}, self.committed_names())
        self.assertIn("project/other.txt", changed_files(self.repo))

    def test_path_escape_symlink_escape_git_path_and_directory_are_rejected(self):
        outside = self.root / "outside.txt"
        outside.write_text("outside\n", encoding="utf-8")
        (self.repo / "safe.txt").write_text("safe\n", encoding="utf-8")
        link = self.repo / "escape"
        try:
            link.symlink_to(outside)
        except OSError as exc:
            self.skipTest(f"symlinks unavailable: {exc}")

        invalid = (["safe.txt", "../outside.txt"], ["safe.txt", "escape"],
                   [outside], [".git/config"], ["safe.txt", self.repo])
        for files in invalid:
            with self.subTest(files=files), self.assertRaises(GitError):
                commit_files(self.repo, files, "must fail")
            self.assertNotIn(
                "safe.txt", self.git("diff", "--cached", "--name-only").stdout,
            )

    def test_symlink_to_file_inside_project_keeps_its_own_name(self):
        target = self.repo / "target.txt"
        target.write_text("target\n", encoding="utf-8")
        link = self.repo / "link.txt"
        try:
            link.symlink_to(target.name)
        except OSError as exc:
            self.skipTest(f"symlinks unavailable: {exc}")
        self.assertEqual(["link.txt"], normalize_files(self.repo, ["link.txt"]))
        commit_files(self.repo, ["link.txt"], "add link")
        self.assertEqual({"link.txt"}, self.committed_names())
        self.assertIn("target.txt", changed_files(self.repo))

    def test_nothing_changed_returns_none(self):
        self.assertIsNone(commit_files(self.repo, ["base.txt"], "nothing"))

    def test_staging_selected_path_back_to_head_returns_none(self):
        (self.repo / "base.txt").write_text("staged version\n", encoding="utf-8")
        self.git("add", "base.txt")
        (self.repo / "base.txt").write_text("base\n", encoding="utf-8")
        self.assertIsNone(commit_files(self.repo, ["base.txt"], "nothing after add"))
        self.assertEqual("", self.git("status", "--short").stdout)

    def test_unborn_repo_first_commit_and_deletion(self):
        unborn = self.root / "unborn"
        unborn.mkdir()
        self.git("init", "-b", "main", cwd=unborn)
        self.git("config", "user.name", "Test User", cwd=unborn)
        self.git("config", "user.email", "test@example.invalid", cwd=unborn)
        self.git("config", "commit.gpgsign", "false", cwd=unborn)
        (unborn / "first.txt").write_text("first\n", encoding="utf-8")
        first = commit_files(unborn, ["first.txt"], "first")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=unborn).stdout.strip(), first)

        (unborn / "first.txt").unlink()
        second = commit_files(unborn, ["first.txt"], "delete first")
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=unborn).stdout.strip(), second)
        self.assertEqual({"first.txt"}, set(self.git(
            "show", "--pretty=format:", "--name-only", cwd=unborn,
        ).stdout.splitlines()))

    def test_commit_error_uses_stderr_tail(self):
        hook = self.repo / ".git" / "hooks" / "pre-commit"
        hook.write_text(
            "#!/bin/sh\nprintf '%*s' 2200 '' | tr ' ' x >&2\nprintf 'END-MARKER\\n' >&2\nexit 1\n",
            encoding="utf-8",
        )
        hook.chmod(0o755)
        (self.repo / "blocked.txt").write_text("blocked\n", encoding="utf-8")
        with self.assertRaises(GitError) as caught:
            commit_files(self.repo, ["blocked.txt"], "blocked")
        self.assertIn("END-MARKER", str(caught.exception))
        self.assertLessEqual(len(str(caught.exception)), 2000)

    def test_git_environment_and_timeout_stderr_tail(self):
        completed = subprocess.CompletedProcess([], 0, stdout=b"true\n", stderr=b"")
        with mock.patch("phone2agent.gitops.subprocess.run", return_value=completed) as run:
            self.assertTrue(is_repo(self.repo))
        env = run.call_args.kwargs["env"]
        self.assertEqual("0", env["GIT_TERMINAL_PROMPT"])
        self.assertEqual("1", env["GIT_LITERAL_PATHSPECS"])

        expired = subprocess.TimeoutExpired(["git"], 1, stderr=b"secret context\nTIMEOUT-END")
        with mock.patch("phone2agent.gitops.subprocess.run", side_effect=expired), \
                self.assertRaisesRegex(GitError, "TIMEOUT-END"):
            gitops._run(self.repo, "status", timeout=1)

    def test_push_with_and_without_upstream(self):
        self.assertFalse(has_upstream(self.repo))
        self.assertEqual((False, "current branch has no upstream"), push(self.repo))

        bare = self.root / "origin.git"
        subprocess.run(
            ["git", "init", "--bare", str(bare)], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.git("remote", "add", "origin", str(bare))
        self.git("push", "-u", "origin", "main")
        self.assertTrue(has_upstream(self.repo))
        (self.repo / "next.txt").write_text("next\n", encoding="utf-8")
        commit_files(self.repo, ["next.txt"], "next")
        ok, message = push(self.repo)
        self.assertTrue(ok, message)
        remote_head = self.git("rev-parse", "refs/heads/main", cwd=bare).stdout.strip()
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), remote_head)

        missing = self.root / "missing-origin.git"
        self.git("remote", "set-url", "origin", str(missing))
        (self.repo / "fail.txt").write_text("fail\n", encoding="utf-8")
        commit_files(self.repo, ["fail.txt"], "push failure")
        ok, message = push(self.repo)
        self.assertFalse(ok)
        self.assertIn("fatal:", message)

    def test_push_converts_upstream_git_error_to_result(self):
        with mock.patch("phone2agent.gitops.has_upstream", side_effect=GitError("broken repo")):
            self.assertEqual((False, "broken repo"), push(self.repo))


if __name__ == "__main__":
    unittest.main()
