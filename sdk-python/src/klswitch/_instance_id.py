"""Instance-key resolution.

The instance key answers "which runtime is this?". The token answers
"which service is this?". They're complementary: one token is shared
across every pod of a service; each pod has a distinct instance key.

Resolution order (first valid wins):

  1. config.instance_id            — operator passed it explicitly
  2. KS_INSTANCE_ID env var        — env-only config
  3. POD_NAME env var              — k8s downward API convention
  4. ``{hostname}-{pid}-{startMs}``  — deterministic per-process fallback

Invalid values (charset / length) are skipped with a warning and the
chain continues — never crash; the operator is giving a hint, not
blocking their app boot.
"""

from __future__ import annotations

import enum
import os
import re
import socket
import time
from dataclasses import dataclass
from typing import Callable, Mapping

from .logger import Logger


class InstanceIdSource(enum.Enum):
    CONFIG = "config"
    ENV = "env"
    POD = "pod"
    DERIVED = "derived"


@dataclass(frozen=True)
class InstanceIdResolution:
    """Resolved instance identifier plus the source it came from."""

    value: str
    source: InstanceIdSource


MAX_INSTANCE_ID_LEN = 128
_VALID = re.compile(r"^[a-zA-Z0-9_\-.:/]+$")


def is_valid_instance_id(v: str | None) -> bool:
    """True iff ``v`` is non-empty and matches the soft-validation rules."""
    if v is None:
        return False
    trimmed = v.strip()
    if not trimmed or len(trimmed) > MAX_INSTANCE_ID_LEN:
        return False
    return bool(_VALID.match(trimmed))


def resolve_instance_id(
    config_value: str | None,
    env: Mapping[str, str] | None = None,
    logger: Logger | None = None,
) -> InstanceIdResolution:
    """Walk the fallback chain. ``env`` defaults to ``os.environ``."""
    if env is None:
        env = os.environ

    candidates: list[tuple[str | None, InstanceIdSource]] = [
        (config_value, InstanceIdSource.CONFIG),
        (env.get("KS_INSTANCE_ID"), InstanceIdSource.ENV),
        (env.get("POD_NAME"), InstanceIdSource.POD),
    ]

    for value, source in candidates:
        if not value:
            continue
        if is_valid_instance_id(value):
            return InstanceIdResolution(value=value.strip(), source=source)
        if logger is not None:
            logger.warning(
                "instance_id candidate rejected — falling through",
                {"source": source.value, "reason": "value violates charset/length rules"},
            )
    return InstanceIdResolution(value=_derive(), source=InstanceIdSource.DERIVED)


_NON_ID_CHAR = re.compile(r"[^a-zA-Z0-9_\-.:/]+")


def _derive() -> str:
    try:
        host = socket.gethostname() or "unknown"
    except OSError:
        host = "unknown"
    # Hostnames can contain characters outside our validation charset
    # (rarely, but possible). Replace anything we don't allow with `-`.
    host = _NON_ID_CHAR.sub("-", host) or "unknown"
    pid = os.getpid()
    start_ms = int(time.time() * 1000)
    return f"{host}-{pid}-{start_ms}"


# Build-time resolved callables for tests that want to stub.
_envlike: Callable[[str], str | None] = lambda k: os.environ.get(k)
