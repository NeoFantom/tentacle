"""Audio decoding and resampling through PyAV's bundled FFmpeg libraries."""
from __future__ import annotations

from pathlib import Path


def _imports():
    try:
        import av
        import numpy
    except ImportError as exc:
        raise RuntimeError(
            "audio decoding requires PyAV; install tentacle with an ASR extra"
        ) from exc
    return av, numpy


def _resampled_frames(path: Path, sample_rate: int):
    av, _ = _imports()
    try:
        with av.open(str(path)) as container:
            stream = next((item for item in container.streams if item.type == "audio"), None)
            if stream is None:
                raise RuntimeError(f"no audio stream found in {path}")
            resampler = av.AudioResampler(format="flt", layout="mono", rate=sample_rate)
            for frame in container.decode(stream):
                yield from resampler.resample(frame)
            yield from resampler.resample(None)
    except RuntimeError:
        raise
    except Exception as exc:
        raise RuntimeError(f"could not decode audio {path}: {exc}") from exc


def load_audio(path: Path, sr: int = 16_000):
    """Decode *path* to contiguous mono float32 samples at *sr*."""
    if sr <= 0:
        raise ValueError("sample rate must be positive")
    _, numpy = _imports()
    chunks = []
    sample_count = 0
    for frame in _resampled_frames(Path(path), sr):
        chunk = frame.to_ndarray().reshape(-1).astype(numpy.float32, copy=False)
        if chunk.size:
            chunks.append(chunk)
            sample_count += int(chunk.size)
    if chunks:
        samples = numpy.ascontiguousarray(numpy.concatenate(chunks), dtype=numpy.float32)
    else:
        samples = numpy.empty(0, dtype=numpy.float32)
    return samples, sample_count / sr


def probe_duration(path: Path) -> float:
    """Return decoded audio duration without relying on an external ffprobe.

    Raw AAC timestamps and container duration metadata are frequently inaccurate,
    so those files are decoded and counted. Other containers use their duration
    metadata when available and fall back to the same streaming count.
    """
    path = Path(path)
    av, _ = _imports()
    if path.suffix.lower() != ".aac":
        try:
            with av.open(str(path)) as container:
                stream = next((item for item in container.streams if item.type == "audio"), None)
                if stream is None:
                    raise RuntimeError(f"no audio stream found in {path}")
                if stream.duration is not None and stream.time_base is not None:
                    duration = float(stream.duration * stream.time_base)
                    if duration >= 0:
                        return duration
                if container.duration is not None:
                    duration = float(container.duration / av.time_base)
                    if duration >= 0:
                        return duration
        except RuntimeError:
            raise
        except Exception as exc:
            raise RuntimeError(f"could not probe audio {path}: {exc}") from exc

    sample_rate = 16_000
    sample_count = sum(frame.samples for frame in _resampled_frames(path, sample_rate))
    return sample_count / sample_rate
