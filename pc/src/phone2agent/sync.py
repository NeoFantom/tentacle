"""Discover phone recordings and download them into the local store."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath

from .config import Config, SourceConfig
from .phone import PhoneClient, PhoneError
from .store import Store
from .timeutil import from_ms, to_ms


_APPS = {
    "微信": "wechat", "企业微信": "wecom", "飞书": "feishu", "qq": "qq",
    "钉钉": "dingtalk", "腾讯会议": "tencent-meeting",
}


@dataclass
class ScanResult:
    """Counts of returned entries, inserted rows, young files and scan errors."""

    found: int = 0
    new: int = 0
    skipped_young: int = 0
    errors: int = 0


def parse_name(filename: str, source: SourceConfig, mtime_ms: int) -> dict:
    """Extract optional metadata; timestamps always include the local offset."""
    groups = {}
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
    app = (groups.get("app") or "").strip().lower()
    app = _APPS.get(app, app) if app else None
    if app is None and source.name == "call" and not has_app:
        app = "phone"
    started = from_ms(mtime_ms)
    if groups.get("ts") and source.ts_format:
        try:
            started = datetime.strptime(groups["ts"], source.ts_format).astimezone()
        except (ValueError, OverflowError):
            pass
    return {
        "app": app,
        "counterpart": groups.get("counterpart") or None,
        "started_at": started.isoformat(timespec="seconds"),
    }


def scan(
    cfg: Config, store: Store, client: PhoneClient, now: datetime | None = None,
) -> ScanResult:
    """Scan each source using its persisted high-water mark and a day's overlap."""
    current = now if now is not None else datetime.now().astimezone()
    now_ms = to_ms(current)
    result = ScanResult()
    for source in cfg.sources:
        newest = store.conn.execute(
            "SELECT MAX(mtime_ms) FROM recordings WHERE source = ?", (source.name,)
        ).fetchone()[0]
        since_ms = (
            to_ms(current - timedelta(days=cfg.sync.backfill_days))
            if newest is None else newest - 86_400_000
        )
        try:
            listing = client.find(root=source.path, ext=source.extensions,
                                  since_ms=since_ms, sort="mtime_asc", limit=5000)
        except PhoneError as exc:
            result.errors += 1
            store.log_event(
                "warning" if exc.code == "not_found" or exc.status is None else "error",
                f"Scan source {source.name}: {exc.code}: {exc}",
            )
            if exc.status is None:
                break
            continue
        if listing.get("truncated"):
            store.log_event("warning", f"Scan source {source.name}: results truncated at 5000 files")
        for entry in listing["entries"]:
            result.found += 1
            if now_ms - entry["mtime_ms"] < source.min_age_s * 1000:
                result.skipped_young += 1
                continue
            _, created = store.add_recording(
                source=source.name, phone_path=entry["path"], size=entry["size"],
                mtime_ms=entry["mtime_ms"], status="discovered",
                **parse_name(entry["name"], source, entry["mtime_ms"]),
            )
            result.new += int(created)
    return result


def _sanitise_name(name: str) -> str:
    # A small portable punctuation set keeps names readable on both platforms.
    safe = "".join(char if char.isalnum() or char in " ._-()" else "_" for char in name)
    safe = safe.strip(" .") or "recording"
    suffix = PurePosixPath(safe).suffix
    # An unusually long suffix must itself fit within the filename budget.
    suffix = suffix.encode("utf-8")[:119].decode("utf-8", errors="ignore")
    stem = PurePosixPath(safe).stem
    budget = 120 - len(suffix.encode("utf-8"))
    stem = stem.encode("utf-8")[:budget].decode("utf-8", errors="ignore") or "_"
    return stem + suffix


def _duration(path: Path) -> float | None:
    try:
        from .asr.audio import probe_duration

        return probe_duration(path)
    except Exception:
        # Optional metadata must never turn a successful transfer into a failure.
        return None


def download_pending(
    cfg: Config, store: Store, client: PhoneClient, limit: int | None = None,
    now: datetime | None = None,
) -> list[int]:
    """Download due rows, returning the IDs that reached ``downloaded``."""
    if limit is not None and limit < 0:
        raise ValueError("limit must be non-negative")
    current = now if now is not None else datetime.now().astimezone()
    due = store.due_recordings(
        ["discovered", "failed"], current.astimezone().isoformat(timespec="seconds")
    )
    rows = [row for row in due if row["status"] == "discovered" or row["failed_stage"] == "download"]
    if limit is not None:
        rows = rows[:limit]
    downloaded = []
    for row in rows:
        recording_id = row["id"]
        started = datetime.fromisoformat(row["started_at"]) if row["started_at"] else from_ms(row["mtime_ms"])
        name = _sanitise_name(PurePosixPath(row["phone_path"]).name)
        destination = cfg.data_dir / "recordings" / started.strftime("%Y-%m") / f"{recording_id}-{name}"
        try:
            client.download(row["phone_path"], destination, expected_size=row["size"])
            sha = hashlib.sha256()
            with destination.open("rb") as stream:
                for chunk in iter(lambda: stream.read(128 * 1024), b""):
                    sha.update(chunk)
            digest = sha.hexdigest()
            duplicate = store.find_by_sha256(digest)
            if duplicate is not None and duplicate["id"] != recording_id:
                destination.unlink()
                store.update_recording(
                    recording_id, status="skipped", sha256=digest, local_path=None,
                    failed_stage=None, error=None, attempts=0, next_try_at=None,
                )
                store.log_event("info", f"Duplicate content of recording {duplicate['id']}; skipped",
                                recording_id=recording_id)
                continue
            store.update_recording(
                recording_id, sha256=digest, local_path=str(destination),
                duration_s=_duration(destination), status="downloaded",
                failed_stage=None, error=None, attempts=0, next_try_at=None,
            )
            downloaded.append(recording_id)
        except PhoneError as exc:
            # Size mismatches are local client errors with no HTTP status, too.
            if exc.status is None and exc.code not in {"size_mismatch", "not_found"}:
                store.log_event("warning", f"Download stopped: {exc.code}: {exc}",
                                recording_id=recording_id)
                break
            store.mark_failed(recording_id, "download", f"{exc.code}: {exc}", cfg.pipeline.max_attempts)
        except OSError as exc:
            store.mark_failed(recording_id, "download", str(exc), cfg.pipeline.max_attempts)
    return downloaded


def sync_once(cfg: Config, store: Store, client: PhoneClient) -> dict:
    """Run one scan and download pass and return integer counts."""
    counts = asdict(scan(cfg, store, client))
    counts["downloaded"] = len(download_pending(cfg, store, client))
    return counts


def _nonnegative(text: str) -> int:
    value = int(text)
    if value < 0:
        raise ValueError("must be non-negative")
    return value


def add_commands(subparsers) -> None:
    """Register sync and local recording-list commands with the shared CLI."""
    sync = subparsers.add_parser("sync", help="scan and download phone recordings")
    sync.add_argument("--scan-only", action="store_true", help="discover without downloading")
    sync.set_defaults(func=_sync_command)
    recordings = subparsers.add_parser("recordings", help="list local recording state")
    recordings.add_argument("--status", help="filter by status")
    recordings.add_argument("--limit", type=_nonnegative, help="maximum rows to show")
    recordings.set_defaults(func=_recordings_command)
    for parser in (sync, recordings):
        parser.add_argument("--json", action="store_true", help="print JSON")
        parser.add_argument("--config", default=argparse.SUPPRESS, help=argparse.SUPPRESS)


def _print_rows(rows: list[dict], columns: tuple[str, ...]) -> None:
    print("\t".join(columns))
    for row in rows:
        print("\t".join(str(row[column]) if row[column] is not None else "-" for column in columns))


def _sync_command(args, cfg: Config) -> int:
    try:
        with Store.open(cfg) as store:
            previous = store.conn.execute("SELECT COALESCE(MAX(id), 0) FROM recordings").fetchone()[0]
            client = PhoneClient(cfg.phone)
            if args.scan_only:
                counts = asdict(scan(cfg, store, client))
                counts["downloaded"] = 0
            else:
                counts = sync_once(cfg, store, client)
            columns = ("id", "source", "counterpart", "started_at", "size")
            rows = [{key: row[key] for key in columns} for row in store.conn.execute(
                "SELECT * FROM recordings WHERE id > ? ORDER BY id", (previous,)
            )]
        if args.json:
            print(json.dumps({**counts, "recordings": rows}, ensure_ascii=False, indent=2))
        else:
            print(" ".join(f"{key}={value}" for key, value in counts.items()))
            _print_rows(rows, columns)
        return 0
    except (OSError, sqlite3.Error) as exc:
        print(f"Sync error: {exc}", file=sys.stderr)
        return 1


def _recordings_command(args, cfg: Config) -> int:
    try:
        with Store.open(cfg) as store:
            columns = ("id", "status", "source", "app", "counterpart", "started_at", "duration", "error")
            rows = [{key: row["duration_s" if key == "duration" else key] for key in columns}
                    for row in store.list_recordings(status=args.status, limit=args.limit)]
        if args.json:
            print(json.dumps(rows, ensure_ascii=False, indent=2))
        else:
            _print_rows(rows, columns)
        return 0
    except (OSError, sqlite3.Error) as exc:
        print(f"Recordings error: {exc}", file=sys.stderr)
        return 1
