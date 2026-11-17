"""Turn stored transcript JSON into validated, project-aware call analyses."""
from __future__ import annotations

import copy
import json
import os
import sys
import tempfile
from datetime import datetime
from importlib import resources
from pathlib import Path

from .agent import run_agent
from .jsonschema_lite import validate
from .projects import add_commands as add_project_commands, list_projects
from .store import Store


class AnalysisError(RuntimeError):
    """Analysis could not complete; stage failures have been recorded in the store."""


def build_prompt(cfg, recording_row, transcript: dict, projects) -> str:
    """Render the packaged template without importing an ASR implementation."""
    row = dict(recording_row)
    metadata = "\n".join(
        f"{label}: {row.get(key) if row.get(key) is not None else 'unknown'}"
        for label, key in (("source", "source"), ("app", "app"),
                           ("counterpart", "counterpart"), ("started_at", "started_at"),
                           ("duration", "duration_s"))
    )
    lines = []
    for segment in transcript.get("segments", []):
        seconds = max(0, int(segment["start"]))
        stamp = f"[{seconds // 60:02d}:{seconds % 60:02d}]"
        speaker = f" {segment['speaker']}:" if segment.get("speaker") else ""
        lines.append(f"{stamp}{speaker} {segment['text']}")
    rendered = "\n".join(lines) if lines else transcript.get("text", "")
    if len(rendered) > 120_000:
        marker = "\n\n[... transcript truncated; middle omitted ...]\n\n"
        budget = 120_000 - len(marker)
        rendered = rendered[:budget // 2] + marker + rendered[-(budget - budget // 2):]
    values = {
        "owner_name": cfg.analyze.owner_name,
        "language": cfg.analyze.language,
        "metadata": metadata,
        "projects": "\n".join(f"- {p.id} — {p.description}" for p in projects)
                    or "(No known projects.)",
        "transcript": rendered,
    }
    template = resources.files("phone2agent").joinpath("prompts/analyze.md").read_text(
        encoding="utf-8")
    for name, value in values.items():
        template = template.replace("{{" + name + "}}", value)
    return template


def _artifact_path(cfg, artifact) -> Path:
    path = Path(artifact["path"])
    return path if path.is_absolute() else cfg.data_dir / path


def _atomic_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=f".{path.name}.", suffix=".tmp",
                                         delete=False) as output:
            temporary = Path(output.name)
            json.dump(data, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def analyze_recording(cfg, store, recording_id, *, runner=run_agent) -> dict:
    """Persist one analysis; raise AnalysisError on failure after recording backoff."""
    row = store.get_recording(recording_id)
    if row is None:
        raise AnalysisError(f"recording {recording_id} not found")
    if row["status"] != "transcribed":
        raise AnalysisError(f"recording {recording_id} must have status transcribed")
    try:
        artifact = store.latest_artifact(recording_id, "transcript")
        if artifact is None:
            raise AnalysisError("recording has no transcript artifact")
        transcript = json.loads(_artifact_path(cfg, artifact).read_text(encoding="utf-8"))
        projects = list_projects(cfg.projects)
        prompt = build_prompt(cfg, row, transcript, projects)
        cfg.data_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().astimezone().strftime("%Y%m%dT%H%M%S%f%z")
        log_path = cfg.data_dir / "logs" / "agent" / f"{stamp}-analyze-{recording_id}.log"
        schema = resources.files("phone2agent").joinpath("schemas/analysis.schema.json")
        with resources.as_file(schema) as schema_path:
            result = runner(cfg.agent, prompt, cwd=cfg.data_dir, mode="read-only",
                            output_schema=schema_path, log_path=log_path)
            if not result.ok:
                raise AnalysisError(
                    f"agent failed (return code {result.returncode}); see {result.log_path}")
            # Check injected runners too; only contract-valid JSON can reach disk.
            validate(result.json, json.loads(schema_path.read_text(encoding="utf-8")))
        analysis = copy.deepcopy(result.json)
        known = {project.id for project in projects}
        corrections = []
        for collection in ("items", "actions", "routes"):
            for index, entry in enumerate(analysis[collection]):
                project = entry["project"]
                if project is not None and project not in known:
                    corrections.append({"path": f"$.{collection}[{index}].project",
                                        "project": project,
                                        "action": "dropped" if collection == "routes" else "set_null"})
                    if collection != "routes":
                        entry["project"] = None
        analysis["routes"] = [route for route in analysis["routes"] if route["project"] in known]
        path = (cfg.data_dir / "analyses" / f"{recording_id}.json").resolve()
        _atomic_json(path, analysis)
        store.add_artifact(recording_id, "analysis", path,
                           meta={"unknown_projects": corrections, "log_path": str(result.log_path),
                                 "duration_s": result.duration_s})
        store.update_recording(recording_id, status="analyzed", failed_stage=None,
                               error=None, next_try_at=None)
        return analysis
    except Exception as exc:
        store.mark_failed(recording_id, "analyze", str(exc), cfg.pipeline.max_attempts)
        raise AnalysisError(str(exc)) from exc


def add_commands(subparsers) -> None:
    # cli.py discovers analyze; delegate the registry command without changing it.
    add_project_commands(subparsers)
    parser = subparsers.add_parser("analyze", help="analyze one transcribed recording")
    parser.add_argument("id", type=int)
    parser.add_argument("--print", dest="print_json", action="store_true", help="print analysis JSON")
    parser.set_defaults(func=_analyze_command)
    parser = subparsers.add_parser("show", help="show a recording, artifacts and analysis")
    parser.add_argument("id", type=int)
    parser.set_defaults(func=_show_command)


def _analyze_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            analysis = analyze_recording(cfg, store, args.id)
        if args.print_json:
            print(json.dumps(analysis, ensure_ascii=False, indent=2))
        else:
            print(f"Analyzed recording {args.id}: {analysis['title']}")
        return 0
    except (AnalysisError, OSError) as exc:
        print(f"analysis error: {exc}", file=sys.stderr)
        return 1


def _show_command(args, cfg) -> int:
    try:
        with Store.open(cfg) as store:
            row = store.get_recording(args.id)
            if row is None:
                raise AnalysisError(f"recording {args.id} not found")
            print(json.dumps(dict(row), ensure_ascii=False, indent=2))
            print("Artifacts:")
            for artifact in store.artifacts(args.id):
                print(f"  {artifact['kind']}: {artifact['path']}")
            artifact = store.latest_artifact(args.id, "analysis")
            if artifact is not None:
                analysis = json.loads(_artifact_path(cfg, artifact).read_text(encoding="utf-8"))
                print(f"\n{analysis['title']}\n{analysis['summary']}")
                for item in analysis["items"]:
                    print(f"- [{item['kind']}] {item['text']}")
        return 0
    except (AnalysisError, OSError, ValueError, KeyError) as exc:
        print(f"show error: {exc}", file=sys.stderr)
        return 1
