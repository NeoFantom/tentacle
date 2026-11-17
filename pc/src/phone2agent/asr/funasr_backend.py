"""Paraformer, VAD, punctuation, and optional whole-call speaker clustering."""
from __future__ import annotations

from contextlib import contextmanager, redirect_stderr, redirect_stdout
from functools import lru_cache
import logging
import os
from pathlib import Path
import sys

from ..config import AsrConfig
from .audio import load_audio
from .base import AsrError, normalize_speakers


@contextmanager
def _quiet_output():
    """Scope library logging, prints, and progress output to this transcription."""
    if os.environ.get("P2A_ASR_VERBOSE") == "1":
        yield
        return
    root = logging.getLogger()
    level, handlers = root.level, root.handlers[:]
    disabled = root.manager.disable
    stderr = sys.stderr
    try:
        logging.disable(max(disabled, logging.INFO))
        with open(os.devnull, "w") as sink, redirect_stdout(sink), redirect_stderr(sink):
            try:
                yield
            finally:
                # Libraries imported here may retain a StreamHandler to stderr.
                # Keep it usable after the temporary output sink is closed.
                loggers = [root, *(value for value in list(root.manager.loggerDict.values())
                                   if isinstance(value, logging.Logger))]
                for logger in loggers:
                    for handler in logger.handlers:
                        if getattr(handler, "stream", None) is sink:
                            handler.setStream(stderr)
    finally:
        # AutoModel calls basicConfig, which otherwise alters the daemon's logging.
        for handler in root.handlers[:]:
            if handler not in handlers:
                root.removeHandler(handler)
                handler.close()
        root.handlers[:] = handlers
        root.setLevel(level)
        logging.disable(disabled)


def _resolve_model(model_name: str) -> str:
    if Path(model_name).is_dir():
        return str(Path(model_name).resolve())
    from funasr.download.name_maps_from_hub import name_maps_ms
    from modelscope.hub.snapshot_download import snapshot_download

    repo = name_maps_ms.get(model_name, model_name)
    try:
        return snapshot_download(repo, local_files_only=True)
    except Exception:
        # FunASR's download_from_ms swallows download errors and later reports
        # "model is not registered". Call its downloader before construction.
        from funasr.download.download_model_from_hub import get_or_download_model_dir
        try:
            return get_or_download_model_dir(repo, "master")
        except Exception as exc:
            raise AsrError(
                f"Failed to download model {model_name!r} ({repo}); network access "
                "to the model hub is required once to cache this model"
            ) from exc


@lru_cache(maxsize=None)
def _get_model(model_name: str, device: str, diarize: bool):
    try:
        from funasr import AutoModel
    except ImportError as exc:
        raise AsrError("FunASR dependencies are missing; install phone2agent[funasr]") from exc
    return AutoModel(model=_resolve_model(model_name),
                     vad_model=_resolve_model("fsmn-vad"),
                     punc_model=_resolve_model("ct-punc"),
                     spk_model=_resolve_model("cam++") if diarize else None, device=device,
                     vad_kwargs={"max_single_segment_time": 30000},
                     disable_update=True,
                     disable_pbar=os.environ.get("P2A_ASR_VERBOSE") != "1")


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
    with _quiet_output():
        return _transcribe(audio_path, cfg)


def _transcribe(audio_path: Path, cfg: AsrConfig) -> dict:
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
                            disable_pbar=os.environ.get("P2A_ASR_VERBOSE") != "1",
                            hotword=" ".join(cfg.hotwords))
    if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
        raise AsrError("FunASR returned an invalid result for one audio file")
    segments = sentence_segments(result[0], diarize=cfg.diarize, duration_s=duration_s)
    return {"backend": "funasr", "model": model_name, "language": cfg.language,
            "duration_s": duration_s, "segments": segments,
            "text": result[0].get("text", "".join(s["text"] for s in segments))}
