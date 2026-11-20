"""File browsing commands for the phone HTTP API."""
from __future__ import annotations

import json
import importlib
import os
import shutil
import sys
import tempfile
import urllib.parse
from contextlib import redirect_stdout
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any

from . import config
from .phone import PhoneClient, PhoneError, parse_pair
from .timeutil import from_ms, parse_since, to_ms



def _get_destination(phone_path: str, dest: str | None) -> Path:
    """Like cp/scp: an existing directory or a trailing slash means DEST/<basename>."""
    name = PurePosixPath(phone_path).name
    if not dest:
        return Path(name)
    target = Path(dest)
    if dest.endswith(("/", os.sep)) or target.is_dir():
        return target / name
    return target

def _nonnegative(text: str) -> int:
    value = int(text)
    if value < 0:
        raise ValueError("must be nonnegative")
    return value


def _limit(text: str) -> int:
    value = int(text)
    if not 1 <= value <= 5000:
        raise ValueError("must be between 1 and 5000")
    return value


def add_commands(subparsers) -> None:
    """Register handlers compatible with :mod:`tentacle.cli`."""
    for command, help_text in (
        ("pair", "pair with a phone"), ("ping", "show the connected device"),
        ("ls", "list a phone directory"), ("stat", "show a phone entry"),
        ("find", "find phone files"), ("recent", "find recently modified files"),
        ("get", "download a phone file"), ("cat", "read a phone text file"),
        ("discover", "find directories containing audio recordings"),
    ):
        parser = subparsers.add_parser(command, help=help_text)
        parser.add_argument("--json", action="store_true", help="print JSON")
        parser.set_defaults(func=_run)
        if command == "pair":
            parser.add_argument("address", help="tentacle pairing string or HTTP URL")
            parser.add_argument("--token", help="token for an HTTP URL")
            # Pairing must also work when --config names a missing file.
            parser.set_defaults(needs_config=False)
        elif command == "ls":
            parser.add_argument("path", nargs="?", default="")
            parser.add_argument("-a", "--hidden", action="store_true")
        elif command in {"stat", "get", "cat"}:
            parser.add_argument("path")
            if command == "get":
                parser.add_argument("dest", nargs="?")
                parser.add_argument("--no-resume", action="store_true")
            elif command == "cat":
                parser.add_argument("--max-bytes", type=_nonnegative, default=1024 * 1024)
        elif command in {"find", "recent"}:
            if command == "find":
                parser.add_argument("root", nargs="?")
            parser.add_argument("--ext")
            parser.add_argument("--name")
            parser.add_argument("--since", default="1d" if command == "recent" else None)
            parser.add_argument("--until")
            parser.add_argument("--limit", type=_limit)
            parser.add_argument("--max-depth", type=_nonnegative)
            parser.add_argument("--type", choices=("file", "dir", "any"), default="file")
            parser.add_argument("--sort", choices=("mtime_desc", "mtime_asc", "name"),
                                default="mtime_desc")
            parser.add_argument("-a", "--hidden", action="store_true")
        elif command == "discover":
            parser.add_argument("--since", default="90d")
        if command in {"ls", "stat", "find", "recent"}:
            parser.add_argument("--local-time", action="store_true",
                                help="show times in the computer's timezone")

    doctor = subparsers.add_parser("doctor", help="check configuration and dependencies")
    doctor.add_argument("--json", action="store_true", help="print JSON")
    doctor.set_defaults(func=_doctor_command, needs_config=False)


def _emit(data) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2))


def _size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{int(value)} B" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    raise AssertionError("unreachable")


def _mtime(ms: int, zone=None) -> str:
    return from_ms(ms, zone).strftime("%Y-%m-%d %H:%M")


def _entries(entries, zone=None) -> None:
    for entry in entries:
        marker = {"dir": "d", "file": "f", "other": "?"}.get(entry["type"], "?")
        print(f"{marker} {_size(entry['size']):>10}  {_mtime(entry['mtime_ms'], zone)}  {entry['path']}")


def _pair(args) -> dict:
    urls, token, _name = parse_pair(args.address, args.token)
    path = Path(config._expand(str(args.config))) if args.config else config.default_config_path()
    if not path.exists():
        config.init(path)
    cfg = config.load(path)
    # The first URL also goes to phone.url so older clients keep working.
    config.set_values(path, "phone", {"url": urls[0], "urls": urls if len(urls) > 1 else [],
                                      "token": token})
    cfg.phone.url, cfg.phone.urls, cfg.phone.token = urls[0], urls if len(urls) > 1 else [], token
    client = PhoneClient(cfg.phone, cfg.data_dir)
    return _ping_result(client)


def _ping_result(client: PhoneClient) -> dict:
    if client.paths.multiple:
        client.select_path(refresh=True, wait_all=True)
    result = dict(client.ping())
    result["base_url"] = client.url
    result["round_trip_ms"] = round(client.last_round_trip_ms or 0.0, 1)
    if client.paths.multiple and client.paths.last is not None:
        result["paths"] = client.paths.last.to_json()
    return result


def _path_lines(paths: list[dict]) -> list[str]:
    width = max(len(item["url"]) for item in paths)
    lines = []
    for item in paths:
        rtt = f"{item['rtt_ms']:.1f} ms" if item["rtt_ms"] is not None else "-"
        detail = f"  ({item['detail']})" if item["detail"] else ""
        chosen = "  <- chosen" if item["chosen"] else ""
        lines.append(f"  {item['url']:<{width}}  {item['status']:<11}  {rtt:>9}{chosen}{detail}")
    return lines


def _audio_extensions(cfg) -> list[str]:
    # Reuse configuration defaults instead of maintaining a second extension list.
    extensions = set(config.SourceConfig(name="", path="").extensions)
    for source in cfg.sources:
        extensions.update(source.extensions)
    return sorted(extensions)


_RECORDER_LOCATIONS = (
    "MIUI/sound_recorder", "Recordings", "Music/Recordings", "Recorder", "Sounds",
    "Download", "Documents", "Android/media/com.tencent.mm",
    "Android/media/com.ss.android.lark", "Call", "CallRecordings",
)


def _discover(client, cfg, since: str) -> dict:
    extensions = _audio_extensions(cfg)
    since_ms = to_ms(parse_since(since, datetime.now().astimezone()))
    entries = {}
    truncated = False
    for root in _RECORDER_LOCATIONS:
        try:
            result = client.find(root=root, ext=",".join(extensions), limit=5000,
                                 max_depth=4, since_ms=since_ms)
        except PhoneError as exc:
            if exc.code == "not_found" or exc.status == 404:
                continue
            raise
        truncated = truncated or bool(result.get("truncated"))
        for entry in result["entries"]:
            entries[entry["path"]] = entry
    directories: dict[str, dict[str, Any]] = {}
    for entry in entries.values():
        parent = str(PurePosixPath(entry["path"]).parent)
        directory = directories.setdefault(parent, {"path": parent, "count": 0,
                                                   "newest_mtime_ms": entry["mtime_ms"],
                                                   "sample": entry["name"]})
        directory["count"] += 1
        if entry["mtime_ms"] > directory["newest_mtime_ms"]:
            directory["newest_mtime_ms"] = entry["mtime_ms"]
            directory["sample"] = entry["name"]
    ranked = sorted(directories.values(), key=lambda item: (-item["count"], item["path"]))
    suggestions = [{"name": f"audio-{i}", "path": item["path"], "extensions": extensions}
                   for i, item in enumerate(ranked[:5], 1)]
    return {"directories": ranked, "suggested_sources": suggestions,
            "truncated": truncated}


def _show_discovery(result, zone=None) -> None:
    for directory in result["directories"]:
        print(f"{directory['count']:>6}  {_mtime(directory['newest_mtime_ms'], zone)}  "
              f"{directory['path']}  ({directory['sample']})")
    if result["truncated"]:
        print("Results truncated at 5000 files.")
    if result["suggested_sources"]:
        print("\nSuggested configuration:")
    for source in result["suggested_sources"]:
        print("\n[[sources]]")
        for key, value in source.items():
            # JSON strings and arrays are valid TOML basic values here.
            print(f"{key} = {json.dumps(value, ensure_ascii=False)}")


def _run(args, cfg) -> int:
    try:
        display_zone = None
        if args.cmd == "pair":
            result = _pair(args)
        else:
            client = PhoneClient(cfg.phone, cfg.data_dir)
            if args.cmd == "ping":
                result = _ping_result(client)
            elif args.cmd == "ls":
                result = client.ls(args.path, hidden=args.hidden)
            elif args.cmd == "stat":
                result = client.stat(args.path)
            elif args.cmd in {"find", "recent"}:
                current = datetime.now().astimezone()
                checked_since = parse_since(args.since, current) if args.since else None
                checked_until = parse_since(args.until, current) if args.until else None
                if checked_since is not None and checked_until is not None and checked_since > checked_until:
                    raise ValueError("--since must not be later than --until")
                display_zone = None if args.local_time else client.phone_timezone()
                now = current.astimezone(display_zone) if display_zone is not None else current
                since = to_ms(parse_since(args.since, now)) if args.since else None
                until = to_ms(parse_since(args.until, now)) if args.until else None
                result = client.find(root=getattr(args, "root", None), ext=args.ext,
                                     name=args.name, since_ms=since, until_ms=until,
                                     limit=args.limit, max_depth=args.max_depth,
                                     type=args.type, sort=args.sort, hidden=args.hidden)
            elif args.cmd == "get":
                dest = _get_destination(args.path, args.dest)
                downloaded = client.download(args.path, dest, resume=not args.no_resume)
                result = {"path": args.path, "dest": str(downloaded)}
            elif args.cmd == "cat":
                data = client.read_bytes(args.path, args.max_bytes)
                result = {"path": args.path, "text": data.decode("utf-8", errors="replace")}
            else:
                result = _discover(client, cfg, args.since)
        if args.json:
            _emit(result)
        elif args.cmd in {"ping", "pair"}:
            print(f"{result['device']}  API {result['api']}  version {result['version']}")
            print(f"{result['base_url']}  {result['round_trip_ms']:.1f} ms round trip")
            if result.get("paths"):
                print("Paths (in preference order):")
                print("\n".join(_path_lines(result["paths"])))
        elif args.cmd in {"ls", "find", "recent"}:
            if args.cmd == "ls":
                display_zone = None if args.local_time else client.phone_timezone()
            _entries(result["entries"], display_zone)
            if result.get("truncated"):
                print("Results truncated; narrow the filters or increase --limit.")
        elif args.cmd == "stat":
            display_zone = None if args.local_time else client.phone_timezone()
            _entries([result], display_zone)
        elif args.cmd == "get":
            print(result["dest"])
        elif args.cmd == "cat":
            print(result["text"], end="")
        else:
            _show_discovery(result, client.phone_timezone())
        return 0
    except PhoneError as exc:
        print(f"phone error ({exc.code}): {exc.message}", file=sys.stderr)
        return 2 if exc.code == "not_paired" else 1
    except (ValueError, OSError) as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2


def doctor(cfg) -> list[tuple[str, bool, str]]:
    """Check the remaining manual-toolkit dependencies without loading ASR models."""
    checks: list[tuple[str, bool, str]] = []

    def check(name, probe) -> None:
        try:
            checks.append((name, True, str(probe())))
        except Exception as exc:
            checks.append((name, False, str(exc)))

    def check_config():
        if cfg.path is None:
            raise ValueError("configuration has no file path")
        config.load(cfg.path)
        return f"{cfg.path}: parsed"

    def writable_data_dir():
        cfg.data_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(dir=cfg.data_dir) as probe:
            probe.write(b"tentacle")
        return cfg.data_dir

    client = PhoneClient(cfg.phone, cfg.data_dir)

    def route():
        if not client.paths.urls or not client.token:
            raise PhoneError("not_paired", "phone URL and token are not configured", None)
        for url in client.paths.urls:
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname:
                raise ValueError(f"phone URL must use HTTP(S) and contain a host: {url}")
        if not client.paths.multiple:
            return client.url
        client.select_path(refresh=True, wait_all=True)
        assert client.paths.last is not None
        return "chosen " + client.url + "\n" + "\n".join(_path_lines(client.paths.last.to_json()))

    def ping():
        reply = client.ping()
        if not reply.get("all_files_access"):
            raise ValueError("phone has not granted all-files access")
        return (f"url={client.url}, device={reply.get('device', 'unknown')}, "
                f"round_trip_ms={client.last_round_trip_ms or 0.0:.1f}")

    check("config", check_config)
    check("data_dir", writable_data_dir)
    check("phone route", route)
    check("phone ping", ping)
    for source in cfg.sources:
        def source_path(source=source):
            reply = client.stat(source.path)
            if reply.get("type") != "dir":
                raise ValueError(f"not a directory: {source.path}")
            return source.path
        check(f"source {source.name}", source_path)

    loaded = {}

    def backend():
        name = cfg.asr.backend
        dependencies = {
            "funasr": ("funasr", "torch", "torchaudio", "av", "numpy"),
            "faster-whisper": ("faster_whisper", "ctranslate2", "av", "numpy"),
            "command": (), "none": (),
        }
        if name not in dependencies:
            raise ValueError(f"unknown ASR backend: {name}")
        if name == "command":
            executable = cfg.asr.command[0] if cfg.asr.command else ""
            resolved = shutil.which(executable) if executable else None
            if resolved is None:
                raise ValueError(f"command not found: {executable or '(empty command)'}")
            return resolved
        for dependency in dependencies[name]:
            loaded[dependency] = importlib.import_module(dependency)
        return f"{name}: importable" if name != "none" else "ASR disabled"

    check("ASR backend", backend)
    if cfg.asr.backend in {"funasr", "faster-whisper"} and cfg.asr.device != "cpu":
        def cuda():
            if cfg.asr.backend == "funasr":
                module = loaded.get("torch") or importlib.import_module("torch")
                visible = module.cuda.is_available()
            else:
                module = loaded.get("ctranslate2") or importlib.import_module("ctranslate2")
                visible = module.get_cuda_device_count() > 0
            detail = f"CUDA visible={bool(visible)}, requested device={cfg.asr.device}"
            if cfg.asr.device.startswith("cuda") and not visible:
                raise ValueError(detail)
            return detail
        check("CUDA", cuda)
    return checks


def _doctor_command(args, cfg) -> int:
    try:
        if cfg is None:
            cfg = config.load(args.config)
            config.override_url(cfg, getattr(args, "url", None))
        with redirect_stdout(sys.stderr):
            checks = doctor(cfg)
    except (config.ConfigError, OSError, ValueError) as exc:
        checks = [("config", False, str(exc))]
    if args.json:
        _emit([{"check": name, "ok": ok, "detail": detail} for name, ok, detail in checks])
    else:
        for name, ok, detail in checks:
            print(f"{'OK' if ok else 'FAIL'} {name}\t{detail}")
    return int(any(not ok for _, ok, _ in checks))
