"""Shared, read-only phone-notification data source for the CLI and local GUI."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
import re
import time
from typing import Iterable, Iterator

from .phone import PhoneClient, PhoneError


OFF_MESSAGE = (
    'Phone notification sharing is off. In the app, enable "Share notifications '
    'with the computer" and grant notification access.'
)
UPDATE_MESSAGE = "Update the phone app to read phone notifications."
_DURATION = re.compile(r"(\d+)([smhdw])\Z")


class SharingOff(PhoneError):
    def __init__(self):
        super().__init__("notifications_disabled", OFF_MESSAGE, 403)


class UnsupportedPhone(PhoneError):
    def __init__(self):
        super().__init__("notifications_unsupported", UPDATE_MESSAGE, 404)


@dataclass(frozen=True)
class Notification:
    key: str
    package: str
    app: str
    title: str
    text: str
    sub_text: str
    category: str
    posted_ms: int
    ongoing: bool
    removed_ms: int | None
    updated_ms: int

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def parse(cls, value: object) -> Notification:
        """Keep all wire-field access here; unknown additive fields are ignored."""
        strings = ("key", "package", "app", "title", "text", "sub_text", "category")
        if (not isinstance(value, dict)
                or any(type(value.get(name)) is not str for name in strings)
                or type(value.get("posted_ms")) is not int
                or value["posted_ms"] < 0
                or type(value.get("ongoing")) is not bool
                or "removed_ms" not in value
                or (value["removed_ms"] is not None
                    and (type(value["removed_ms"]) is not int or value["removed_ms"] < 0))
                or ("updated_ms" in value
                    and (type(value["updated_ms"]) is not int or value["updated_ms"] < 0))):
            raise PhoneError("bad_response", "phone returned an invalid notification object")
        fields = {name: value[name] for name in cls.__dataclass_fields__ if name != "updated_ms"}
        fields["updated_ms"] = value.get("updated_ms", max(value["posted_ms"], value["removed_ms"] or 0))
        return cls(**fields)


@dataclass(frozen=True)
class Batch:
    notifications: tuple[Notification, ...]
    now_ms: int
    enabled: bool

    def to_dict(self) -> dict:
        return {"notifications": [item.to_dict() for item in self.notifications],
                "now_ms": self.now_ms, "enabled": self.enabled}


def parse_since(value: str, now_ms: int) -> int:
    """Relative durations use the phone clock; unzoned ISO times use PC local time."""
    value = value.strip()
    match = _DURATION.fullmatch(value)
    if match:
        scale = {"s": 1000, "m": 60_000, "h": 3_600_000,
                 "d": 86_400_000, "w": 604_800_000}[match[2]]
        return max(0, now_ms - int(match[1]) * scale)
    try:
        parsed = datetime.fromisoformat(value)
        return max(0, int(parsed.timestamp() * 1000))
    except (ValueError, OverflowError, OSError):
        raise ValueError("--since must be a duration such as 10m/2h or an ISO date/time") from None


def filter_entries(entries: Iterable[Notification], *, app: str | None = None, grep: str | None = None,
                   after_ms: int | None = None, limit: int = 500) -> tuple[Notification, ...]:
    if type(limit) is not int or not 1 <= limit <= 500:
        raise ValueError("limit must be between 1 and 500")
    wanted = app.casefold() if app else ""
    needle = grep.casefold() if grep else ""
    newest: dict[str, Notification] = {}
    for item in entries:
        if item.key not in newest or item.updated_ms > newest[item.key].updated_ms:
            newest[item.key] = item
    return tuple(sorted((item for item in newest.values()
                         if (after_ms is None or item.posted_ms > after_ms)
                         and (not wanted or wanted in item.app.casefold()
                              or wanted == item.package.casefold())
                         and (not needle or any(needle in text.casefold()
                                               for text in (item.app, item.title, item.text)))),
                        key=lambda item: item.updated_ms, reverse=True)[:limit])


class NotificationFeed:
    """Each consumer owns its client; no notifications are persisted on the PC."""

    def __init__(self, phone_cfg, cache_dir: Path | None = None):
        self.client = PhoneClient(phone_cfg, cache_dir)

    def fetch(self, *, since_ms: int | None = None, wait: int = 0,
              app: str | None = None, grep: str | None = None, limit: int = 500,
              since: str | None = None) -> Batch:
        if type(wait) is not int or not 0 <= wait <= 60:
            raise ValueError("wait must be between 0 and 60 seconds")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValueError("limit must be between 1 and 500")
        if since_ms is not None and (type(since_ms) is not int or since_ms < 0):
            raise ValueError("since must be nonnegative phone epoch milliseconds")
        after = None
        if since is not None:
            after = parse_since(since, 0)  # Reject invalid input before contacting the phone.
            if _DURATION.fullmatch(since.strip()):
                # Learn the phone clock without a waiting request. The resulting
                # threshold remains a posting filter as well as a safe lower
                # bound for the API's change cursor.
                after = parse_since(since, self.fetch(limit=1).now_ms)
            since_ms = max(since_ms or 0, after)
        # Fetch the retained window before applying label/grep/limit locally. The
        # phone's app query takes only packages, whereas our app filter takes labels too.
        params = {"wait": wait, "limit": 500}
        if since_ms is not None:
            params["since"] = since_ms
        try:
            payload = self.client._json(
                "notifications", params, timeout=max(self.client.timeout, wait + 5),
            )
        except PhoneError as exc:
            if exc.status == 404:
                raise UnsupportedPhone() from exc
            if exc.status == 403 and exc.code == "disabled":
                raise SharingOff() from exc
            raise
        if (type(payload.get("enabled")) is not bool
                or type(payload.get("now_ms")) is not int or payload["now_ms"] < 0
                or not isinstance(payload.get("notifications"), list)):
            raise PhoneError("bad_response", "phone returned an invalid notifications response")
        if not payload["enabled"]:
            raise SharingOff()
        entries = tuple(Notification.parse(item) for item in payload["notifications"])
        # Numeric since is a change cursor, not a user-facing posting-time filter.
        return Batch(filter_entries(entries, app=app, grep=grep, after_ms=after, limit=limit),
                     payload["now_ms"], True)

    def follow(self, *, since: str | None = None, app: str | None = None,
               grep: str | None = None, limit: int = 500,
               wait: int = 55) -> Iterator[Notification]:
        """Yield a snapshot, then new keys, content changes and known removals.

        Advance using the unfiltered feed so unmatched apps never cause a busy loop.
        Updates and removals advance the same updated_ms cursor as new posts.
        """
        if since is not None:
            parse_since(since, 0)
        batch = self.fetch()
        after = parse_since(since, batch.now_ms) if since is not None else None
        known = {item.key: item for item in batch.notifications}
        emitted: dict[str, Notification] = {}
        for item in filter_entries(batch.notifications, app=app, grep=grep, after_ms=after, limit=limit):
            emitted[item.key] = item
            yield item
        cursor = max((item.updated_ms for item in batch.notifications), default=0)
        if after is not None:
            cursor = max(cursor, after)
        while True:
            started = time.monotonic()
            batch = self.fetch(since_ms=cursor, wait=wait)
            if batch.notifications:
                cursor = max(cursor, *(item.updated_ms for item in batch.notifications))
            changes = []
            for item in reversed(batch.notifications):
                previous = known.get(item.key)
                if previous is not None and item.updated_ms <= previous.updated_ms:
                    continue
                known[item.key] = item
                printed = emitted.get(item.key)
                removed = (printed is not None and previous is not None and previous.removed_ms is None
                           and item.removed_ms is not None)
                matches = filter_entries((item,), app=app, grep=grep, after_ms=after)
                if removed or (matches and (printed is None
                        or (item.title, item.text) != (printed.title, printed.text))):
                    changes.append(item)
            # Process every version before limiting output, so hidden changes also
            # advance state and cannot repeatedly wake the same long poll.
            for item in changes[-limit:]:
                emitted[item.key] = item
                yield item
            if time.monotonic() - started < 0.1:
                time.sleep(0.1)
