"""Validate the small JSON Schema subset used by the packaged contracts."""
from __future__ import annotations

import math
from typing import Any


class ValidationError(ValueError):
    """A value violates the contract at the JSON path in the message."""


def _is_type(value: Any, name: str) -> bool:
    checks = {
        "null": lambda: value is None,
        "boolean": lambda: isinstance(value, bool),
        "string": lambda: isinstance(value, str),
        "object": lambda: isinstance(value, dict),
        "array": lambda: isinstance(value, list),
        "integer": lambda: (not isinstance(value, bool) and
                            isinstance(value, (int, float)) and
                            (isinstance(value, int) or
                             (math.isfinite(value) and value.is_integer()))),
        "number": lambda: (not isinstance(value, bool) and
                           isinstance(value, (int, float)) and
                           (isinstance(value, int) or math.isfinite(value))),
    }
    if name not in checks:
        raise ValueError(f"unsupported schema type: {name}")
    return checks[name]()


def _equal(left: Any, right: Any) -> bool:
    # Python considers True == 1, whereas JSON Schema does not.
    if isinstance(left, bool) != isinstance(right, bool):
        return False
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(_equal(left[k], right[k]) for k in left)
    return left == right


def validate(value: Any, schema: dict, path: str = "$") -> None:
    """Raise ValidationError at the first violation; otherwise return None."""
    expected = schema.get("type")
    types = expected if isinstance(expected, list) else [expected]
    if expected is not None and not any(_is_type(value, name) for name in types):
        raise ValidationError(f"{path}: expected {' or '.join(types)}")
    if "enum" in schema and not any(_equal(value, item) for item in schema["enum"]):
        raise ValidationError(f"{path}: value is not in enum {schema['enum']!r}")
    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                raise ValidationError(f"{path}.{key}: required property is missing")
        properties = schema.get("properties", {})
        for key, item in value.items():
            if key in properties:
                validate(item, properties[key], f"{path}.{key}")
            elif schema.get("additionalProperties") is False:
                raise ValidationError(f"{path}.{key}: additional property is not allowed")
    if isinstance(value, list) and "items" in schema:
        for index, item in enumerate(value):
            validate(item, schema["items"], f"{path}[{index}]")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if isinstance(value, float) and not math.isfinite(value):
            raise ValidationError(f"{path}: number must be finite")
        if "minimum" in schema and value < schema["minimum"]:
            raise ValidationError(f"{path}: must be >= {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            raise ValidationError(f"{path}: must be <= {schema['maximum']}")
