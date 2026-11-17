"""Time helpers shared by the PC client and pipeline.

All persisted timestamps are local, timezone-aware ISO-8601 strings with
seconds precision.  Epoch millisecond conversion is kept here so callers use
the same timezone rules.
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, tzinfo


_RELATIVE_RE = re.compile(r"^(\d+)([mhdw])$")


def now_iso() -> str:
    """Return the current local time as ISO-8601, rounded to seconds."""
    return datetime.now().astimezone().isoformat(timespec="seconds")


def parse_since(text: str, now: datetime) -> datetime:
    """Parse a relative duration or local ISO date/time relative to *now*.

    Accepted relative suffixes are minutes, hours, days and weeks.  Absolute
    values are interpreted in ``now``'s timezone.
    """
    value = text.strip()
    match = _RELATIVE_RE.fullmatch(value)
    if match:
        amount = int(match.group(1))
        unit = match.group(2)
        delta = {
            "m": timedelta(minutes=amount),
            "h": timedelta(hours=amount),
            "d": timedelta(days=amount),
            "w": timedelta(weeks=amount),
        }[unit]
        return now - delta

    formats = ("%Y-%m-%d", "%Y-%m-%dT%H:%M")
    for fmt in formats:
        try:
            parsed = datetime.strptime(value, fmt)
        except ValueError:
            continue
        if now.tzinfo is not None:
            parsed = parsed.replace(tzinfo=now.tzinfo)
        return parsed
    raise ValueError(
        f"invalid time {text!r}; expected 30m, 2h, 3d, 1w, "
        "YYYY-MM-DD, or YYYY-MM-DDTHH:MM"
    )


def to_ms(value: datetime) -> int:
    """Convert a datetime to Unix epoch milliseconds."""
    if value.tzinfo is None:
        value = value.astimezone()
    return int(value.timestamp() * 1000)


def from_ms(value: int | float, tz: tzinfo | None = None) -> datetime:
    """Convert Unix epoch milliseconds to a timezone-aware datetime.

    The local timezone is used unless *tz* is supplied.
    """
    if tz is not None:
        return datetime.fromtimestamp(value / 1000, tz=tz)
    # Convert at the requested instant so local DST/historical offset rules are
    # applied, rather than reusing the offset in effect today.
    return datetime.fromtimestamp(value / 1000).astimezone()
