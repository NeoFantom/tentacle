"""Wait for verification codes relayed by the Tentacle phone app."""
from __future__ import annotations

import json
import re
import sys
import time

from .phone import PhoneClient, PhoneError


_DURATION = re.compile(r"^(\d+)([sm])$")


def _duration_ms(value: str) -> int:
    match = _DURATION.fullmatch(value)
    if match is None:
        raise ValueError("--since must be now or a duration such as 30s or 2m")
    scale = 1_000 if match.group(2) == "s" else 60_000
    return int(match.group(1)) * scale


def _wait_seconds(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError:
        raise ValueError("--wait must be a nonnegative number of seconds") from None
    if seconds < 0:
        raise ValueError("--wait must be a nonnegative number of seconds")
    return seconds


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("code", help="wait for a verification code from the phone")
    parser.add_argument("--wait", type=_wait_seconds, default=120,
                        help="maximum seconds to wait (default: 120)")
    parser.add_argument("--from", dest="sender", metavar="TEXT",
                        help="match sender or message snippet")
    parser.add_argument("--since", default="now", metavar="now|DURATION",
                        help="only codes after start, or include a duration such as 30s or 2m")
    parser.add_argument("--json", action="store_true", help="print the matched code object as JSON")
    parser.set_defaults(func=_run)


def _matches(item: dict, wanted: str | None) -> bool:
    if wanted is None:
        return True
    needle = wanted.casefold()
    return needle in item["sender"].casefold() or needle in item["snippet"].casefold()


def _run(args, cfg) -> int:
    try:
        client = PhoneClient(cfg.phone, cfg.data_dir)
        initial = client.codes(wait=0)
        if not initial["enabled"]:
            print("Code relay is off in the app (Settings → Relay verification codes)",
                  file=sys.stderr)
            return 2

        now_ms = initial["now_ms"]
        if args.since == "now":
            cursor = now_ms
            available: list[dict] = []
        else:
            cursor = now_ms - _duration_ms(args.since)
            available = [item for item in initial["codes"] if item["received_ms"] > cursor]

        deadline = time.monotonic() + args.wait
        while True:
            # Newest first: with --since several codes may already be waiting, and the
            # latest one is the one a website accepts.
            for item in sorted(available, key=lambda entry: entry["received_ms"], reverse=True):
                if _matches(item, args.sender):
                    if args.json:
                        print(json.dumps(item, ensure_ascii=False))
                    else:
                        print(item["code"])
                    return 0
            if available:
                cursor = max(cursor, *(item["received_ms"] for item in available))

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            wait = min(55, max(1, int(remaining + 0.999)))
            poll_started = time.monotonic()
            payload = client.codes(since_ms=cursor, wait=wait)
            if not payload["enabled"]:
                print("Code relay is off in the app (Settings → Relay verification codes)",
                      file=sys.stderr)
                return 2
            available = payload["codes"]
            # The phone may decline to occupy another shared long-poll slot.
            # Avoid hammering it if that produces an immediate empty response.
            if not available and time.monotonic() - poll_started < 0.1:
                time.sleep(min(0.1, max(0.0, deadline - time.monotonic())))

        detail = f" from {args.sender}" if args.sender else ""
        print(f"No verification code{detail} arrived before the timeout.", file=sys.stderr)
        return 1
    except PhoneError as exc:
        print(f"Phone code request failed ({exc.code}).", file=sys.stderr)
        return 2 if exc.code == "not_paired" else 1
    except ValueError as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2
