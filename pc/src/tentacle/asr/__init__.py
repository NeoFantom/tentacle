"""Pluggable local ASR, atomic transcript artifacts, and the transcribe command."""
from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import tempfile

from ..config import AsrConfig
from .base import AsrError, Backend, validate_transcript

_BACKENDS = {
    "funasr": "funasr_backend",
    "faster-whisper": "whisper_backend",
    "command": "command_backend",
    "none": None,
}


def _disabled(audio_path: Path, cfg: AsrConfig) -> dict:
    raise AsrError("ASR is disabled; configure [asr].backend to funasr, faster-whisper, or command")


def get_backend(name: str) -> Backend:
    if name not in _BACKENDS:
        raise ValueError(f"Unknown ASR backend {name!r}; valid names: {', '.join(_BACKENDS)}")
    module = _BACKENDS[name]
    if module is None:
        return _disabled
    return importlib.import_module(f".{module}", __name__).transcribe


def transcribe(audio_path: Path, cfg: AsrConfig) -> dict:
    backend = get_backend(cfg.backend)
    path = Path(audio_path)
    if not path.is_file():
        raise AsrError(f"Audio file not found: {path}")
    try:
        return validate_transcript(backend(path, cfg))
    except AsrError:
        raise
    except Exception as exc:
        raise AsrError(f"{cfg.backend} transcription failed: {exc}") from exc


def _timestamp(seconds: float) -> str:
    hours, remainder = divmod(int(seconds), 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes:02d}:{secs:02d}"


def _markdown(transcript: dict) -> str:
    lines = ["# Transcript", "", f"Backend: {transcript['backend']}",
             f"Model: {transcript['model']}",
             f"Duration: {transcript['duration_s']:.3f} s", ""]
    paragraphs: list[tuple[float, str | None, list[str]]] = []
    for segment in transcript["segments"]:
        # Keep each speaker turn on one physical line, including external text.
        text = " ".join(segment["text"].split())
        if not text:
            continue
        speaker = segment["speaker"]
        if speaker is not None and paragraphs and paragraphs[-1][1] == speaker:
            paragraphs[-1][2].append(text)
        else:
            paragraphs.append((segment["start"], speaker, [text]))
    for start, speaker, texts in paragraphs:
        label = f"{speaker}: " if speaker is not None else ""
        lines.append(f"[{_timestamp(start)}] {label}{' '.join(texts)}")
    return "\n".join(lines) + "\n"


def write_transcript(transcript: dict, json_path: Path, md_path: Path) -> None:
    """Stage both files first; replace each destination atomically."""
    transcript = validate_transcript(transcript)
    json_path, md_path = Path(json_path), Path(md_path)
    if json_path.resolve() == md_path.resolve():
        raise AsrError("JSON and Markdown output paths must differ")
    staged: list[tuple[Path, Path]] = []
    try:
        for destination, content in (
            (json_path, json.dumps(transcript, ensure_ascii=False, indent=2, allow_nan=False) + "\n"),
            (md_path, _markdown(transcript)),
        ):
            destination.parent.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".part",
                                        dir=destination.parent)
            temporary = Path(name)
            staged.append((temporary, destination))
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
        for temporary, destination in staged:
            os.replace(temporary, destination)
    finally:
        for temporary, _ in staged:
            temporary.unlink(missing_ok=True)


