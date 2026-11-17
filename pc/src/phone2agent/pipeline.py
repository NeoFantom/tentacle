"""Sequential recording orchestration, daemon lifecycle, and diagnostics."""
from __future__ import annotations

import fcntl
import importlib
import json
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
from contextlib import ExitStack, contextmanager, redirect_stdout
from datetime import datetime
from pathlib import Path

from . import asr, config, sync, timeutil
from .analyze import analyze_recording
from .dispatch import dispatch_recording
from .notify import notify
from .phone import PhoneClient
from .projects import list_projects
from .store import Store
from .tasks import run_approved


class PipelineError(RuntimeError):
    """A pipeline operation could not complete."""


class AlreadyRunning(PipelineError):
    """Another run or daemon holds the data directory lock."""


@contextmanager
def single_instance(cfg):
    """Hold a process lock; leave the lock file in place to preserve its inode."""
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    with (cfg.data_dir / "p2a.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise AlreadyRunning("Another p2a run or daemon is already running for this data directory") from exc
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def transcribe_recording(cfg, store, recording_id):
    """Write transcript artifacts, or skip silence; persist errors before raising."""
    row = store.get_recording(recording_id)
    if row is None or row["status"] != "downloaded":
        raise PipelineError(f"recording {recording_id} must exist with status downloaded")
    try:
        if not row["local_path"]:
            raise PipelineError("recording has no local audio path")
        transcript = asr.transcribe(Path(row["local_path"]), cfg.asr)
        json_path = (cfg.data_dir / "transcripts" / f"{recording_id}.json").resolve()
        asr.write_transcript(transcript, json_path, json_path.with_suffix(".md"))
        speakers = {s["speaker"] for s in transcript["segments"] if s["speaker"] is not None}
        store.add_artifact(recording_id, "transcript", json_path, meta={
            "backend": transcript["backend"], "model": transcript["model"],
            "speakers": len(speakers), "duration_s": transcript["duration_s"],
        })
        silent = not (transcript["text"].strip() or
                      any(s["text"].strip() for s in transcript["segments"]))
        fields = {"status": "skipped" if silent else "transcribed",
                  "failed_stage": None, "error": None, "next_try_at": None}
        if row["duration_s"] is None:
            fields["duration_s"] = transcript["duration_s"]
        store.update_recording(recording_id, **fields)
        if silent:
            store.log_event("info", "Empty transcript (silence); skipped", recording_id=recording_id)
        return transcript
    except Exception as exc:
        store.mark_failed(recording_id, "transcribe", str(exc), cfg.pipeline.max_attempts)
        raise PipelineError(str(exc)) from exc


def _artifact_json(cfg, artifact):
    path = Path(artifact["path"])
    return json.loads((path if path.is_absolute() else cfg.data_dir / path).read_text(encoding="utf-8"))


def _notify_filed(cfg, store, recording_id):
    try:
        artifact = store.latest_artifact(recording_id, "analysis")
        title = _artifact_json(cfg, artifact)["title"] if artifact else f"Recording {recording_id}"
        lines = [title]
        filings = {}
        for artifact in store.artifacts(recording_id, "filing"):
            filing = _artifact_json(cfg, artifact)
            filings[filing["project"]] = filing
        for filing in filings.values():
            lines.append(f"{filing['project']}: commit={filing['commit'] or 'none'}, "
                         f"pushed={'yes' if filing['pushed'] else 'no'}"
                         + (f"; error={filing['error']}" if filing.get("error") else ""))
        if not filings:
            lines.append("Projects: none; saved to inbox; commit=none, pushed=no")
        notify(cfg, "Recording filed", "\n".join(lines))
    except Exception as exc:
        print(f"Filing notification could not be prepared: {exc}", file=sys.stderr)


def run_once(cfg, store, client, *, transcribe_fn=transcribe_recording,
             analyze_fn=analyze_recording, dispatch_fn=dispatch_recording,
             run_approved_fn=run_approved, now=None, stop_requested=None) -> dict:
    """Advance each due recording fully, isolating failures and preserving backoff.

    ``now`` controls due selection (ISO string or datetime). Stage functions own
    their failure timestamps. A stop request is honored between recordings.
    """
    stopped = stop_requested if stop_requested is not None else lambda: False
    pending_before = {row["id"] for row in store.list_tasks(["pending"])}
    failures_before = {row["id"]: row["attempts"] for row in store.list_recordings("failed")}
    counts = {"sync": {}, "downloaded": 0, "transcribed": 0, "analyzed": 0,
              "filed": 0, "skipped": 0, "tasks_run": 0, "pending_tasks": 0, "errors": 0}
    try:
        counts["sync"] = sync.sync_once(cfg, store, client)
        counts["downloaded"] = counts["sync"].get("downloaded", 0)
        counts["errors"] += counts["sync"].get("errors", 0)
        store.log_event("info", "Sync completed: " + json.dumps(counts["sync"], sort_keys=True))
    except Exception as exc:
        counts["errors"] += 1
        store.log_event("error", f"Sync failed: {exc}")
    # Download failures belong to sync, which returns scan errors separately.
    counts["errors"] += sum(row["failed_stage"] == "download" and
                             row["attempts"] > failures_before.get(row["id"], 0)
                             for row in store.list_recordings("failed"))
    current = now.isoformat() if isinstance(now, datetime) else now or timeutil.now_iso()
    stages = {"downloaded": ("transcribe", "transcribed", transcribe_fn),
              "transcribed": ("analyze", "analyzed", analyze_fn),
              "analyzed": ("dispatch", "filed", dispatch_fn)}
    retry_status = {stage: status for status, (stage, _, _) in stages.items()}
    due = store.due_recordings([*stages, "failed"], current)
    order = {status: index for index, status in enumerate(stages)}
    due.sort(key=lambda row: (order.get(retry_status.get(row["failed_stage"])
                                      if row["status"] == "failed" else row["status"], 99), row["id"]))
    for original in due:
        if stopped():
            break
        recording_id = original["id"]
        if original["status"] == "failed":
            status = retry_status.get(original["failed_stage"])
            if status is None:  # Download retries are owned by sync.
                continue
            # reset_for_retry is for explicit operator retries: it clears attempts.
            store.update_recording(recording_id, status=status, failed_stage=None,
                                   error=None, next_try_at=None)
        while True:
            before = store.get_recording(recording_id)
            if before["status"] not in stages:
                break
            stage, expected, function = stages[before["status"]]
            try:
                function(cfg, store, recording_id)
                after = store.get_recording(recording_id)
                if after["status"] == "failed":
                    counts["errors"] += 1
                    break
                if stage == "transcribe" and after["status"] == "skipped":
                    counts["skipped"] += 1
                    break
                if after["status"] != expected:
                    raise PipelineError(f"{stage} did not advance recording {recording_id} to {expected}")
            except Exception as exc:
                after = store.get_recording(recording_id)
                # Existing stage implementations mark failures before raising.
                if after["status"] != "failed" or after["attempts"] == before["attempts"]:
                    store.mark_failed(recording_id, stage, str(exc), cfg.pipeline.max_attempts)
                store.log_event("error", f"{stage} failed: {exc}", recording_id=recording_id)
                counts["errors"] += 1
                break
            counts[expected] += 1
            if expected == "filed":
                _notify_filed(cfg, store, recording_id)

    if not stopped():
        try:
            task_ids = run_approved_fn(cfg, store)
            counts["tasks_run"] = len(task_ids)
            counts["errors"] += sum(store.get_task(task_id)["status"] == "failed" for task_id in task_ids)
        except Exception as exc:
            counts["errors"] += 1
            store.log_event("error", f"Approved tasks failed: {exc}")
    new_pending = [row for row in store.list_tasks(["pending"]) if row["id"] not in pending_before]
    counts["pending_tasks"] = len(new_pending)
    if new_pending:
        notify(cfg, "Tasks awaiting approval", "\n".join(
            f"{row['title']} — p2a tasks approve {row['id']}" for row in new_pending))
    for row in store.list_recordings("failed"):
        if row["next_try_at"] is None and row["attempts"] > failures_before.get(row["id"], 0):
            notify(cfg, "Recording failed permanently",
                   f"Recording {row['id']} ({row['phone_path']}), stage={row['failed_stage']}, "
                   f"attempts={row['attempts']}: {row['error']}")
    return counts


def _summary(counts):
    return " ".join(f"{key}={value}" for key, value in counts.items() if key != "sync")


def daemon(cfg, store=None, client=None, *, run_once_fn=run_once) -> int:
    """Run until SIGTERM/SIGINT, finishing an active recording before exiting."""
    stop = threading.Event()
    previous = {}
    try:
        with ExitStack() as stack:
            stack.enter_context(single_instance(cfg))
            for signum in (signal.SIGTERM, signal.SIGINT):
                previous[signum] = signal.signal(signum, lambda *_: stop.set())
            if store is None:
                store = stack.enter_context(Store.open(cfg))
            if client is None:
                client = PhoneClient(cfg.phone)
            while not stop.is_set():
                with redirect_stdout(sys.stderr):
                    counts = run_once_fn(cfg, store, client, stop_requested=stop.is_set)
                print(_summary(counts), flush=True)
                stop.wait(cfg.sync.interval_s)
        return 0
    except AlreadyRunning as exc:
        print(str(exc), file=sys.stderr)
        return 3
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def doctor(cfg) -> list[tuple[str, bool, str]]:
    """Probe configured dependencies without loading any ASR models."""
    checks = []

    def check(name, probe):
        try:
            detail = probe()
            checks.append((name, True, str(detail)))
        except Exception as exc:
            checks.append((name, False, str(exc)))

    def check_config():
        if cfg.path is None:
            raise PipelineError("No config file path; configuration was supplied in memory")
        config.load(cfg.path)
        return f"{cfg.path}: parsed"

    def writable():
        cfg.data_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(dir=cfg.data_dir) as probe:
            probe.write(b"p2a")
            probe.flush()
        return cfg.data_dir

    def binary(name):
        resolved = shutil.which(name) if name else None
        if resolved is None:
            raise PipelineError(f"Command not found: {name or '(empty command)'}")
        return resolved

    def ping():
        reply = client.ping()
        detail = f"device={reply.get('device', 'unknown')}, all_files_access={reply.get('all_files_access')}"
        if not reply.get("all_files_access"):
            raise PipelineError(detail)
        return detail

    check("config", check_config)
    check("data_dir", writable)
    client = None
    try:
        client = PhoneClient(cfg.phone)
        paired = bool(client.url and client.token)
        checks.append(("phone paired", paired, "Configured" if paired else "Phone URL or token missing"))
    except Exception as exc:
        checks.append(("phone paired", False, str(exc)))
    check("phone ping", ping)
    for source in cfg.sources:
        def source_dir(source=source):
            reply = client.stat(source.path)
            if reply["type"] != "dir":
                raise PipelineError(f"Not a directory: {source.path}")
            return source.path
        check(f"source {source.name}", source_dir)

    modules = {}

    def backend():
        name = cfg.asr.backend
        dependencies = {"funasr": ("funasr", "torch", "torchaudio", "av", "numpy"),
                        "faster-whisper": ("faster_whisper", "ctranslate2", "av", "numpy"),
                        "command": (), "none": ()}
        if name not in dependencies:
            raise PipelineError(f"Unknown ASR backend: {name}")
        if name == "command":
            return binary(cfg.asr.command[0] if cfg.asr.command else "")
        for dependency in dependencies[name]:
            modules[dependency] = importlib.import_module(dependency)
        return f"{name}: importable" if name != "none" else "ASR disabled"

    check("ASR backend", backend)
    if cfg.asr.backend in {"funasr", "faster-whisper"} and cfg.asr.device != "cpu":
        def cuda():
            if cfg.asr.backend == "funasr":
                available = modules.get("torch") or importlib.import_module("torch")
                visible = available.cuda.is_available()
            else:
                available = modules.get("ctranslate2") or importlib.import_module("ctranslate2")
                visible = available.get_cuda_device_count() > 0
            detail = f"CUDA visible={bool(visible)}, requested device={cfg.asr.device}"
            if cfg.asr.device.startswith("cuda") and not visible:
                raise PipelineError(detail)
            return detail
        check("CUDA", cuda)

    def agent():
        name = (cfg.agent.command[0] if cfg.agent.command else "") if cfg.agent.kind == "command" else cfg.agent.binary
        executable = binary(name)
        result = subprocess.run([executable, "--version"], capture_output=True, text=True,
                                timeout=30, check=True, stdin=subprocess.DEVNULL)
        return f"{executable}: {(result.stdout.strip() or result.stderr.strip())[:500]}"

    check("agent", agent)
    for root in [*cfg.projects.root_paths(), *cfg.projects.extra_paths()]:
        def project_dir(root=root):
            if not root.is_dir():
                raise PipelineError(f"Project directory missing: {root}")
            return root
        check(f"project root {root}", project_dir)
    check("projects", lambda: f"{len(list_projects(cfg.projects))} project(s)")
    check("git", lambda: binary("git"))
    if cfg.notify.command:
        check("notify", lambda: binary(cfg.notify.command[0]))
    return checks


def status(store) -> dict:
    last_sync = store.conn.execute(
        "SELECT at FROM events WHERE message LIKE 'Sync %' ORDER BY id DESC LIMIT 1").fetchone()
    return {"counts": store.counts_by_status(), "last_sync": last_sync["at"] if last_sync else None,
            "pending_tasks": len(store.list_tasks(["pending"])),
            "events": [dict(row) for row in store.recent_events(10)],
            "failed": [dict(row) for row in store.list_recordings("failed")]}


def _run_command(args, cfg):
    try:
        with single_instance(cfg), Store.open(cfg) as store:
            with redirect_stdout(sys.stderr):
                counts = run_once(cfg, store, PhoneClient(cfg.phone))
        print(json.dumps(counts, ensure_ascii=False, indent=2) if args.json else _summary(counts))
        return int(bool(counts["errors"]))
    except AlreadyRunning as exc:
        print(str(exc), file=sys.stderr)
        return 3
    except (OSError, sqlite3.Error) as exc:
        print(f"Pipeline error: {exc}", file=sys.stderr)
        return 1


def _status_command(args, cfg):
    try:
        with Store.open(cfg) as store:
            result = status(store)
        if args.json:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print("Counts: " + (" ".join(f"{key}={value}" for key, value in result["counts"].items()) or "none"))
            print(f"Last sync: {result['last_sync'] or 'never'}")
            print(f"Pending tasks: {result['pending_tasks']}")
            for row in result["failed"]:
                print(f"Failed {row['id']}: stage={row['failed_stage']} error={row['error']} "
                      f"next try={row['next_try_at'] or 'exhausted'}")
            for event in result["events"]:
                print(f"{event['at']} {event['level']}: {event['message']}")
        return 0
    except (OSError, sqlite3.Error) as exc:
        print(f"Status error: {exc}", file=sys.stderr)
        return 1


def _retry_command(args, cfg):
    try:
        with Store.open(cfg) as store:
            row = store.reset_for_retry(args.id)
        print(f"Recording {args.id} reset to {row['status']}")
        return 0
    except (KeyError, ValueError, OSError, sqlite3.Error) as exc:
        print(f"Retry error: {exc}", file=sys.stderr)
        return 1


def _doctor_command(args, cfg):
    # Load here so malformed configuration is a diagnostic failure (exit 1).
    try:
        if cfg is None:
            cfg = config.load(args.config)
        with redirect_stdout(sys.stderr):
            checks = doctor(cfg)
    except (config.ConfigError, OSError, ValueError) as exc:
        checks = [("config", False, str(exc))]
    if args.json:
        print(json.dumps([{"check": name, "ok": ok, "detail": detail}
                          for name, ok, detail in checks], ensure_ascii=False, indent=2))
    else:
        for name, ok, detail in checks:
            print(f"{'✓' if ok else '✗'} {name}\t{detail}")
    return int(any(not ok for _, ok, _ in checks))


def add_commands(subparsers):
    for name, handler, help_text in (
        ("run", _run_command, "run one sync, recording, and task pass"),
        ("daemon", lambda args, cfg: daemon(cfg), "process recordings until stopped"),
        ("status", _status_command, "show pipeline state and recent events"),
        ("retry", _retry_command, "reset a recording for retry"),
        ("doctor", _doctor_command, "check configuration and dependencies"),
    ):
        parser = subparsers.add_parser(name, help=help_text)
        parser.set_defaults(func=handler)
        if name in {"run", "status", "doctor"}:
            parser.add_argument("--json", action="store_true", help="print JSON")
        if name == "retry":
            parser.add_argument("id", type=int)
        if name == "doctor":
            parser.set_defaults(needs_config=False)
