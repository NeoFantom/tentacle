"""Approval queue and execution for actions extracted from call analyses."""
from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

from .agent import run_agent
from .analyze import _artifact_path, _atomic_json
from .dispatch import _metadata, _render, _run_work
from .projects import get_project
from .store import Store


class TaskError(RuntimeError):
    """A task does not exist or cannot make the requested state transition."""


def _require_task(store, task_id: int):
    task = store.get_task(task_id)
    if task is None:
        raise TaskError(f"task {task_id} not found")
    return task


def _claim_approved(store, task_id: int):
    """Atomically move one approved task to running and return its prior row."""
    store.conn.execute("BEGIN IMMEDIATE")
    try:
        task = _require_task(store, task_id)
        if task["status"] != "approved":
            raise TaskError(f"task {task_id} must have status approved")
        # update_task's connection context commits this claim before the agent
        # starts, releasing the write lock for other daemon/CLI processes.
        store.update_task(task_id, status="running")
        return task
    except Exception:
        if store.conn.in_transaction:
            store.conn.rollback()
        raise


def approve(cfg, store, task_id: int, note: str | None = None):
    """Approve one pending task and retain an optional owner instruction."""
    del cfg
    task = _require_task(store, task_id)
    if task["status"] != "pending":
        raise TaskError(f"task {task_id} must have status pending")
    updated = store.update_task(task_id, status="approved", owner_note=note)
    store.log_event("info", "Task approved", task_id=task_id,
                    recording_id=task["recording_id"])
    return updated


def reject(cfg, store, task_id: int, note: str | None = None):
    """Reject one pending task and retain the owner's reason, when supplied."""
    del cfg
    task = _require_task(store, task_id)
    if task["status"] != "pending":
        raise TaskError(f"task {task_id} must have status pending")
    updated = store.update_task(task_id, status="rejected", owner_note=note)
    store.log_event("info", "Task rejected", task_id=task_id,
                    recording_id=task["recording_id"])
    return updated


def _context(cfg, store, task) -> tuple[str, str, str]:
    recording_id = task["recording_id"]
    if recording_id is None:
        return "(No recording context.)", "(No call summary available.)", \
            "(No analysis artifact available.)"

    recording = store.get_recording(recording_id)
    metadata = _metadata(recording) if recording is not None else "(Recording unavailable.)"
    artifact = store.latest_artifact(recording_id, "analysis")
    if artifact is None:
        return metadata, "(No call summary available.)", \
            "(No analysis artifact available.)"

    path = _artifact_path(cfg, artifact).resolve()
    try:
        analysis = json.loads(path.read_text(encoding="utf-8"))
        summary = str(analysis.get("summary") or "(No call summary available.)")
    except (OSError, ValueError, TypeError):
        summary = "(Analysis could not be read.)"
    return metadata, summary, str(path)


def _atomic_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=f".{path.name}.", suffix=".tmp",
                                         delete=False) as output:
            temporary = Path(output.name)
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _result_markdown(task, result: dict, status: str, log_path: Path | None) -> str:
    lines = [
        f"# Task {task['id']}: {task['title']}",
        "",
        f"Status: {status}",
        f"Project: {result['project'] or 'scratch workspace'}",
        f"Commit: {result['commit'] or 'none'}",
        f"Pushed: {'yes' if result['pushed'] else 'no'}",
        f"Agent log: {log_path if log_path is not None else 'none'}",
        "",
        "## Report",
        "",
        result["report"] or "No report was produced.",
    ]
    if result["files"]:
        lines.extend(["", "## Files", "", *[f"- {name}" for name in result["files"]]])
    if result["error"]:
        lines.extend(["", "## Error", "", result["error"]])
    return "\n".join(lines) + "\n"


def run_task(cfg, store, task_id: int, *, runner=run_agent) -> dict:
    """Run one approved task, persist its result, and reach a terminal state."""
    task = _claim_approved(store, task_id)

    project = None
    try:
        project = get_project(cfg.projects, task["project"]) if task["project"] else None
        if project is None:
            cwd = (cfg.data_dir / "workspaces" / f"task-{task_id}").resolve()
            cwd.mkdir(parents=True, exist_ok=True)
            if task["project"]:
                project_id = f"scratch workspace for unknown project {task['project']}"
            else:
                project_id = "scratch workspace (task has no project)"
        else:
            cwd = project.path.resolve()
            project_id = project.id

        metadata, summary, analysis_path = _context(cfg, store, task)
        prompt = _render("run_task.md", {
            "title": str(task["title"]),
            "instructions": str(task["instructions"]),
            "reason": str(task["reason"] or "none"),
            "owner_note": str(task["owner_note"] or "none"),
            "metadata": metadata,
            "summary": summary,
            "analysis_path": analysis_path,
            "owner_name": cfg.analyze.owner_name,
            "language": cfg.analyze.language,
            "project_id": project_id,
        })
        result, agent_ok = _run_work(cfg, project, prompt, cwd=cwd, kind="task",
                                     identifier=task_id, runner=runner)
        if not agent_ok and not result.get("error"):
            result["error"] = "Agent did not produce a valid successful result"
    except Exception as exc:
        result = {
            "project": project.id if project is not None else None,
            "files": [],
            "commit": None,
            "pushed": False,
            "report": "",
            "error": str(exc),
        }

    status = "failed" if result["error"] else "done"
    json_path = (cfg.data_dir / "tasks" / f"{task_id}.json").resolve()
    markdown_path = json_path.with_suffix(".md")
    logs = sorted((cfg.data_dir / "logs" / "agent").glob(f"*-task-{task_id}.log"))
    log_path = logs[-1].resolve() if logs else None
    try:
        _atomic_json(json_path, result)
        _atomic_text(markdown_path, _result_markdown(task, result, status, log_path))
        store.update_task(task_id, status=status, result_path=str(json_path))
    except Exception as exc:
        store.update_task(task_id, status="failed")
        store.log_event("error", f"Task result could not be saved: {exc}",
                        task_id=task_id, recording_id=task["recording_id"])
        raise TaskError(str(exc)) from exc

    message = "Task completed" if status == "done" else f"Task failed: {result['error']}"
    store.log_event("info" if status == "done" else "error", message,
                    task_id=task_id, recording_id=task["recording_id"])
    return result


def run_approved(cfg, store, *, runner=run_agent) -> list[int]:
    """Run the currently approved queue in task order."""
    task_ids = [int(task["id"]) for task in store.list_tasks(["approved"])]
    for task_id in task_ids:
        try:
            run_task(cfg, store, task_id, runner=runner)
        except TaskError:
            # run_task records a terminal failure before raising.  One broken
            # result file must not starve the rest of the approval queue.
            continue
    return task_ids


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("tasks", help="review and run approval tasks")
    parser.add_argument("--all", action="store_true", help="include non-pending tasks")
    parser.add_argument("--json", action="store_true", help="print JSON")
    parser.set_defaults(func=_list_command)
    commands = parser.add_subparsers(dest="tasks_command")

    show = commands.add_parser("show", help="show one task")
    show.add_argument("id", type=int)
    show.set_defaults(func=_show_command)

    approve_parser = commands.add_parser("approve", help="approve one pending task")
    approve_parser.add_argument("id", type=int)
    approve_parser.add_argument("-m", "--message", dest="note")
    approve_parser.add_argument("--now", action="store_true", help="run immediately")
    approve_parser.set_defaults(func=_approve_command)

    reject_parser = commands.add_parser("reject", help="reject one pending task")
    reject_parser.add_argument("id", type=int)
    reject_parser.add_argument("-m", "--message", dest="note")
    reject_parser.set_defaults(func=_reject_command)

    run_parser = commands.add_parser("run", help="run one approved task")
    run_parser.add_argument("id", type=int)
    run_parser.set_defaults(func=_run_command)


def _task_view(task) -> dict:
    return {
        "id": int(task["id"]),
        "status": task["status"],
        "project": task["project"],
        "title": task["title"],
        "created": task["created_at"],
    }


def _list_command(args, cfg) -> int:
    with Store.open(cfg) as store:
        tasks = store.list_tasks() if args.all else store.list_tasks(["pending"])
    views = [_task_view(task) for task in tasks]
    if args.json:
        print(json.dumps(views, ensure_ascii=False, indent=2))
    else:
        for task in views:
            print(f"{task['id']}\t{task['status']}\t{task['project'] or '-'}\t"
                  f"{task['title']}\t{task['created']}")
    return 0


def _show_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            task = _require_task(store, args.id)
            data = dict(task)
            if task["result_path"]:
                path = Path(task["result_path"])
                if not path.is_absolute():
                    path = cfg.data_dir / path
                if path.is_file():
                    data["result"] = json.loads(path.read_text(encoding="utf-8"))
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    except (TaskError, OSError, ValueError) as exc:
        print(f"task error: {exc}", file=sys.stderr)
        return 1


def _approve_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            approve(cfg, store, args.id, args.note)
            result = run_task(cfg, store, args.id) if args.now else None
        if result is None:
            print(f"Approved task {args.id}")
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result is None or not result["error"] else 1
    except (TaskError, OSError, ValueError) as exc:
        print(f"task error: {exc}", file=sys.stderr)
        return 1


def _reject_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            reject(cfg, store, args.id, args.note)
        print(f"Rejected task {args.id}")
        return 0
    except (TaskError, OSError, ValueError) as exc:
        print(f"task error: {exc}", file=sys.stderr)
        return 1


def _run_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            result = run_task(cfg, store, args.id)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if not result["error"] else 1
    except (TaskError, OSError, ValueError) as exc:
        print(f"task error: {exc}", file=sys.stderr)
        return 1
