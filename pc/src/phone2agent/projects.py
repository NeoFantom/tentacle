"""Discover local projects and describe them for routing call analyses."""
from __future__ import annotations

import dataclasses
import fnmatch
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .config import ProjectsConfig


_HEADING_RE = re.compile(r"^#{1,6}\s+")
_INDEX_HEADING_RE = re.compile(r"^###\s+(.+?)\s*$")


@dataclass(frozen=True)
class Project:
    id: str
    path: Path
    description: str
    has_git: bool
    has_remote: bool


def _index_descriptions(path: Path | None) -> dict[str, str]:
    if path is None or not path.is_file():
        return {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return {}

    descriptions: dict[str, str] = {}
    project_id: str | None = None
    content: list[str] = []

    def finish() -> None:
        if project_id is not None:
            descriptions[project_id] = " ".join(" ".join(content).split())[:600]

    for line in lines:
        match = _INDEX_HEADING_RE.fullmatch(line.strip())
        if match:
            finish()
            project_id = match.group(1).strip()
            content = []
        elif project_id is not None and _HEADING_RE.match(line.strip()):
            finish()
            project_id = None
            content = []
        elif project_id is not None:
            content.append(line)
    finish()
    return descriptions


def _document_description(path: Path) -> str:
    for name in ("AGENTS.md", "README.md"):
        document = path / name
        if not document.is_file():
            continue
        try:
            lines = document.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError):
            continue
        content = [line.strip() for line in lines if line.strip() and not _HEADING_RE.match(line.strip())]
        description = " ".join(" ".join(content).split())[:400]
        if description:
            return description
    return ""


def _git_status(path: Path) -> tuple[bool, bool]:
    try:
        result = subprocess.run(
            ["git", "-C", str(path), "rev-parse", "--is-inside-work-tree"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False, False
    has_git = result.returncode == 0 and result.stdout.strip() == "true"
    if not has_git:
        return False, False
    try:
        remotes = subprocess.run(
            ["git", "-C", str(path), "remote"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return True, False
    return True, remotes.returncode == 0 and bool(remotes.stdout.strip())


def _unique_id(path: Path, used: set[str]) -> str:
    name = path.name
    if name not in used:
        return name

    parents: list[str] = []
    current = path.parent
    while current.name:
        parents.insert(0, current.name)
        candidate = "/".join([*parents, name])
        if candidate not in used:
            return candidate
        current = current.parent

    suffix = 2
    candidate = f"{path.parent.name}/{name}-{suffix}"
    while candidate in used:
        suffix += 1
        candidate = f"{path.parent.name}/{name}-{suffix}"
    return candidate


def _excluded(project_id: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(project_id, pattern) for pattern in patterns)


def list_projects(projects_cfg: ProjectsConfig) -> list[Project]:
    """Return all configured projects, with stable and unique IDs."""
    candidates: list[Path] = []
    for root in projects_cfg.root_paths():
        if not root.is_dir():
            continue
        try:
            candidates.extend(sorted(
                (entry for entry in root.iterdir() if entry.is_dir()),
                key=lambda path: path.name,
            ))
        except OSError:
            continue
    candidates.extend(path for path in projects_cfg.extra_paths() if path.is_dir())

    index = _index_descriptions(projects_cfg.index_path())
    used_ids: set[str] = set()
    projects: list[Project] = []
    for path in candidates:
        project_id = _unique_id(path, used_ids)
        if _excluded(project_id, projects_cfg.exclude):
            continue
        used_ids.add(project_id)
        has_git, has_remote = _git_status(path)
        projects.append(Project(
            id=project_id,
            path=path,
            description=index.get(project_id) or _document_description(path),
            has_git=has_git,
            has_remote=has_remote,
        ))
    return sorted(projects, key=lambda project: project.id)


def get_project(projects_cfg: ProjectsConfig, id: str) -> Project | None:
    """Look up a project by its registry ID."""
    return next((project for project in list_projects(projects_cfg) if project.id == id), None)


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("projects", help="list local projects")
    parser.add_argument("--json", action="store_true", help="print JSON")
    parser.set_defaults(func=_run_projects)


def _run_projects(args, cfg) -> int:
    projects = list_projects(cfg.projects)
    if args.json:
        print(json.dumps([
            {**dataclasses.asdict(project), "path": str(project.path)} for project in projects
        ], ensure_ascii=False, indent=2))
        return 0
    for project in projects:
        flags = "git" if project.has_git else "-"
        if project.has_remote:
            flags += ",remote"
        print(f"{project.id}\t{project.path}\t{flags}\t{project.description}")
    return 0
