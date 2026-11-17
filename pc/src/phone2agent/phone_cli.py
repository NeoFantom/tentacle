"""File browsing commands for the phone HTTP API."""
from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path, PurePosixPath

from . import config
from .phone import PhoneClient, PhoneError, parse_pair_string
from .timeutil import from_ms, parse_since, to_ms


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
    """Register handlers compatible with :mod:`phone2agent.cli`."""
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
            parser.add_argument("address", help="p2a pairing string or HTTP URL")
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


def _emit(data) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2))


def _size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{int(value)} B" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    raise AssertionError("unreachable")


def _mtime(ms: int) -> str:
    return from_ms(ms).astimezone().strftime("%Y-%m-%d %H:%M")


def _entries(entries) -> None:
    for entry in entries:
        marker = {"dir": "d", "file": "f", "other": "?"}.get(entry["type"], "?")
        print(f"{marker} {_size(entry['size']):>10}  {_mtime(entry['mtime_ms'])}  {entry['path']}")


def _pair(args) -> dict:
    url, token, _name = parse_pair_string(args.address, args.token)
    path = Path(config._expand(str(args.config))) if args.config else config.default_config_path()
    if not path.exists():
        config.init(path)
    cfg = config.load(path)
    config.set_values(path, "phone", {"url": url, "token": token})
    cfg.phone.url, cfg.phone.token = url, token
    return PhoneClient(cfg.phone).ping()


def _audio_extensions(cfg) -> list[str]:
    # Reuse configuration defaults instead of maintaining a second extension list.
    extensions = set(config.SourceConfig(name="", path="").extensions)
    for source in cfg.sources:
        extensions.update(source.extensions)
    return sorted(extensions)


def _discover(client, cfg, since: str) -> dict:
    extensions = _audio_extensions(cfg)
    result = client.find(ext=",".join(extensions), limit=5000,
                         since_ms=to_ms(parse_since(since, datetime.now().astimezone())))
    directories = {}
    for entry in result["entries"]:
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
            "truncated": result["truncated"]}


def _show_discovery(result) -> None:
    for directory in result["directories"]:
        print(f"{directory['count']:>6}  {_mtime(directory['newest_mtime_ms'])}  "
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
        if args.cmd == "pair":
            result = _pair(args)
        else:
            client = PhoneClient(cfg.phone)
            if args.cmd == "ping":
                result = client.ping()
            elif args.cmd == "ls":
                result = client.ls(args.path, hidden=args.hidden)
            elif args.cmd == "stat":
                result = client.stat(args.path)
            elif args.cmd in {"find", "recent"}:
                now = datetime.now().astimezone()
                since = to_ms(parse_since(args.since, now)) if args.since else None
                until = to_ms(parse_since(args.until, now)) if args.until else None
                if since is not None and until is not None and since > until:
                    raise ValueError("--since must not be later than --until")
                result = client.find(root=getattr(args, "root", None), ext=args.ext,
                                     name=args.name, since_ms=since, until_ms=until,
                                     limit=args.limit, max_depth=args.max_depth,
                                     type=args.type, sort=args.sort, hidden=args.hidden)
            elif args.cmd == "get":
                dest = Path(args.dest) if args.dest else Path(PurePosixPath(args.path).name)
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
        elif args.cmd in {"ls", "find", "recent"}:
            _entries(result["entries"])
            if result.get("truncated"):
                print("Results truncated; narrow the filters or increase --limit.")
        elif args.cmd == "stat":
            _entries([result])
        elif args.cmd == "get":
            print(result["dest"])
        elif args.cmd == "cat":
            print(result["text"], end="")
        else:
            _show_discovery(result)
        return 0
    except PhoneError as exc:
        print(f"phone error ({exc.code}): {exc.message}", file=sys.stderr)
        return 2 if exc.code == "not_paired" else 1
    except (ValueError, OSError) as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2
