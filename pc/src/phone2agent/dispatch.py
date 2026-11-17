"""File approved routes and queue actions without disturbing existing git work."""
from __future__ import annotations

import fnmatch
import json
import re
import sys
import unicodedata
from datetime import datetime
from importlib import resources
from pathlib import Path
from urllib.parse import quote

from . import gitops
from .agent import run_agent
from .analyze import _artifact_path, _atomic_json
from .jsonschema_lite import validate
from .projects import list_projects
from .store import Store
from .timeutil import from_ms


class DispatchError(RuntimeError):
    """A recording cannot be dispatched with its current state or artifacts."""


def _metadata(recording_row) -> str:
    row = dict(recording_row)
    return "\n".join(
        f"{label}: {row.get(key) if row.get(key) is not None else 'unknown'}"
        for label, key in (("source", "source"), ("app", "app"),
                           ("counterpart", "counterpart"), ("started_at", "started_at"),
                           ("duration", "duration_s")))


def _render(template_name: str, values: dict) -> str:
    template = resources.files("phone2agent").joinpath("prompts", template_name).read_text(
        encoding="utf-8")
    # Substitute only template placeholders, never placeholder-like call content.
    return re.sub(r"\{\{(\w+)\}\}", lambda match: str(values[match[1]]), template)


def _log_path(cfg, kind: str, identifier: int) -> Path:
    stamp = datetime.now().astimezone().strftime("%Y%m%dT%H%M%S%f%z")
    path = (cfg.data_dir / "logs" / "agent" / f"{stamp}-{kind}-{identifier}.log").resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _plain_files(cwd: Path, files: list[str]) -> list[str]:
    """Apply the project boundary even when the workspace has no git repository."""
    root = cwd.resolve()
    validated = []
    for name in files:
        path = Path(name)
        if not name or ".." in path.parts or ".git" in path.parts:
            raise ValueError(f"unsafe file path: {name!r}")
        absolute = path if path.is_absolute() else root / path
        resolved = absolute.resolve()
        if not resolved.is_relative_to(root) or resolved == root or absolute.is_dir():
            raise ValueError(f"file is outside the project or is not a file: {name!r}")
        relative = absolute.relative_to(root).as_posix()
        if relative not in validated:
            validated.append(relative)
    return validated


def _run_work(cfg, project, prompt: str, *, cwd: Path, kind: str,
              identifier: int, runner) -> tuple[dict, bool]:
    """Run one agent, then apply the same git policy for filings and tasks.

    The boolean records successful agent work separately from commit/push errors:
    retrying a failed push must never cause the notes to be written again.
    """
    result = {"project": project.id if project else None, "files": [], "commit": None,
              "pushed": False, "report": "", "error": None}
    agent_ok = False
    diagnostics = []
    try:
        cwd = cwd.resolve()
        has_git = project is not None and gitops.is_repo(cwd)
        before = gitops.changed_files(cwd) if has_git else set()
        log_path = _log_path(cfg, kind, identifier)
        schema = resources.files("phone2agent").joinpath("schemas/filing.schema.json")
        with resources.as_file(schema) as schema_path:
            response = runner(cfg.agent, prompt, cwd=cwd, mode="workspace-write",
                              output_schema=schema_path, log_path=log_path)
            if not response.ok:
                raise DispatchError(
                    f"agent failed (return code {response.returncode}); see {response.log_path}")
            validate(response.json, json.loads(schema_path.read_text(encoding="utf-8")))
        data = response.json
        result["report"] = data["report"]
        files = _plain_files(cwd, data["files"])
        if has_git:
            files = gitops.normalize_files(cwd, files)
        # Invalid paths are an unsuccessful agent result, so no partial add occurs.
        agent_ok = True
        if not has_git:
            result["files"] = files
            diagnostics.append("Non-git workspace; git commit and push skipped.")
        else:
            after = gitops.changed_files(cwd)
            selected = []
            for name in files:
                if name in before:
                    diagnostics.append(f"Skipped pre-existing dirty file: {name}")
                elif name not in after:
                    diagnostics.append(f"Skipped unchanged file: {name}")
                else:
                    selected.append(name)
            result["files"] = selected
            if cfg.projects.auto_commit and selected:
                result["commit"] = gitops.commit_files(
                    gitops.repo_root(cwd), selected, data["commit_message"])
            if result["commit"] and cfg.projects.auto_push:
                if any(fnmatch.fnmatch(project.id, pattern) for pattern in cfg.projects.no_push):
                    diagnostics.append("Push skipped by projects.no_push.")
                elif not gitops.has_upstream(cwd):
                    diagnostics.append("Push skipped: branch has no upstream.")
                else:
                    ok, message = gitops.push(cwd)
                    result["pushed"] = ok
                    if not ok:
                        result["error"] = f"Push failed: {message}"
                    if message:
                        diagnostics.append(message)
    except Exception as exc:
        result["error"] = str(exc)
    result["report"] = "\n".join(filter(None, [result["report"], *diagnostics]))
    return result, agent_ok


def _load_analysis(cfg, store, recording_id) -> dict:
    artifact = store.latest_artifact(recording_id, "analysis")
    if artifact is None:
        raise DispatchError("recording has no analysis artifact")
    data = json.loads(_artifact_path(cfg, artifact).read_text(encoding="utf-8"))
    schema = resources.files("phone2agent").joinpath("schemas/analysis.schema.json")
    validate(data, json.loads(schema.read_text(encoding="utf-8")))
    return data


def _successful_filings(cfg, store, recording_id) -> dict[str, dict]:
    successful = {}
    for artifact in store.artifacts(recording_id, "filing"):
        try:
            data = json.loads(_artifact_path(cfg, artifact).read_text(encoding="utf-8"))
            meta = json.loads(artifact["meta"] or "{}")
            if meta.get("success", not data.get("error")):
                successful[data["project"]] = data
        except (OSError, ValueError, KeyError):
            continue
    return successful


def _filing_prompt(cfg, store, row, analysis, project, notes) -> str:
    stamp = row["started_at"]
    date = (datetime.fromisoformat(stamp).date().isoformat() if stamp
            else from_ms(row["mtime_ms"]).date().isoformat())
    title = unicodedata.normalize("NFKD", analysis["title"]).encode("ascii", "ignore").decode()
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:60].rstrip("-")
    transcript = store.latest_artifact(row["id"], "transcript")
    return _render("file_notes.md", {
        "owner_name": cfg.analyze.owner_name, "language": cfg.analyze.language,
        "project_id": project.id, "project_path": str(project.path.resolve()),
        "notes_markdown": notes, "metadata": _metadata(row),
        "transcript_path": str(_artifact_path(cfg, transcript).resolve()) if transcript else "unavailable",
        "transcript_clause": " — but you may include it" if cfg.projects.include_transcript else "",
        "default_notes_dir": cfg.projects.default_notes_dir,
        "date": date, "slug": slug or f"call-{row['id']}",
    })


def _file_project(cfg, store, row, analysis, project, notes, runner, *, consume_inbox=False):
    prompt = _filing_prompt(cfg, store, row, analysis, project, notes)
    result, success = _run_work(cfg, project, prompt, cwd=project.path, kind="dispatch",
                                identifier=row["id"], runner=runner)
    # IDs may contain '/' when project basenames collide; encode rather than nest.
    project_name = quote(project.id, safe="")
    path = (cfg.data_dir / "filings" / f"{row['id']}-{project_name}.json").resolve()
    _atomic_json(path, result)
    store.add_artifact(row["id"], "filing", path,
                       meta={"project": project.id, "success": success,
                             "inbox_consumed": consume_inbox and success and not result["error"]})
    store.log_event("error" if result["error"] else "info",
                    f"Filing {project.id}: {result['error'] or result['report'] or 'done'}",
                    recording_id=row["id"])
    return result, success


def _append_inbox(cfg, store, row, analysis, route) -> None:
    path = cfg.data_dir / "inbox" / f"{row['id']}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    section = (f"# {analysis['title']}\n\n{analysis['summary']}\n\n"
               f"Project: {route['project'] or 'unknown'}\n"
               f"Confidence: {route['confidence']}\nReason: {route['reason']}\n\n"
               f"{route['notes_markdown']}\n\n")
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    if section not in existing:
        with path.open("a", encoding="utf-8") as output:
            output.write(section)
        store.log_event("info", f"Route placed in inbox: {route['project']}", recording_id=row["id"])


def _queue_actions(cfg, store, recording_id, actions) -> list[int]:
    ids = []
    for action in actions:
        # The daemon and a foreground CLI may share the database. Hold the
        # SQLite write lock across lookup and insertion without changing schema.
        store.conn.execute("BEGIN IMMEDIATE")
        try:
            existing = store.conn.execute(
                "SELECT id FROM tasks WHERE recording_id = ? AND title = ? ORDER BY id LIMIT 1",
                (recording_id, action["title"])).fetchone()
            if existing is None:
                task_id = store.add_task(recording_id=recording_id, status="pending", **action)
            else:
                task_id = existing["id"]
                store.conn.commit()
        except Exception:
            store.conn.rollback()
            raise
        if existing is None:
            if not cfg.tasks.require_approval:
                store.update_task(task_id, status="approved")
            store.log_event("info", f"Queued task: {action['title']}",
                            recording_id=recording_id, task_id=task_id)
        ids.append(task_id)
    return list(dict.fromkeys(ids))


def dispatch_recording(cfg, store, recording_id, *, runner=run_agent) -> dict:
    row = store.get_recording(recording_id)
    if row is None:
        raise DispatchError(f"recording {recording_id} not found")
    # A filed recording can be replayed safely; this also repairs an interrupted queue write.
    if row["status"] not in {"analyzed", "filed"}:
        raise DispatchError(f"recording {recording_id} must have status analyzed (or filed for replay)")
    try:
        analysis = _load_analysis(cfg, store, recording_id)
        projects = {project.id: project for project in list_projects(cfg.projects)}
        successful = _successful_filings(cfg, store, recording_id)
        inbox_consumed = any(json.loads(artifact["meta"] or "{}").get("inbox_consumed", False)
                             for artifact in store.artifacts(recording_id, "filing"))
        result = {"recording_id": recording_id, "filings": [], "inbox": False, "tasks": []}
        processed = 0
        grouped = {}
        routes = analysis["routes"] or [{"project": None, "confidence": 0,
                    "reason": "No project route was identified.", "notes_markdown": analysis["summary"]}]
        for route in routes:
            if route["project"] in successful:
                # A manual override also settles a formerly low-confidence route.
                grouped.setdefault(route["project"], []).append(route["notes_markdown"])
            elif route["project"] not in projects or route["confidence"] < cfg.projects.min_confidence:
                if not inbox_consumed:
                    _append_inbox(cfg, store, row, analysis, route)
                    result["inbox"] = True
                processed += 1
            else:
                grouped.setdefault(route["project"], []).append(route["notes_markdown"])
        for project_id, notes in grouped.items():
            if project_id in successful:
                filing, success = successful[project_id], True
            else:
                filing, success = _file_project(cfg, store, row, analysis,
                    projects[project_id], "\n\n".join(notes), runner)
            result["filings"].append(filing)
            processed += int(success)
        result["tasks"] = _queue_actions(cfg, store, recording_id, analysis["actions"])
        if not processed:
            message = "All project filing agents failed: " + "; ".join(
                filing["error"] or "unknown error" for filing in result["filings"])
            store.mark_failed(recording_id, "dispatch", message, cfg.pipeline.max_attempts)
        else:
            store.update_recording(recording_id, status="filed", failed_stage=None,
                                   error=None, next_try_at=None)
        return result
    except Exception as exc:
        store.mark_failed(recording_id, "dispatch", str(exc), cfg.pipeline.max_attempts)
        store.log_event("error", f"Dispatch failed: {exc}", recording_id=recording_id)
        raise DispatchError(str(exc)) from exc


def route_manually(cfg, store, recording_id, project_id, *, runner=run_agent) -> dict:
    row = store.get_recording(recording_id)
    if row is None:
        raise DispatchError(f"recording {recording_id} not found")
    projects = {project.id: project for project in list_projects(cfg.projects)}
    if project_id not in projects:
        raise DispatchError(f"unknown project: {project_id}")
    analysis = _load_analysis(cfg, store, recording_id)
    successful = _successful_filings(cfg, store, recording_id)
    inbox = cfg.data_dir / "inbox" / f"{recording_id}.md"
    if project_id in successful and not inbox.exists():
        return successful[project_id]
    # An inbox is one recording-wide entry. File all of it before removing it,
    # including notes that originally had a different best-guess project.
    notes = ([inbox.read_text(encoding="utf-8")] if inbox.exists() else
             [route["notes_markdown"] for route in analysis["routes"] if route["project"] == project_id])
    if not notes:
        notes = [route["notes_markdown"] for route in analysis["routes"]]
    result, success = _file_project(cfg, store, row, analysis, projects[project_id],
                                    "\n\n".join(notes) or analysis["summary"], runner,
                                    consume_inbox=inbox.exists())
    if success and not result["error"]:
        inbox.unlink(missing_ok=True)
        if row["status"] == "failed" and row["failed_stage"] == "dispatch":
            store.update_recording(recording_id, status="filed", failed_stage=None,
                                   error=None, next_try_at=None)
    return result


def _inbox_entries(cfg, store) -> list[dict]:
    entries = []
    for path in sorted((cfg.data_dir / "inbox").glob("*.md")):
        if not path.stem.isdecimal():
            continue
        recording_id = int(path.stem)
        analysis = _load_analysis(cfg, store, recording_id)
        best = max(analysis["routes"], key=lambda route: route["confidence"], default={})
        entries.append({"id": recording_id, "title": analysis["title"],
                        "project": best.get("project"), "confidence": best.get("confidence", 0)})
    return entries


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("dispatch", help="file an analyzed recording and queue tasks")
    parser.add_argument("id", type=int)
    parser.set_defaults(func=_dispatch_command)
    parser = subparsers.add_parser("inbox", help="list recordings needing a project route")
    parser.add_argument("--json", action="store_true")
    parser.set_defaults(func=_inbox_command)
    parser = subparsers.add_parser("route", help="file a recording into a chosen project")
    parser.add_argument("id", type=int)
    parser.add_argument("project")
    parser.set_defaults(func=_route_command)


def _dispatch_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            result = dispatch_recording(cfg, store, args.id)
            failed = store.get_recording(args.id)["status"] == "failed"
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return int(failed)
    except (DispatchError, OSError, ValueError) as exc:
        print(f"dispatch error: {exc}", file=sys.stderr)
        return 1


def _inbox_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            entries = _inbox_entries(cfg, store)
        if args.json:
            print(json.dumps(entries, ensure_ascii=False, indent=2))
        else:
            for entry in entries:
                print(f"{entry['id']}\t{entry['title']}\t{entry['project'] or '-'}\t{entry['confidence']}")
        return 0
    except (DispatchError, OSError, ValueError) as exc:
        print(f"inbox error: {exc}", file=sys.stderr)
        return 1


def _route_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            result = route_manually(cfg, store, args.id, args.project)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return int(bool(result["error"]))
    except (DispatchError, OSError, ValueError) as exc:
        print(f"route error: {exc}", file=sys.stderr)
        return 1
