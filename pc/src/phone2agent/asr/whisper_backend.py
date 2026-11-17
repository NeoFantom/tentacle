"""faster-whisper ASR backend."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .audio import load_audio


_MODELS: dict[tuple[str, str, str], Any] = {}


def _resolve_device(requested: str) -> str:
    if requested != "auto":
        return requested
    try:
        import ctranslate2

        return "cuda" if ctranslate2.get_cuda_device_count() else "cpu"
    except (ImportError, RuntimeError):
        return "cpu"


def _get_model(model_name: str, device: str, compute_type: str):
    key = (model_name, device, compute_type)
    if key not in _MODELS:
        try:
            from faster_whisper import WhisperModel
        except ImportError as exc:
            from .base import AsrError

            raise AsrError(
                "faster-whisper backend is not installed; install phone2agent[whisper]"
            ) from exc
        _MODELS[key] = WhisperModel(
            model_name,
            device=device,
            compute_type=compute_type,
        )
    return _MODELS[key]


def transcribe(audio_path: Path, cfg) -> dict:
    from .base import AsrError, validate_transcript

    model_name = cfg.model or "large-v3"
    device = _resolve_device(cfg.device)
    try:
        audio, duration = load_audio(Path(audio_path), sr=16_000)
        model = _get_model(model_name, device, cfg.compute_type)
        kwargs = {
            "language": cfg.language or None,
            "vad_filter": True,
        }
        if cfg.initial_prompt:
            kwargs["initial_prompt"] = cfg.initial_prompt
        result, info = model.transcribe(audio, **kwargs)
        segments = []
        for segment in result:
            text = segment.text.strip()
            if not text:
                continue
            segments.append(
                {
                    "id": len(segments),
                    "start": float(segment.start),
                    "end": float(segment.end),
                    "speaker": None,
                    "text": text,
                }
            )
        transcript = {
            "backend": "faster-whisper",
            "model": model_name,
            "language": getattr(info, "language", None) or cfg.language,
            "duration_s": duration,
            "segments": segments,
            "text": " ".join(segment["text"] for segment in segments),
        }
        return validate_transcript(transcript)
    except AsrError:
        raise
    except Exception as exc:
        raise AsrError(f"faster-whisper transcription failed: {exc}") from exc
