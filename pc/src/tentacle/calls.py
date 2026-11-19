"""Manual call-recording discovery, download, and transcription commands."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from contextlib import redirect_stdout
from dataclasses import dataclass
from datetime import datetime, tzinfo
from pathlib import Path, PurePosixPath
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import asr
from .asr.base import AsrError, validate_transcript
from .config import Config, SourceConfig
from .phone import PhoneClient, PhoneError
from .timeutil import from_ms, parse_since, to_ms

_APPS = {
    "微信": "wechat", "企业微信": "wecom", "飞书": "feishu", "qq": "qq",
    "钉钉": "dingtalk", "腾讯会议": "tencent-meeting",
}


@dataclass(frozen=True)
class TranscriptFiles:
    json_path: Path
    text_path: Path


def parse_name(filename: str, source: SourceConfig, mtime_ms: int, tz=None) -> dict:
    """Extract recording metadata using the configured source naming rule."""
    groups: dict[str, str | None] = {}
    has_app = False
    if source.name_pattern:
        try:
            pattern = re.compile(source.name_pattern)
        except re.error:
            pass
        else:
            has_app = "app" in pattern.groupindex
            match = pattern.search(PurePosixPath(filename).stem)
            if match:
                groups = match.groupdict()
    app: str | None = (groups.get("app") or "").strip().lower()
    app = _APPS.get(app, app) if app else None
    if app is None and source.name == "call" and not has_app:
        app = "phone"
    started = from_ms(mtime_ms, tz)
    timestamp = groups.get("ts")
    if timestamp and source.ts_format:
        try:
            parsed = datetime.strptime(timestamp, source.ts_format)
            if tz is None:
                started = parsed.astimezone()
            elif parsed.tzinfo is None:
                started = parsed.replace(tzinfo=tz)
            else:
                started = parsed.astimezone(tz)
        except (ValueError, OverflowError):
            pass
    return {"app": app, "counterpart": groups.get("counterpart") or None,
            "started_at": started.isoformat(timespec="seconds")}


def _zone(name: str | None):
    if name:
        try:
            return ZoneInfo(name)
        except (ZoneInfoNotFoundError, ValueError, TypeError):
            pass
    return None


def _source_for_path(cfg: Config, phone_path: str, roots: list[dict]) -> SourceConfig | None:
    path = PurePosixPath(phone_path)
    primary = next((root for root in roots if root.get("id") == "primary"),
                   roots[0] if roots else None)
    for source in cfg.sources:
        source_path = PurePosixPath(source.path)
        if not source_path.is_absolute():
            if primary is None or not isinstance(primary.get("path"), str):
                continue
            source_path = PurePosixPath(primary["path"]) / source_path
        if path == source_path or path.is_relative_to(source_path):
            return source
    return None


def _cached_recording(cfg: Config, source: SourceConfig, entry: dict) -> Path | None:
    path = cfg.data_dir / "recordings" / source.name / PurePosixPath(entry["path"]).name
    try:
        return path if path.is_file() and path.stat().st_size == entry["size"] else None
    except OSError:
        return None


def list_calls(
    cfg: Config, client: PhoneClient, *, since: str | None = "7d", until: str | None = None,
    source_name: str | None = None, with_text: str | None = None, app: str | None = None,
    limit: int = 50,
) -> list[dict]:
    if limit < 1 or limit > 5000:
        raise ValueError("limit must be between 1 and 5000")
    selected = [source for source in cfg.sources if source_name in (None, source.name)]
    if source_name is not None and not selected:
        raise ValueError(f"unknown source: {source_name}")
    ping = client.ping()
    phone_now_ms = ping.get("time_ms")
    if not isinstance(phone_now_ms, (int, float)):
        phone_now_ms = to_ms(datetime.now().astimezone())
    tz: tzinfo | None
    if cfg.calls.timezone:
        try:
            tz = ZoneInfo(cfg.calls.timezone)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError(f"invalid [calls].timezone: {cfg.calls.timezone!r}") from exc
    else:
        tz = client.phone_timezone() if hasattr(client, "phone_timezone") else _zone(ping.get("timezone"))
    now = from_ms(phone_now_ms, tz)
    since_ms = to_ms(parse_since(since, now)) if since else None
    until_ms = to_ms(parse_since(until, now)) if until else None
    if since_ms is not None and until_ms is not None and since_ms > until_ms:
        raise ValueError("--since must not be later than --until")
    rows: list[dict] = []
    for source in selected:
        result = client.find(root=source.path, ext=source.extensions, since_ms=since_ms,
                             until_ms=until_ms, type="file", limit=limit, sort="mtime_desc")
        for entry in result.get("entries", []):
            metadata = parse_name(entry["name"], source, entry["mtime_ms"], tz)
            counterpart = metadata["counterpart"]
            if with_text and with_text.casefold() not in (counterpart or "").casefold():
                continue
            if app and metadata["app"] != app:
                continue
            local = _cached_recording(cfg, source, entry)
            age_ms = phone_now_ms - entry["mtime_ms"]
            rows.append({**metadata, "size": entry["size"], "source": source.name,
                         "path": entry["path"], "local": str(local) if local else None,
                         "recording": -source.min_age_s * 1000 <= age_ms < source.min_age_s * 1000,
                         "_mtime_ms": entry["mtime_ms"]})
    rows.sort(key=lambda row: row["_mtime_ms"], reverse=True)
    for row in rows:
        del row["_mtime_ms"]
    return rows


def pull_paths(cfg: Config, client: PhoneClient, phone_paths: list[str],
               destination: Path | None = None) -> list[Path]:
    output: list[Path] = []
    roots = client.ping().get("roots", [])
    if not isinstance(roots, list):
        roots = []
    for requested in phone_paths:
        entry = client.stat(requested)
        if entry.get("type", "file") != "file":
            raise ValueError(f"not a file: {requested}")
        source = _source_for_path(cfg, entry["path"], roots)
        target_dir = destination or cfg.data_dir / "recordings" / (source.name if source else "other")
        target = target_dir / PurePosixPath(entry["path"]).name
        if not (target.is_file() and target.stat().st_size == entry["size"]):
            client.download(entry["path"], target, resume=True, expected_size=entry["size"])
        output.append(target)
    return output


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(128 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _timestamp(seconds: float) -> str:
    hours, remainder = divmod(int(seconds), 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes:02d}:{secs:02d}"


def transcript_text(transcript: dict) -> str:
    lines = []
    for segment in transcript["segments"]:
        text = " ".join(segment["text"].split())
        if not text:
            continue
        speaker = f"{segment['speaker']}: " if segment["speaker"] else ""
        lines.append(f"[{_timestamp(segment['start'])}] {speaker}{text}")
    return "\n".join(lines) + ("\n" if lines else "")


def _atomic_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".part", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def transcribe_path(
    cfg: Config, client: PhoneClient, value: str, *, force: bool = False,
    output_prefix: Path | None = None,
) -> TranscriptFiles:
    candidate = Path(value)
    is_phone_path = value.startswith("/storage/") or (not candidate.is_absolute() and not candidate.exists())
    if is_phone_path:
        audio, = pull_paths(cfg, client, [value])
    else:
        audio = candidate
        if not audio.is_file():
            raise ValueError(f"local audio file not found: {audio}")
    export_paths: tuple[Path, Path] | None = None
    if output_prefix is not None:
        export_paths = (Path(f"{output_prefix}.json"), Path(f"{output_prefix}.txt"))
        if audio.resolve() in {path.resolve() for path in export_paths}:
            raise ValueError("transcript output would overwrite the input audio")
    digest = _sha256(audio)
    cache_dir = cfg.data_dir / "transcripts"
    files = TranscriptFiles(cache_dir / f"{digest}.json", cache_dir / f"{digest}.txt")
    if force or not (files.json_path.is_file() and files.text_path.is_file()):
        with redirect_stdout(sys.stderr):
            transcript = validate_transcript(asr.transcribe(audio, cfg.asr))
        _atomic_text(files.json_path, json.dumps(
            transcript, ensure_ascii=False, indent=2, allow_nan=False,
        ) + "\n")
        _atomic_text(files.text_path, transcript_text(transcript))
    if export_paths is not None:
        for cached, exported in zip((files.json_path, files.text_path), export_paths):
            if cached.resolve() == exported.resolve():
                continue
            exported.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(cached, exported)
    return files


def _size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return f"{int(value)} B" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    raise AssertionError("unreachable")


def _calls_command(args, cfg: Config) -> int:
    rows = list_calls(
        cfg, PhoneClient(cfg.phone), since=args.since, until=args.until, source_name=args.source,
        with_text=args.with_text, app=args.app, limit=args.limit,
    )
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0
    widths = {key: max([len(key), *(len(str(row.get(key) or "-")) for row in rows)])
              for key in ("app", "counterpart", "started_at", "source")}
    for row in rows:
        active = " recording" if row["recording"] else ""
        started_at = row["started_at"]
        if args.local_time:
            started_at = datetime.fromisoformat(started_at).astimezone().isoformat(timespec="seconds")
        print(f"{(row['app'] or '-'):<{widths['app']}}  "
              f"{(row['counterpart'] or '-'):<{widths['counterpart']}}  "
              f"{started_at:<{widths['started_at']}}  {_size(row['size']):>10}  "
              f"{row['source']:<{widths['source']}}  {row['path']}  {row['local'] or '-'}{active}")
    return 0


def _pull_command(args, cfg: Config) -> int:
    paths = pull_paths(cfg, PhoneClient(cfg.phone), args.paths, Path(args.to) if args.to else None)
    for path in paths:
        print(path)
    return 0


def _transcribe_command(args, cfg: Config) -> int:
    files = transcribe_path(cfg, PhoneClient(cfg.phone), args.input, force=args.force,
                            output_prefix=Path(args.output) if args.output else None)
    selected = files.json_path if args.json else files.text_path
    print(selected.read_text(encoding="utf-8"), end="")
    return 0


def add_commands(subparsers) -> None:
    calls = subparsers.add_parser("calls", help="list call recordings on the phone")
    calls.add_argument("--since", default="7d")
    calls.add_argument("--until")
    calls.add_argument("--source")
    calls.add_argument("--with", dest="with_text")
    calls.add_argument("--app")
    calls.add_argument("--limit", type=int, default=50)
    calls.add_argument("--local-time", action="store_true")
    calls.add_argument("--json", action="store_true")
    calls.set_defaults(func=lambda args, cfg: run_command(_calls_command, args, cfg))

    pull = subparsers.add_parser("pull", help="download call recordings into the local cache")
    pull.add_argument("paths", nargs="+")
    pull.add_argument("--to")
    pull.set_defaults(func=lambda args, cfg: run_command(_pull_command, args, cfg))

    transcribe = subparsers.add_parser("transcribe", help="pull if needed and transcribe a recording")
    transcribe.add_argument("input")
    transcribe.add_argument("--json", action="store_true")
    transcribe.add_argument("--force", action="store_true")
    transcribe.add_argument("-o", "--output")
    transcribe.set_defaults(func=lambda args, cfg: run_command(_transcribe_command, args, cfg))


def run_command(handler, args, cfg: Config) -> int:
    """Translate command errors to stable CLI exit codes."""
    try:
        return handler(args, cfg)
    except PhoneError as exc:
        print(f"phone error ({exc.code}): {exc.message}", file=sys.stderr)
        return 2 if exc.code == "not_paired" else 1
    except ValueError as exc:
        print(f"calls error: {exc}", file=sys.stderr)
        return 2
    except (AsrError, OSError) as exc:
        print(f"calls error: {exc}", file=sys.stderr)
        return 1
