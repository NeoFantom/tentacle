"""Shared transcript validation and speaker normalization (standard library only)."""
from __future__ import annotations

import math
from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..config import AsrConfig

Backend = Callable[[Path, "AsrConfig"], dict]


class AsrError(RuntimeError):
    """An actionable transcription, decoding, or output error."""


def normalize_speakers(segments: list[dict]) -> list[dict]:
    """Copy segments, assigning stable labels in order of first appearance."""
    speakers: dict[str | int, str] = {}
    result = []
    for segment in segments:
        speaker = segment["speaker"]
        if speaker is not None:
            if isinstance(speaker, bool) or not isinstance(speaker, (str, int)):
                raise AsrError("Segment speaker must be a string, integer, or null")
            speaker = speakers.setdefault(speaker, f"S{len(speakers) + 1}")
        result.append({**segment, "speaker": speaker})
    return result


def _seconds(value: object, field: str) -> None:
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not math.isfinite(value) or value < 0):
        raise AsrError(f"Transcript {field} must be a finite, non-negative number")


def validate_transcript(transcript: dict) -> dict:
    """Validate the fixed disk contract and return a normalized copy."""
    fields = {"backend", "model", "language", "duration_s", "segments", "text"}
    if not isinstance(transcript, dict) or set(transcript) != fields:
        raise AsrError("Transcript must contain exactly: " + ", ".join(sorted(fields)))
    for field in ("backend", "model", "language", "text"):
        if not isinstance(transcript[field], str):
            raise AsrError(f"Transcript {field} must be a string")
    _seconds(transcript["duration_s"], "duration_s")
    if not isinstance(transcript["segments"], list):
        raise AsrError("Transcript segments must be a list")
    for index, segment in enumerate(transcript["segments"]):
        if not isinstance(segment, dict) or set(segment) != {
                "id", "start", "end", "speaker", "text"}:
            raise AsrError(f"Transcript segment {index} must contain id, start, end, speaker, text")
        if type(segment["id"]) is not int or segment["id"] != index:
            raise AsrError(f"Transcript segment {index} id must be {index}")
        _seconds(segment["start"], f"segment {index} start")
        _seconds(segment["end"], f"segment {index} end")
        if segment["end"] < segment["start"]:
            raise AsrError(f"Transcript segment {index} end precedes start")
        if segment["speaker"] is not None and not isinstance(segment["speaker"], str):
            raise AsrError(f"Transcript segment {index} speaker must be a string or null")
        if not isinstance(segment["text"], str):
            raise AsrError(f"Transcript segment {index} text must be a string")
    return {**transcript, "segments": normalize_speakers(transcript["segments"])}
