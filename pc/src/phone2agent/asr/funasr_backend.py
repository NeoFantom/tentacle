"""Paraformer, VAD, punctuation, and optional whole-call speaker clustering."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from ..config import AsrConfig
from .audio import load_audio
from .base import AsrError, normalize_speakers


@lru_cache(maxsize=None)
def _get_model(model_name: str, device: str, diarize: bool):
    try:
        from funasr import AutoModel
    except ImportError as exc:
        raise AsrError("FunASR dependencies are missing; install phone2agent[funasr]") from exc
    return AutoModel(model=model_name, vad_model="fsmn-vad", punc_model="ct-punc",
                     spk_model="cam++" if diarize else None, device=device,
                     vad_kwargs={"max_single_segment_time": 30000},
                     disable_update=True, disable_pbar=True)


def sentence_segments(result: dict, *, diarize: bool, duration_s: float) -> list[dict]:
    """Convert FunASR's sentence timestamps (milliseconds) to the disk contract."""
    sentences = result.get("sentence_info") or []
    if not sentences:
        text = result.get("text", "")
        return ([{"id": 0, "start": 0.0, "end": duration_s,
                  "speaker": None, "text": text}] if text.strip() else [])
    segments = []
    for sentence in sentences:
        segments.append({"id": len(segments), "start": sentence["start"] / 1000.0,
                         "end": sentence["end"] / 1000.0,
                         "speaker": sentence.get("spk") if diarize else None,
                         "text": sentence["text"].strip()})
    return normalize_speakers(segments)


def transcribe(audio_path: Path, cfg: AsrConfig) -> dict:
    try:
        import torch
    except ImportError as exc:
        raise AsrError("FunASR dependencies are missing; install phone2agent[funasr]") from exc
    device = cfg.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    model_name = cfg.model or "paraformer-zh"
    model = _get_model(model_name, device, cfg.diarize)
    samples, duration_s = load_audio(audio_path)
    # Limit padded ASR batches, while preserving whole-call speaker identities.
    # VAD caps a single speech region too, including recordings without pauses.
    # AutoModel retains runtime kwargs; an empty hotword clears earlier hints.
    result = model.generate(input=samples, fs=16000, batch_size_s=60,
                            sentence_timestamp=True,
                            hotword=" ".join(cfg.hotwords))
    if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
        raise AsrError("FunASR returned an invalid result for one audio file")
    segments = sentence_segments(result[0], diarize=cfg.diarize, duration_s=duration_s)
    return {"backend": "funasr", "model": model_name, "language": cfg.language,
            "duration_s": duration_s, "segments": segments,
            "text": result[0].get("text", "".join(s["text"] for s in segments))}
