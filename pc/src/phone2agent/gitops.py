"""Small, deterministic Git operations used after agent-written changes."""
from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path
from typing import Iterable


_LOG = logging.getLogger(__name__)
_TIMEOUT_S = 30
_ERROR_TAIL = 2000


class GitError(RuntimeError):
    """A Git operation or a requested repository path was invalid."""


def _environment() -> dict[str, str]:
    env = os.environ.copy()
    env["GIT_TERMINAL_PROMPT"] = "0"
    # ``--`` does not disable Git's pathspec magic.
    env["GIT_LITERAL_PATHSPECS"] = "1"
    return env


def _stderr_tail(stderr: bytes | str | None) -> str:
    if isinstance(stderr, bytes):
        value = stderr.decode(errors="replace")
    else:
        value = stderr or ""
    value = value.strip()
    return value[-_ERROR_TAIL:] if value else "git command failed"


def _run(path: Path | str, *args: str, check: bool = True,
         timeout: int = _TIMEOUT_S) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            ["git", "-C", os.fspath(path), *args],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=_environment(), timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired as exc:
        detail = _stderr_tail(exc.stderr) if exc.stderr else ""
        suffix = f": {detail}" if detail else ""
        raise GitError(f"git command timed out after {timeout}s{suffix}") from exc
    except OSError as exc:
        raise GitError(str(exc)) from exc
    if check and result.returncode != 0:
        raise GitError(_stderr_tail(result.stderr))
    return result


def is_repo(path: Path | str) -> bool:
    """Return whether *path* is within a Git working tree."""
    result = _run(path, "rev-parse", "--is-inside-work-tree", check=False)
    return result.returncode == 0 and result.stdout.strip() == b"true"


def repo_root(path: Path | str) -> Path:
    """Return the resolved worktree root containing *path*."""
    result = _run(path, "rev-parse", "--show-toplevel")
    return Path(os.fsdecode(result.stdout.rstrip(b"\r\n"))).resolve()


def has_upstream(path: Path | str) -> bool:
    """Return whether the current branch has a configured upstream."""
    if not is_repo(path):
        return False
    result = _run(path, "rev-parse", "--abbrev-ref", "--symbolic-full-name",
                  "@{upstream}", check=False)
    return result.returncode == 0 and bool(result.stdout.strip())


def changed_files(path: Path | str) -> set[str]:
    """Return staged, unstaged, and untracked names relative to the worktree.

    Rename and copy records contain two NUL-delimited names; both are returned
    so a pre-existing change to either side is protected by dirty snapshots.
    """
    root = repo_root(path)
    output = _run(root, "status", "--porcelain=v1", "-z", "--untracked-files=all").stdout
    fields = output.split(b"\0")
    changed: set[str] = set()
    index = 0
    while index < len(fields):
        record = fields[index]
        index += 1
        if not record:
            continue
        if len(record) < 4 or record[2:3] != b" ":
            raise GitError("unexpected output from git status --porcelain=v1 -z")
        status = record[:2]
        changed.add(os.fsdecode(record[3:]))
        if b"R" in status or b"C" in status:
            if index >= len(fields) or not fields[index]:
                raise GitError("incomplete rename record from git status")
            changed.add(os.fsdecode(fields[index]))
            index += 1
    return changed


def _inside(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def normalize_files(path: Path | str, files: Iterable[str | os.PathLike[str]]) -> list[str]:
    """Validate file names and return literal worktree-relative names.

    Relative names are resolved from *path*.  Every resolved target must remain
    inside both *path* and its Git worktree; symlink targets are checked too.
    Directories and Git's own administrative files are never valid targets.
    """
    project = Path(path).resolve()
    if not project.is_dir():
        raise GitError(f"project directory does not exist: {project}")
    root = repo_root(project)
    if not _inside(project, root):
        raise GitError(f"project is outside git worktree: {project}")

    normalized: list[str] = []
    seen: set[str] = set()
    for supplied in files:
        raw = os.fspath(supplied)
        if not raw or raw == ".":
            raise GitError("empty file path is not allowed")
        candidate = Path(raw)
        if ".." in candidate.parts:
            raise GitError(f"parent traversal is not allowed: {raw}")
        absolute = candidate if candidate.is_absolute() else project / candidate
        lexical = Path(os.path.abspath(absolute))
        resolved = lexical.resolve(strict=False)
        if (not _inside(lexical, project) or not _inside(lexical, root)
                or not _inside(resolved, project) or not _inside(resolved, root)):
            raise GitError(f"file is outside project: {raw}")
        relative_path = lexical.relative_to(root)
        if ".git" in relative_path.parts:
            raise GitError(f"git administrative path is not allowed: {raw}")
        if lexical.is_dir():
            raise GitError(f"directories are not allowed: {raw}")
        relative = relative_path.as_posix()
        if relative not in seen:
            seen.add(relative)
            normalized.append(relative)
    return normalized


def commit_files(path: Path | str, files: Iterable[str | os.PathLike[str]],
                 message: str) -> str | None:
    """Commit only valid, currently changed files; leave all other state alone."""
    root = repo_root(path)
    requested = normalize_files(path, files)
    changed = changed_files(root)
    selected = [name for name in requested if name in changed]
    skipped = [name for name in requested if name not in changed]
    if skipped:
        _LOG.warning("skipping unchanged files: %s", ", ".join(skipped))
    if not selected:
        return None

    _run(root, "add", "--", *selected)
    staged = _run(root, "diff", "--cached", "--quiet", "--exit-code", "--",
                  *selected, check=False)
    if staged.returncode == 0:
        return None
    if staged.returncode != 1:
        raise GitError(_stderr_tail(staged.stderr))
    _run(root, "commit", "-m", message, "--", *selected)
    result = _run(root, "rev-parse", "HEAD")
    return result.stdout.decode("ascii", errors="strict").strip()


def push(path: Path | str) -> tuple[bool, str]:
    """Push the current branch without force when it has an upstream."""
    try:
        if not has_upstream(path):
            return False, "current branch has no upstream"
        result = _run(path, "push")
    except GitError as exc:
        return False, str(exc)
    message = result.stdout.decode(errors="replace").strip()
    return True, message or "pushed"
