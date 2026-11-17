"""SQLite state store for phone2agent.

One :class:`Store` owns one SQLite connection.  SQLite connections are not
shared across threads here: each thread or process must open its own Store.
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Sequence

from . import timeutil


_MIGRATIONS = [
    """
    CREATE TABLE recordings (
      id            INTEGER PRIMARY KEY,
      source        TEXT NOT NULL,
      phone_path    TEXT NOT NULL,
      size          INTEGER NOT NULL,
      mtime_ms      INTEGER NOT NULL,
      sha256        TEXT,
      local_path    TEXT,
      app           TEXT,
      counterpart   TEXT,
      started_at    TEXT,
      duration_s    REAL,
      status        TEXT NOT NULL,
      failed_stage  TEXT,
      error         TEXT,
      attempts      INTEGER NOT NULL DEFAULT 0,
      next_try_at   TEXT,
      created_at    TEXT NOT NULL,
      updated_at    TEXT NOT NULL,
      UNIQUE(phone_path, size, mtime_ms)
    );
    CREATE TABLE artifacts (
      id INTEGER PRIMARY KEY,
      recording_id INTEGER NOT NULL REFERENCES recordings(id),
      kind TEXT NOT NULL,
      path TEXT NOT NULL,
      meta TEXT,
      created_at TEXT NOT NULL
    );
    CREATE TABLE tasks (
      id INTEGER PRIMARY KEY,
      recording_id INTEGER REFERENCES recordings(id),
      project TEXT,
      title TEXT NOT NULL,
      instructions TEXT NOT NULL,
      reason TEXT,
      status TEXT NOT NULL,
      owner_note TEXT,
      result_path TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );
    CREATE TABLE events (
      id INTEGER PRIMARY KEY,
      at TEXT NOT NULL,
      level TEXT NOT NULL,
      recording_id INTEGER,
      task_id INTEGER,
      message TEXT NOT NULL
    );
    CREATE INDEX recordings_status_next_try_at_idx
      ON recordings(status, next_try_at);
    CREATE INDEX tasks_status_idx ON tasks(status);
    """
]

_RECORDING_FIELDS = {
    "source", "phone_path", "size", "mtime_ms", "sha256", "local_path",
    "app", "counterpart", "started_at", "duration_s", "status",
    "failed_stage", "error", "attempts", "next_try_at",
}
_TASK_FIELDS = {
    "recording_id", "project", "title", "instructions", "reason", "status",
    "owner_note", "result_path",
}
_RETRY_STATUS = {
    "download": "discovered",
    "transcribe": "downloaded",
    "analyze": "transcribed",
    "dispatch": "analyzed",
}


class Store:
    """A single-connection SQLite store.

    Open a separate instance in every thread and process.  The connection uses
    WAL mode, a five-second busy timeout, and enforced foreign keys.
    """

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.path)
        try:
            self.conn.row_factory = sqlite3.Row
            self.conn.execute("PRAGMA busy_timeout = 5000")
            self.conn.execute("PRAGMA foreign_keys = ON")
            self.conn.execute("PRAGMA journal_mode = WAL")
            self._migrate()
        except Exception:
            self.conn.close()
            raise

    @classmethod
    def open(cls, cfg: Any) -> "Store":
        cfg.data_dir.mkdir(parents=True, exist_ok=True)
        return cls(cfg.data_dir / "state.db")

    def _migrate(self) -> None:
        # Lock before reading the version.  Two processes opening a fresh
        # database concurrently must not both decide to apply migration 1.
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            current = int(self.conn.execute("PRAGMA user_version").fetchone()[0])
            if current > len(_MIGRATIONS):
                raise RuntimeError(
                    f"state database version {current} is newer than supported "
                    f"version {len(_MIGRATIONS)}"
                )
            for version in range(current + 1, len(_MIGRATIONS) + 1):
                for statement in _MIGRATIONS[version - 1].split(";"):
                    if statement.strip():
                        self.conn.execute(statement)
                self.conn.execute(f"PRAGMA user_version = {version}")
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "Store":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    @staticmethod
    def _check_fields(fields: dict[str, Any], allowed: set[str]) -> None:
        unknown = set(fields) - allowed
        if unknown:
            raise ValueError(f"unknown field(s): {', '.join(sorted(unknown))}")

    def add_recording(
        self,
        source: str,
        phone_path: str,
        size: int,
        mtime_ms: int,
        **fields: Any,
    ) -> tuple[int, bool]:
        self._check_fields(fields, _RECORDING_FIELDS - {"source", "phone_path", "size", "mtime_ms"})
        now = timeutil.now_iso()
        values: dict[str, Any] = {
            "source": source,
            "phone_path": phone_path,
            "size": size,
            "mtime_ms": mtime_ms,
            "status": "discovered",
            "created_at": now,
            "updated_at": now,
        }
        values.update(fields)
        columns = list(values)
        placeholders = ", ".join("?" for _ in columns)
        with self.conn:
            cursor = self.conn.execute(
                f"""INSERT INTO recordings ({', '.join(columns)}) VALUES ({placeholders})
                    ON CONFLICT(phone_path, size, mtime_ms) DO NOTHING""",
                [values[column] for column in columns],
            )
            created = cursor.rowcount == 1
            if created:
                recording_id = int(cursor.lastrowid)
            else:
                row = self.conn.execute(
                    "SELECT id FROM recordings WHERE phone_path = ? AND size = ? AND mtime_ms = ?",
                    (phone_path, size, mtime_ms),
                ).fetchone()
                if row is None:  # Only the recording identity can conflict.
                    raise RuntimeError("recording conflict without an existing row")
                recording_id = int(row["id"])
        return recording_id, created

    def get_recording(self, recording_id: int) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM recordings WHERE id = ?", (recording_id,)
        ).fetchone()

    def update_recording(self, recording_id: int, **fields: Any) -> sqlite3.Row | None:
        self._check_fields(fields, _RECORDING_FIELDS)
        fields = dict(fields)
        fields["updated_at"] = timeutil.now_iso()
        assignments = ", ".join(f"{field} = ?" for field in fields)
        with self.conn:
            self.conn.execute(
                f"UPDATE recordings SET {assignments} WHERE id = ?",
                [*fields.values(), recording_id],
            )
        return self.get_recording(recording_id)

    def list_recordings(
        self, status: str | None = None, limit: int | None = None
    ) -> list[sqlite3.Row]:
        sql = "SELECT * FROM recordings"
        values: list[Any] = []
        if status is not None:
            sql += " WHERE status = ?"
            values.append(status)
        sql += " ORDER BY id"
        if limit is not None:
            if limit < 0:
                raise ValueError("limit must be non-negative")
            sql += " LIMIT ?"
            values.append(limit)
        return list(self.conn.execute(sql, values))

    def due_recordings(self, statuses: Sequence[str], now: str) -> list[sqlite3.Row]:
        if not statuses:
            return []
        placeholders = ", ".join("?" for _ in statuses)
        # A failed row with no retry time has exhausted its attempts.  Other
        # statuses with no retry time are immediately due.
        sql = f"""
            SELECT * FROM recordings
            WHERE status IN ({placeholders})
              AND ((status != 'failed' AND next_try_at IS NULL)
                   OR julianday(next_try_at) <= julianday(?))
            ORDER BY id
        """
        return list(self.conn.execute(sql, [*statuses, now]))

    def find_by_sha256(self, sha: str) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM recordings WHERE sha256 = ? ORDER BY id LIMIT 1", (sha,)
        ).fetchone()

    def mark_failed(
        self, recording_id: int, stage: str, error: str, max_attempts: int
    ) -> sqlite3.Row:
        if stage not in _RETRY_STATUS:
            raise ValueError(f"unknown failure stage: {stage}")
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            row = self.conn.execute(
                "SELECT attempts FROM recordings WHERE id = ?", (recording_id,)
            ).fetchone()
            if row is None:
                raise KeyError(recording_id)
            attempts = int(row["attempts"]) + 1
            now = timeutil.now_iso()
            next_try_at = None
            if attempts < max_attempts:
                delay_minutes = min(5 * (2 ** (attempts - 1)), 24 * 60)
                base = datetime.fromisoformat(now)
                next_try_at = (base + timedelta(minutes=delay_minutes)).isoformat(timespec="seconds")
            self.conn.execute(
                """UPDATE recordings
                   SET status = 'failed', failed_stage = ?, error = ?, attempts = ?,
                       next_try_at = ?, updated_at = ?
                   WHERE id = ?""",
                (stage, error, attempts, next_try_at, now, recording_id),
            )
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise
        updated = self.get_recording(recording_id)
        assert updated is not None
        return updated

    def reset_for_retry(self, recording_id: int) -> sqlite3.Row:
        row = self.get_recording(recording_id)
        if row is None:
            raise KeyError(recording_id)
        stage = row["failed_stage"]
        if stage not in _RETRY_STATUS:
            raise ValueError(f"recording {recording_id} has no retryable failed stage")
        updated = self.update_recording(
            recording_id,
            status=_RETRY_STATUS[stage],
            failed_stage=None,
            error=None,
            attempts=0,
            next_try_at=None,
        )
        assert updated is not None
        return updated

    def add_artifact(
        self, recording_id: int, kind: str, path: str | Path, meta: dict | None = None
    ) -> int:
        encoded = json.dumps(meta, ensure_ascii=False, separators=(",", ":")) if meta is not None else None
        with self.conn:
            cursor = self.conn.execute(
                "INSERT INTO artifacts (recording_id, kind, path, meta, created_at) VALUES (?, ?, ?, ?, ?)",
                (recording_id, kind, str(path), encoded, timeutil.now_iso()),
            )
        return int(cursor.lastrowid)

    def artifacts(self, recording_id: int, kind: str | None = None) -> list[sqlite3.Row]:
        if kind is None:
            return list(self.conn.execute(
                "SELECT * FROM artifacts WHERE recording_id = ? ORDER BY id", (recording_id,)
            ))
        return list(self.conn.execute(
            "SELECT * FROM artifacts WHERE recording_id = ? AND kind = ? ORDER BY id",
            (recording_id, kind),
        ))

    def latest_artifact(self, recording_id: int, kind: str) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM artifacts WHERE recording_id = ? AND kind = ? ORDER BY id DESC LIMIT 1",
            (recording_id, kind),
        ).fetchone()

    def add_task(
        self,
        title: str,
        instructions: str,
        recording_id: int | None = None,
        project: str | None = None,
        reason: str | None = None,
        status: str = "pending",
        owner_note: str | None = None,
        result_path: str | None = None,
    ) -> int:
        now = timeutil.now_iso()
        with self.conn:
            cursor = self.conn.execute(
                """INSERT INTO tasks
                   (recording_id, project, title, instructions, reason, status,
                    owner_note, result_path, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (recording_id, project, title, instructions, reason, status,
                 owner_note, result_path, now, now),
            )
        return int(cursor.lastrowid)

    def get_task(self, task_id: int) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()

    def list_tasks(self, statuses: Sequence[str] | None = None) -> list[sqlite3.Row]:
        if statuses is None:
            return list(self.conn.execute("SELECT * FROM tasks ORDER BY id"))
        if not statuses:
            return []
        placeholders = ", ".join("?" for _ in statuses)
        return list(self.conn.execute(
            f"SELECT * FROM tasks WHERE status IN ({placeholders}) ORDER BY id", list(statuses)
        ))

    def update_task(self, task_id: int, **fields: Any) -> sqlite3.Row | None:
        self._check_fields(fields, _TASK_FIELDS)
        fields = dict(fields)
        fields["updated_at"] = timeutil.now_iso()
        assignments = ", ".join(f"{field} = ?" for field in fields)
        with self.conn:
            self.conn.execute(
                f"UPDATE tasks SET {assignments} WHERE id = ?", [*fields.values(), task_id]
            )
        return self.get_task(task_id)

    def log_event(
        self,
        level: str,
        message: str,
        recording_id: int | None = None,
        task_id: int | None = None,
    ) -> int:
        with self.conn:
            cursor = self.conn.execute(
                "INSERT INTO events (at, level, recording_id, task_id, message) VALUES (?, ?, ?, ?, ?)",
                (timeutil.now_iso(), level, recording_id, task_id, message),
            )
        return int(cursor.lastrowid)

    def recent_events(self, limit: int) -> list[sqlite3.Row]:
        if limit < 0:
            raise ValueError("limit must be non-negative")
        return list(self.conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
        ))

    def counts_by_status(self) -> dict[str, int]:
        return {
            str(row["status"]): int(row["count"])
            for row in self.conn.execute(
                "SELECT status, COUNT(*) AS count FROM recordings GROUP BY status ORDER BY status"
            )
        }
