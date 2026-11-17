from __future__ import annotations

import importlib.util
import io
import json
import logging
import math
import os
import struct
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from phone2agent.config import AsrConfig


def transcript(**changes):
    value = {
        "backend": "test",
        "model": "tiny",
        "language": "zh",
        "duration_s": 4.0,
        "segments": [
            {"id": 0, "start": 0.0, "end": 1.0, "speaker": "alice", "text": "one"},
            {"id": 1, "start": 1.0, "end": 2.0, "speaker": "alice", "text": "two"},
            {"id": 2, "start": 2.0, "end": 3.0, "speaker": "bob", "text": "three"},
        ],
        "text": "one two three",
    }
    value.update(changes)
    return value


class TranscriptTests(unittest.TestCase):
    def test_speakers_are_normalised_in_first_appearance_order(self):
        from phone2agent.asr.base import normalize_speakers, validate_transcript

        value = validate_transcript(transcript())
        self.assertEqual([segment["speaker"] for segment in value["segments"]], ["S1", "S1", "S2"])
        raw = [
            {"speaker": 8},
            {"speaker": "remote"},
            {"speaker": 8},
            {"speaker": None},
        ]
        self.assertEqual(
            [segment["speaker"] for segment in normalize_speakers(raw)],
            ["S1", "S2", "S1", None],
        )

    def test_null_speakers_remain_null(self):
        from phone2agent.asr.base import validate_transcript

        value = transcript()
        value["segments"][0]["speaker"] = None
        value["segments"][1]["speaker"] = None
        self.assertEqual(
            [segment["speaker"] for segment in validate_transcript(value)["segments"]],
            [None, None, "S1"],
        )

    def test_invalid_shape_is_rejected(self):
        from phone2agent.asr.base import AsrError, validate_transcript

        value = transcript()
        del value["model"]
        with self.assertRaisesRegex(AsrError, "model"):
            validate_transcript(value)

    def test_write_transcript_merges_consecutive_speaker_segments_and_formats_time(self):
        from phone2agent.asr import write_transcript

        value = transcript(duration_s=3662.5)
        value["segments"][2]["start"] = 3661.2
        value["segments"][2]["end"] = 3662.2
        with tempfile.TemporaryDirectory() as directory:
            json_path = Path(directory) / "call.json"
            markdown_path = Path(directory) / "call.md"
            write_transcript(value, json_path, markdown_path)

            saved = json.loads(json_path.read_text(encoding="utf-8"))
            markdown = markdown_path.read_text(encoding="utf-8")

        self.assertEqual(saved["segments"][0]["speaker"], "S1")
        self.assertIn("test", markdown)
        self.assertIn("tiny", markdown)
        self.assertIn("3662.5", markdown)
        self.assertIn("[00:00] S1: one two", markdown)
        self.assertEqual(markdown.count("S1:"), 1)
        self.assertIn("[1:01:01] S2: three", markdown)

    def test_write_failure_cleans_staged_atomic_files(self):
        from phone2agent.asr import write_transcript

        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            with mock.patch("phone2agent.asr.os.replace", side_effect=OSError("disk full")):
                with self.assertRaisesRegex(OSError, "disk full"):
                    write_transcript(transcript(), directory / "call.json", directory / "call.md")
            self.assertFalse(any(path.name.endswith(".part") for path in directory.iterdir()))

    def test_null_speaker_segments_keep_separate_timestamps(self):
        from phone2agent.asr import write_transcript

        value = transcript()
        value["segments"] = [
            {"id": 0, "start": 0.0, "end": 1.0, "speaker": None, "text": "one"},
            {"id": 1, "start": 1.0, "end": 2.0, "speaker": None, "text": "two"},
        ]
        value["text"] = "one two"
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            markdown_path = directory / "call.md"
            write_transcript(value, directory / "call.json", markdown_path)
            markdown = markdown_path.read_text(encoding="utf-8")

        self.assertIn("[00:00] one", markdown)
        self.assertIn("[00:01] two", markdown)


class RegistryTests(unittest.TestCase):
    def test_unknown_backend_lists_valid_names(self):
        from phone2agent.asr import get_backend

        with self.assertRaisesRegex(ValueError, r"funasr.*faster-whisper.*command.*none"):
            get_backend("missing")

    def test_none_backend_has_clear_configuration_error(self):
        from phone2agent.asr import get_backend
        from phone2agent.asr.base import AsrError

        with self.assertRaisesRegex(AsrError, "configure"):
            get_backend("none")(Path("unused.wav"), AsrConfig(backend="none"))


class BackendCacheTests(unittest.TestCase):
    def test_funasr_model_is_cached_by_options(self):
        from phone2agent.asr import funasr_backend

        created = []

        class FakeAutoModel:
            def __init__(self, **kwargs):
                created.append(kwargs)

        funasr_backend._get_model.cache_clear()
        try:
            with (
                mock.patch.dict(sys.modules, {"funasr": SimpleNamespace(AutoModel=FakeAutoModel)}),
                mock.patch.object(funasr_backend, "_resolve_model", side_effect=lambda name: name),
            ):
                first = funasr_backend._get_model("model-a", "cpu", True)
                second = funasr_backend._get_model("model-a", "cpu", True)
                third = funasr_backend._get_model("model-a", "cpu", False)
        finally:
            funasr_backend._get_model.cache_clear()

        self.assertIs(first, second)
        self.assertIsNot(first, third)
        self.assertEqual(len(created), 2)
        self.assertEqual(created[0]["model"], "model-a")
        self.assertEqual(created[0]["device"], "cpu")
        self.assertEqual(created[0]["spk_model"], "cam++")
        self.assertEqual(created[0]["vad_kwargs"], {"max_single_segment_time": 30_000})
        self.assertTrue(created[0]["disable_update"])
        self.assertIsNone(created[1]["spk_model"])

    def test_funasr_transcribe_forwards_bounded_batch_and_hotwords(self):
        from phone2agent.asr import funasr_backend

        model = mock.Mock()
        model.generate.return_value = [{
            "text": "hello",
            "sentence_info": [{"start": 0, "end": 1000, "spk": 0, "text": "hello"}],
        }]
        fake_torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: True))
        samples = object()
        with (
            mock.patch.dict(os.environ, {"P2A_ASR_VERBOSE": "0"}),
            mock.patch.dict(sys.modules, {"torch": fake_torch}),
            mock.patch.object(funasr_backend, "_get_model", return_value=model) as get_model,
            mock.patch.object(funasr_backend, "load_audio", return_value=(samples, 1.0)),
        ):
            value = funasr_backend.transcribe(
                Path("call.aac"),
                AsrConfig(hotwords=["ExampleName", "phone2agent"]),
            )
            funasr_backend.transcribe(
                Path("call.aac"),
                AsrConfig(device="cpu", hotwords=[]),
            )
            with mock.patch.dict(os.environ, {"P2A_ASR_VERBOSE": "1"}):
                funasr_backend.transcribe(Path("call.aac"), AsrConfig())

        get_model.assert_any_call("paraformer-zh", "cuda", True)
        get_model.assert_any_call("paraformer-zh", "cpu", True)
        first = model.generate.call_args_list[0].kwargs
        self.assertIs(first["input"], samples)
        self.assertEqual(first["fs"], 16_000)
        self.assertEqual(first["batch_size_s"], 60)
        self.assertTrue(first["sentence_timestamp"])
        self.assertTrue(first["disable_pbar"])
        self.assertFalse(model.generate.call_args_list[2].kwargs["disable_pbar"])
        self.assertEqual(first["hotword"], "ExampleName phone2agent")
        self.assertEqual(model.generate.call_args_list[1].kwargs["hotword"], "")
        self.assertEqual(value["segments"][0]["speaker"], "S1")

    def test_funasr_resolves_all_cached_models_without_downloads(self):
        from phone2agent.asr import funasr_backend

        aliases = {name: f"publisher/{name}" for name in
                   ("paraformer-zh", "fsmn-vad", "ct-punc", "cam++")}
        snapshot = mock.Mock(side_effect=lambda repo, **kw: f"/cache/{repo}")
        download = mock.Mock(side_effect=AssertionError("network must not be used"))
        constructor = mock.Mock()
        modules = {
            "funasr": SimpleNamespace(AutoModel=constructor),
            "funasr.download.name_maps_from_hub": SimpleNamespace(name_maps_ms=aliases),
            "modelscope.hub.snapshot_download": SimpleNamespace(snapshot_download=snapshot),
            "funasr.download.download_model_from_hub": SimpleNamespace(
                get_or_download_model_dir=download),
        }
        funasr_backend._get_model.cache_clear()
        try:
            with mock.patch.dict(sys.modules, modules):
                funasr_backend._get_model("paraformer-zh", "cpu", True)
                # A user-specified repository id uses the same cache resolution.
                self.assertEqual(funasr_backend._resolve_model("custom/repo"),
                                 "/cache/custom/repo")
        finally:
            funasr_backend._get_model.cache_clear()

        download.assert_not_called()
        self.assertTrue(all(call.kwargs == {"local_files_only": True}
                            for call in snapshot.call_args_list))
        options = constructor.call_args.kwargs
        for field, name in (("model", "paraformer-zh"), ("vad_model", "fsmn-vad"),
                            ("punc_model", "ct-punc"), ("spk_model", "cam++")):
            self.assertEqual(options[field], f"/cache/{aliases[name]}")
        self.assertTrue(options["disable_update"])

    def test_funasr_local_directory_needs_no_hub_import(self):
        from phone2agent.asr import funasr_backend

        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.dict(sys.modules, {"modelscope": None, "funasr": None}):
                self.assertEqual(funasr_backend._resolve_model(directory),
                                 str(Path(directory).resolve()))

    def test_funasr_missing_model_download_and_failure_identify_model(self):
        from phone2agent.asr import funasr_backend
        from phone2agent.asr.base import AsrError

        snapshot = mock.Mock(side_effect=FileNotFoundError("not cached"))
        download = mock.Mock(return_value="downloaded/model")
        with mock.patch.dict(sys.modules, {
            "funasr.download.name_maps_from_hub": SimpleNamespace(
                name_maps_ms={"ct-punc": "publisher/punctuation"}),
            "modelscope.hub.snapshot_download": SimpleNamespace(snapshot_download=snapshot),
            "funasr.download.download_model_from_hub": SimpleNamespace(
                get_or_download_model_dir=download),
        }):
            self.assertEqual(funasr_backend._resolve_model("ct-punc"), "downloaded/model")
            download.assert_called_once_with("publisher/punctuation", "master")
            download.side_effect = OSError("offline")
            with self.assertRaisesRegex(AsrError, "ct-punc.*network access.*required once"):
                funasr_backend._resolve_model("ct-punc")

    def test_funasr_quiet_output_restores_logging_even_on_failure(self):
        from phone2agent.asr import funasr_backend

        root = logging.getLogger()
        handlers, level, disabled = root.handlers[:], root.level, root.manager.disable
        output, errors = io.StringIO(), io.StringIO()
        with (
            mock.patch.dict(os.environ, {"P2A_ASR_VERBOSE": "0"}),
            mock.patch("sys.stdout", output), mock.patch("sys.stderr", errors),
        ):
            with self.assertRaisesRegex(RuntimeError, "failure"):
                with funasr_backend._quiet_output():
                    self.assertFalse(logging.getLogger("modelscope").isEnabledFor(logging.INFO))
                    print("library banner")
                    print("progress bar", file=sys.stderr)
                    logging.info("library info")
                    root.addHandler(logging.NullHandler())
                    root.setLevel(logging.DEBUG)
                    raise RuntimeError("failure")
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(errors.getvalue(), "")
        self.assertEqual(root.handlers, handlers)
        self.assertEqual(root.level, level)
        self.assertEqual(root.manager.disable, disabled)

    def test_funasr_quiet_import_keeps_library_log_handler_usable(self):
        from phone2agent.asr import funasr_backend

        logger = logging.Logger("library-import")
        errors = io.StringIO()
        with (
            mock.patch.dict(os.environ, {"P2A_ASR_VERBOSE": "0"}),
            mock.patch.dict(logging.Logger.manager.loggerDict, {logger.name: logger}),
            mock.patch("sys.stderr", errors),
        ):
            with funasr_backend._quiet_output():
                handler = logging.StreamHandler()
                logger.addHandler(handler)
            logger.warning("still usable")
        self.assertEqual(errors.getvalue(), "still usable\n")

    def test_funasr_verbose_preserves_library_output(self):
        from phone2agent.asr import funasr_backend

        output = io.StringIO()
        with mock.patch.dict(os.environ, {"P2A_ASR_VERBOSE": "1"}), mock.patch("sys.stdout", output):
            with funasr_backend._quiet_output():
                print("library banner")
        self.assertEqual(output.getvalue(), "library banner\n")

    def test_whisper_model_is_cached_by_options(self):
        from phone2agent.asr import whisper_backend

        created = []

        class FakeWhisperModel:
            def __init__(self, model, **kwargs):
                created.append((model, kwargs))

        whisper_backend._MODELS.clear()
        try:
            with mock.patch.dict(
                sys.modules,
                {"faster_whisper": SimpleNamespace(WhisperModel=FakeWhisperModel)},
            ):
                first = whisper_backend._get_model("small", "cpu", "int8")
                second = whisper_backend._get_model("small", "cpu", "int8")
                third = whisper_backend._get_model("small", "cpu", "float32")
        finally:
            whisper_backend._MODELS.clear()

        self.assertIs(first, second)
        self.assertIsNot(first, third)
        self.assertEqual(
            created,
            [
                ("small", {"device": "cpu", "compute_type": "int8"}),
                ("small", {"device": "cpu", "compute_type": "float32"}),
            ],
        )


class CommandBackendTests(unittest.TestCase):
    def test_placeholder_substitution_preserves_braces_in_paths_and_code(self):
        from phone2agent.asr.command_backend import _build_argv

        with tempfile.TemporaryDirectory() as directory:
            audio = Path(directory) / "call{output}.wav"
            output = Path(directory) / "{input}.json"
            argv = _build_argv(
                ["program", "{input}", "{output}", "print({'text': 'hello'})"], audio, output
            )
        self.assertEqual(argv, ["program", str(audio), str(output), "print({'text': 'hello'})"])

    def _audio(self, directory: Path) -> Path:
        path = directory / "input file.wav"
        path.write_bytes(b"not decoded by command backend")
        return path

    def _script(self, directory: Path, source: str) -> Path:
        path = directory / "external_asr.py"
        path.write_text(source, encoding="utf-8")
        return path

    def test_external_command_end_to_end(self):
        from phone2agent.asr.command_backend import transcribe

        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            audio = self._audio(directory)
            script = self._script(
                directory,
                """import json, pathlib, sys
assert pathlib.Path(sys.argv[1]).is_file()
result = {
    "backend": "external", "model": "fixture", "language": "zh", "duration_s": 1.25,
    "segments": [{"id": 0, "start": 0.0, "end": 1.25, "speaker": "person", "text": "hello"}],
    "text": "hello",
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(result), encoding="utf-8")
""",
            )
            cfg = AsrConfig(
                backend="command",
                command=[sys.executable, str(script), "{input}", "{output}"],
            )
            value = transcribe(audio, cfg)

            self.assertEqual(value["backend"], "external")
            self.assertEqual(value["segments"][0]["speaker"], "S1")
            self.assertFalse(any(path.name.endswith(".transcript.json") for path in directory.iterdir()))

    def test_invalid_json_is_reported(self):
        from phone2agent.asr.base import AsrError
        from phone2agent.asr.command_backend import transcribe

        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            audio = self._audio(directory)
            script = self._script(
                directory,
                "import pathlib, sys\npathlib.Path(sys.argv[2]).write_text('not JSON', encoding='utf-8')\n",
            )
            cfg = AsrConfig(command=[sys.executable, str(script), "{input}", "{output}"])
            with self.assertRaisesRegex(AsrError, "invalid JSON"):
                transcribe(audio, cfg)
            self.assertFalse(any(path.name.endswith(".transcript.json") for path in directory.iterdir()))

    def test_nonzero_exit_includes_stderr(self):
        from phone2agent.asr.base import AsrError
        from phone2agent.asr.command_backend import transcribe

        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            audio = self._audio(directory)
            script = self._script(
                directory,
                "import sys\nsys.stderr.write('recogniser failed')\nraise SystemExit(7)\n",
            )
            cfg = AsrConfig(command=[sys.executable, str(script), "{input}", "{output}"])
            with self.assertRaisesRegex(AsrError, "status 7.*recogniser failed"):
                transcribe(audio, cfg)

    def test_non_utf8_stderr_is_safely_reported(self):
        from phone2agent.asr.base import AsrError
        from phone2agent.asr.command_backend import transcribe

        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            audio = self._audio(directory)
            script = self._script(
                directory,
                "import os\nos.write(2, b'\\xff fatal')\nraise SystemExit(2)\n",
            )
            cfg = AsrConfig(command=[sys.executable, str(script), "{input}", "{output}"])
            with self.assertRaisesRegex(AsrError, "status 2.*fatal"):
                transcribe(audio, cfg)

    def test_both_placeholders_are_required(self):
        from phone2agent.asr.base import AsrError
        from phone2agent.asr.command_backend import transcribe

        with tempfile.TemporaryDirectory() as directory_name:
            audio = self._audio(Path(directory_name))
            with self.assertRaisesRegex(AsrError, r"\{output\}"):
                transcribe(audio, AsrConfig(command=[sys.executable, "{input}"]))


class CliTests(unittest.TestCase):
    def test_parser_accepts_standalone_transcription_options(self):
        from phone2agent.cli import build_parser

        args = build_parser().parse_args(
            ["transcribe", "call.aac", "-o", "out/call", "--backend", "command",
             "--model", "fixture", "--no-diarize", "--json"]
        )
        self.assertEqual(args.audio, "call.aac")
        self.assertEqual(args.output, "out/call")
        self.assertEqual(args.backend, "command")
        self.assertEqual(args.model, "fixture")
        self.assertTrue(args.no_diarize)
        self.assertTrue(args.json)

    def test_handler_applies_overrides_and_prints_json(self):
        from phone2agent import asr

        with tempfile.TemporaryDirectory() as directory_name:
            audio = Path(directory_name) / "call.wav"
            audio.write_bytes(b"fixture")
            args = SimpleNamespace(
                audio=str(audio), output=str(Path(directory_name) / "result"),
                backend="command", model="fixture-model", no_diarize=True, json=True,
            )
            cfg = SimpleNamespace(asr=AsrConfig())
            output = io.StringIO()
            with (
                mock.patch.object(asr, "transcribe", return_value=transcript()) as run,
                mock.patch.object(asr, "write_transcript") as write,
                mock.patch("sys.stdout", output),
            ):
                status = asr._transcribe_command(args, cfg)

        self.assertEqual(status, 0)
        passed_cfg = run.call_args.args[1]
        self.assertEqual(passed_cfg.backend, "command")
        self.assertEqual(passed_cfg.model, "fixture-model")
        self.assertFalse(passed_cfg.diarize)
        write.assert_called_once()
        self.assertEqual(json.loads(output.getvalue())["backend"], "test")

    def test_handler_returns_one_with_readable_asr_error(self):
        from phone2agent import asr

        with tempfile.TemporaryDirectory() as directory_name:
            audio = Path(directory_name) / "call.wav"
            audio.write_bytes(b"fixture")
            args = SimpleNamespace(
                audio=str(audio), output=None, backend="none", model=None,
                no_diarize=False, json=False,
            )
            stderr = io.StringIO()
            with mock.patch("sys.stderr", stderr):
                status = asr._transcribe_command(args, SimpleNamespace(asr=AsrConfig()))

        self.assertEqual(status, 1)
        self.assertIn("ASR error:", stderr.getvalue())
        self.assertIn("configure", stderr.getvalue())


class FunasrMappingTests(unittest.TestCase):
    def test_sentence_info_milliseconds_map_to_segments(self):
        from phone2agent.asr.base import validate_transcript
        from phone2agent.asr.funasr_backend import sentence_segments

        result = {
            "text": "hello world",
            "sentence_info": [
                {"start": 120, "end": 1120, "spk": 8, "text": " hello "},
                {"start": 1250, "end": 2500, "spk": 3, "text": "world"},
                {"start": 2600, "end": 3000, "spk": 8, "text": "again"},
            ],
        }
        segments = sentence_segments(result, diarize=True, duration_s=4.0)
        value = validate_transcript(
            transcript(duration_s=4.0, segments=segments, text="hello world again")
        )

        self.assertEqual([item["id"] for item in segments], [0, 1, 2])
        self.assertEqual([item["start"] for item in segments], [0.12, 1.25, 2.6])
        self.assertEqual([item["end"] for item in segments], [1.12, 2.5, 3.0])
        self.assertEqual([item["text"] for item in segments], ["hello", "world", "again"])
        self.assertEqual([item["speaker"] for item in value["segments"]], ["S1", "S2", "S1"])

    def test_diarisation_disabled_discards_speaker_ids(self):
        from phone2agent.asr.funasr_backend import sentence_segments

        segments = sentence_segments(
            {"sentence_info": [{"start": 0, "end": 100, "spk": 8, "text": "hello"}]},
            diarize=False,
            duration_s=1.0,
        )
        self.assertIsNone(segments[0]["speaker"])


@unittest.skipUnless(importlib.util.find_spec("av"), "PyAV is not installed")
class AudioTests(unittest.TestCase):
    def test_generated_8khz_wave_is_loaded_as_16khz_mono_float32(self):
        from phone2agent.asr.audio import load_audio, probe_duration

        with tempfile.TemporaryDirectory() as directory_name:
            path = Path(directory_name) / "narrowband.wav"
            sample_rate = 8_000
            duration = 0.1
            frames = b"".join(
                struct.pack("<h", int(10_000 * math.sin(2 * math.pi * 440 * i / sample_rate)))
                for i in range(int(sample_rate * duration))
            )
            with wave.open(str(path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(sample_rate)
                output.writeframes(frames)

            samples, decoded_duration = load_audio(path)

            self.assertEqual(samples.dtype.name, "float32")
            self.assertEqual(samples.ndim, 1)
            self.assertAlmostEqual(decoded_duration, duration, places=2)
            self.assertAlmostEqual(len(samples), 1_600, delta=32)
            self.assertAlmostEqual(probe_duration(path), duration, places=2)


if __name__ == "__main__":
    unittest.main()
