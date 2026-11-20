"""Send a notification from the computer to the phone owner."""
from __future__ import annotations

import json
import sys
import time
import unicodedata
import uuid

from .phone import PhoneClient, PhoneError


def _wait_seconds(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError:
        raise ValueError("--wait-ack must be a nonnegative number of seconds") from None
    if seconds < 0:
        raise ValueError("--wait-ack must be a nonnegative number of seconds")
    return seconds


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("notify", help="notify the phone owner")
    parser.add_argument("text", metavar="TEXT", help="notification text")
    parser.add_argument("--title", metavar="T", help="notification title")
    parser.add_argument("--urgent", action="store_true",
                        help="request an urgent alert (only when the owner allowed it)")
    parser.add_argument("--wait-ack", nargs="?", const=300, type=_wait_seconds,
                        metavar="SECONDS", help="wait for acknowledgement (default: 300 seconds)")
    parser.add_argument("--json", action="store_true", help="print the phone response as JSON")
    parser.set_defaults(func=_run)


def _delivery_message(result: dict) -> str:
    if not result["shown"]:
        return f"not shown: {result.get('reason', 'unknown')}"
    if result.get("reason") == "urgent_not_allowed":
        return "delivered (urgent alerts are off in the app, sent as a normal notification)"
    if result.get("reason") == "full_screen_not_allowed":
        return "delivered (full-screen alerts not allowed on the phone)"
    return "delivered"


def _validate_text(text: str, title: str | None) -> None:
    if not 1 <= len(text) <= 1000:
        raise ValueError("TEXT must contain 1 to 1000 characters")
    if title is not None and len(title) > 100:
        raise ValueError("--title must contain at most 100 characters")
    for value in (text, title):
        if value is not None and any(
                character != "\n" and unicodedata.category(character) == "Cc"
                for character in value):
            raise ValueError("notification text must not contain control characters")


def _post_with_retry(client: PhoneClient, args, notification_id: str) -> dict:
    try:
        return client.send_notification(
            args.text, notification_id=notification_id, title=args.title, urgent=args.urgent,
        )
    except PhoneError as exc:
        if exc.code not in {"timeout", "unreachable"}:
            raise
    return client.send_notification(
        args.text, notification_id=notification_id, title=args.title, urgent=args.urgent,
    )


def _run(args, cfg) -> int:
    try:
        _validate_text(args.text, args.title)
        client = PhoneClient(cfg.phone, cfg.data_dir)
        result = _post_with_retry(client, args, str(uuid.uuid4()))
        if args.json:
            print(json.dumps(result, ensure_ascii=False))
        else:
            print(_delivery_message(result))
        if not result["shown"]:
            return 1
        if args.wait_ack is None:
            return 0

        deadline = time.monotonic() + args.wait_ack
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            wait = min(55, max(1, int(remaining + 0.999)))
            poll_started = time.monotonic()
            state = client.notification(result["id"], wait=wait)
            if state["state"] in {"acknowledged", "dismissed"}:
                if args.json:
                    print(json.dumps(state, ensure_ascii=False))
                else:
                    print(state["state"])
                return 0
            if state["state"] == "expired":
                break
            if time.monotonic() - poll_started < 0.1:
                time.sleep(min(0.1, max(0.0, deadline - time.monotonic())))
        print("no response before timeout", file=sys.stderr)
        return 1
    except PhoneError as exc:
        if exc.status == 429 or exc.code == "rate_limited":
            print(f"rate limited: {exc.message}", file=sys.stderr)
            return 1
        print(f"Phone notification failed ({exc.code}).", file=sys.stderr)
        return 2 if exc.code in {"not_paired", "unauthorized"} else 1
    except ValueError as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2
