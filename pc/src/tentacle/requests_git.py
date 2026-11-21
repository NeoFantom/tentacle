"""Small argv-only GitHub adapter and request branch helpers."""
from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess


class RequestFailure(Exception):
    """An actionable failure that is safe to display to the phone owner."""


class Gh:
    """Run GitHub CLI without a shell or leaking credentials from its output."""

    def __init__(self, repo: Path):
        self.repo = repo

    def call(self, *argv: str) -> str:
        try:
            result = subprocess.run(['gh', *argv], cwd=self.repo, capture_output=True,
                                    text=True, timeout=60)
        except FileNotFoundError as exc:
            raise RequestFailure('GitHub CLI (gh) is required for pull requests; install it and run gh auth login.') from exc
        except subprocess.TimeoutExpired as exc:
            raise RequestFailure('GitHub CLI (gh) timed out; check connectivity and try again.') from exc
        if result.returncode:
            if argv[:2] == ('auth', 'status'):
                raise RequestFailure('GitHub CLI is not authenticated; run gh auth login.')
            raise RequestFailure('GitHub CLI operation failed (' + ' '.join(argv[:2]) + '); check gh authentication and repository permissions.')
        return result.stdout.strip()

    def json(self, *argv: str):
        try:
            return json.loads(self.call(*argv))
        except ValueError as exc:
            raise RequestFailure('GitHub CLI returned invalid JSON.') from exc

    def check_auth(self) -> None:
        self.call('auth', 'status')


def resolve_repo(watcher, repo: Path) -> tuple[str, str]:
    """Return PR destination and the push repository's GitHub owner/name."""
    remote = watcher.options.push_remote
    url = watcher.git(repo, 'remote', 'get-url', '--push', remote)
    info = Gh(repo).json('repo', 'view', url, '--json', 'nameWithOwner,parent')
    if not isinstance(info, dict):
        raise RequestFailure('GitHub CLI returned invalid repository information.')
    name = info.get('nameWithOwner', '')
    parent = info.get('parent') or {}
    if not isinstance(parent, dict):
        raise RequestFailure('GitHub CLI returned invalid parent repository information.')
    destination = watcher.options.pr_repo or parent.get('nameWithOwner') or name
    if not all(isinstance(value, str) and re.fullmatch(r'[\w.-]+/[\w.-]+', value)
               for value in (name, destination)):
        raise RequestFailure('Cannot resolve the GitHub repository; configure requests pr_repo.')
    return destination, name


def default_branch(watcher, repo: Path, *, use_configured: bool = True) -> str:
    """Fetch and resolve the configured base or remote HEAD, without merging."""
    remote = watcher.options.push_remote
    watcher.git(repo, 'fetch', remote)
    branch = watcher.options.base if use_configured else ''
    if not branch:
        output = watcher.git(repo, 'ls-remote', '--symref', remote, 'HEAD')
        match = re.search(r'^ref: refs/heads/(.+)\s+HEAD$', output, re.MULTILINE)
        if match:
            branch = match[1]
        else:
            raise RequestFailure('The remote default branch could not be resolved; '
                                 + ('configure requests base.' if use_configured else
                                    'set the personal fork remote HEAD to its default branch.'))
    if branch.startswith(remote + '/'):
        branch = branch[len(remote) + 1:]
    watcher.git(repo, 'check-ref-format', '--branch', branch)
    return branch
