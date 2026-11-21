"""List or follow incoming phone notifications."""
from __future__ import annotations

import json
import shutil
import sys
import unicodedata

from .notifications import NotificationFeed, SharingOff, UnsupportedPhone
from .phone import PhoneError
from .timeutil import from_ms


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("notifications", help="read incoming phone notifications")
    parser.add_argument("--since", metavar="DURATION|ISO", help="posted since 10m/2h ago or ISO time")
    parser.add_argument("--app", help="match an app label or package, case-insensitively")
    parser.add_argument("--grep", help="search app, title and text, case-insensitively")
    parser.add_argument("--follow", action="store_true", help="follow new notifications, edits and removals")
    parser.add_argument("--json", action="store_true", help="print one JSON object per line")
    parser.add_argument("--limit", type=int, default=50, help="maximum entries per batch (1–500; default 50)")
    parser.set_defaults(func=_run)


def _fit(value: str, width: int, *, pad: bool = False) -> str:
    # Phone strings are untrusted: do not let newlines or terminal escape codes
    # escape a row, and account for wide characters when fitting the terminal.
    value = "".join(c if not unicodedata.category(c).startswith("C") else " " for c in value)
    result, used = "", 0
    for character in value:
        size = 0 if unicodedata.combining(character) else (
            2 if unicodedata.east_asian_width(character) in {"W", "F"} else 1)
        if used + size > width:
            break
        result += character
        used += size
    return result + " " * (width - used) if pad else result


def _columns(time: str, app: str, title: str, text: str, width: int) -> str:
    # Reserve text space on narrow terminals; bound the entire row even below 40 columns.
    app_width = min(18, max(4, width // 6))
    title_width = min(28, max(6, width // 4))
    prefix = f"{_fit(time, 11, pad=True)}  {_fit(app, app_width, pad=True)}  {_fit(title, title_width, pad=True)}  "
    return _fit(prefix + text, width)


def _run(args, cfg) -> int:
    try:
        if not 1 <= args.limit <= 500:
            raise ValueError("--limit must be between 1 and 500")
        feed = NotificationFeed(cfg.phone, cfg.data_dir)
        filters = dict(since=args.since, app=args.app, grep=args.grep, limit=args.limit)
        entries = feed.follow(**filters) if args.follow else feed.fetch(**filters).notifications
        if not args.json:
            print(_columns("TIME", "APP", "TITLE", "TEXT", shutil.get_terminal_size().columns), flush=True)
        printed = set()
        for item in entries:
            if args.json:
                line = json.dumps(item.to_dict(), ensure_ascii=False)
            else:
                title, text = item.title, item.text
                if item.removed_ms is not None:
                    title = "[removed] " + title
                    if item.key in printed:
                        title, text = "[removed]", ""
                elif item.key in printed:
                    title = "[updated] " + title
                line = _columns(from_ms(item.posted_ms).strftime("%m-%d %H:%M"), item.app,
                                title, text, shutil.get_terminal_size().columns)
            print(line, flush=True)
            printed.add(item.key)
        return 0
    except (SharingOff, UnsupportedPhone) as exc:
        print(exc.message, file=sys.stderr)
        return 2
    except PhoneError as exc:
        print(f"Phone notifications request failed ({exc.code}).", file=sys.stderr)
        return 2 if exc.code in {"not_paired", "unauthorized"} else 1
    except ValueError as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2
