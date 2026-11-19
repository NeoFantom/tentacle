"""`tentacle` entry point. Each feature module exposes
`add_commands(subparsers)`; handlers receive `(args, cfg)` and return an exit
code. Modules are imported lazily so a missing optional dependency only breaks
its own commands."""
from __future__ import annotations

import argparse
import importlib
import json
import sys

from . import __version__, config

# Modules that contribute subcommands (see docs/ARCHITECTURE.md §5).
COMMAND_MODULES = ["phone_cli", "calls"]


def _config_commands(sub) -> None:
    p = sub.add_parser("config", help="show or initialise the config file")
    s = p.add_subparsers(dest="config_cmd", required=True)
    i = s.add_parser("init", help="write the commented template")
    i.add_argument("--force", action="store_true")
    i.set_defaults(func=_config_init, needs_config=False)
    sh = s.add_parser("show", help="print effective config (secrets masked)")
    sh.set_defaults(func=_config_show)
    pa = s.add_parser("path", help="print config file path")
    pa.set_defaults(func=lambda a, c: print(c.path) or 0)


def _config_init(args, _cfg) -> int:
    p = config.init(args.config, force=args.force)
    print(f"wrote {p}")
    return 0


def _config_show(args, cfg) -> int:
    print(json.dumps(config.masked(cfg), ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="tentacle", description="tentacle: phone files + call recordings for local agents")
    ap.add_argument("--config", help="config file (default: $TENTACLE_CONFIG or ~/.config/tentacle/config.toml)")
    ap.add_argument("--url", help="use this phone base URL, overriding phone.url and phone.urls")
    ap.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    sub = ap.add_subparsers(dest="cmd", required=True)
    _config_commands(sub)
    for name in COMMAND_MODULES:
        try:
            mod = importlib.import_module(f"tentacle.{name}")
        except ModuleNotFoundError as e:
            if e.name and e.name.startswith("tentacle."):
                continue  # module not implemented yet
            raise
        if hasattr(mod, "add_commands"):
            mod.add_commands(sub)
    return ap


def main(argv: list[str] | None = None) -> int:
    ap = build_parser()
    args = ap.parse_args(argv)
    try:
        cfg = None if getattr(args, "needs_config", True) is False else config.load(args.config)
        if cfg is not None:
            config.override_url(cfg, args.url)
        return int(args.func(args, cfg) or 0)
    except config.ConfigError as e:
        print(f"config error: {e}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
