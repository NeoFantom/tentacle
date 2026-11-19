"""External-command ASR backend.

The configured command receives the source audio path and a temporary output
path.  It is responsible for writing the transcript JSON contract to that
output path.
"""
from __future__ import annotations

import json
import re
import subprocess
import tempfile
from pathlib import Path

from .base import AsrError, validate_transcript


_STDERR_TAIL = 2_000


def _build_argv(template: list[str], audio_path: Path, output_path: Path) -> list[str]:
    if not template:
        raise AsrError("command ASR backend requires asr.command")
    if not any("{input}" in arg for arg in template):
        raise AsrError("asr.command must contain the {input} placeholder")
    if not any("{output}" in arg for arg in template):
        raise AsrError("asr.command must contain the {output} placeholder")

    paths = {"input": str(audio_path), "output": str(output_path)}
    return [re.sub(r"\{(input|output)\}", lambda match: paths[match[1]], arg)
            for arg in template]


def transcribe(audio_path: Path, cfg) -> dict:
    """Run the configured argv template and validate its JSON output."""
    audio_path = Path(audio_path).resolve()
    if not audio_path.is_file():
        raise AsrError(f"audio file not found: {audio_path}")

    # Keep the intermediate transcript beside the private recording rather
    # than relying on a system-wide temporary directory.
    with tempfile.NamedTemporaryFile(
        prefix=f".{audio_path.name}.", suffix=".transcript.json", dir=audio_path.parent,
        delete=False,
    ) as output_file:
        output_path = Path(output_file.name)
    output_path.unlink()

    try:
        argv = _build_argv(cfg.command, audio_path, output_path)
        try:
            with tempfile.TemporaryFile(dir=audio_path.parent) as stderr_file:
                completed = subprocess.run(
                    argv,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=stderr_file,
                    check=False,
                )
                stderr_file.seek(0, 2)
                stderr_size = stderr_file.tell()
                stderr_file.seek(max(0, stderr_size - _STDERR_TAIL))
                stderr_tail = stderr_file.read().decode("utf-8", errors="replace").strip()
        except OSError as exc:
            raise AsrError(f"could not run command ASR backend: {exc}") from exc

        if completed.returncode:
            detail = f": {stderr_tail}" if stderr_tail else ""
            raise AsrError(
                f"command ASR backend exited with status {completed.returncode}{detail}"
            )

        try:
            raw_bytes = output_path.read_bytes()
        except FileNotFoundError as exc:
            raise AsrError("command ASR backend did not write {output}") from exc
        except OSError as exc:
            raise AsrError(f"could not read command ASR output: {exc}") from exc
        try:
            raw = raw_bytes.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise AsrError("command ASR backend output is not UTF-8 JSON") from exc
        try:
            transcript = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise AsrError(f"command ASR backend wrote invalid JSON: {exc}") from exc
        return validate_transcript(transcript)
    finally:
        output_path.unlink(missing_ok=True)
