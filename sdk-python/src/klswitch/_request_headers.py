"""Header construction for every backend call (rules poll,
decisions).

Built centrally so the wire-level shape stays consistent across
pollers / reporters and so SDK-controlled headers (auth, instance
key, user agent) can't be accidentally overridden by the operator's
``extra_headers``.

``X-Env-Hint`` is observational — the backend derives the authoritative
env from the token. The hint exists so a misconfigured deploy (prod
token distributed to staging pods) is loud rather than silent.
"""

from __future__ import annotations

import os
from typing import Mapping

from .logger import Logger

_ENV_HINT_VARS = ("KS_ENV", "POD_NAMESPACE", "K_SERVICE_REVISION")
_RESERVED = frozenset({"authorization", "x-instance-key", "user-agent"})


def build_headers(
    *,
    token: str,
    instance_key: str,
    sdk_version: str,
    extra_headers: Mapping[str, str] | None = None,
    env_hint: str | None = None,
    env: Mapping[str, str] | None = None,
    logger: Logger | None = None,
) -> dict[str, str]:
    """Build the headers for one outbound call.

    ``env_hint`` overrides resolution from ``env`` when explicitly set;
    pass ``None`` (the default) to let the chain resolve from env vars.
    """
    if env is None:
        env = os.environ

    headers: dict[str, str] = {}
    if extra_headers:
        _warn_if_reserved(extra_headers, logger)
        headers.update(extra_headers)

    # SDK-controlled headers go last so they always win over operator
    # extras (even if extras tried to set them).
    headers["Authorization"] = f"Bearer {token}"
    headers["X-Instance-Key"] = instance_key
    headers["User-Agent"] = f"klswitch-python/{sdk_version}"
    headers["Accept-Encoding"] = "gzip"

    hint = env_hint if env_hint is not None else resolve_env_hint(env)
    if hint:
        headers["X-Env-Hint"] = hint
    return headers


def resolve_env_hint(env: Mapping[str, str] | None = None) -> str | None:
    """First non-empty value from the standard env-hint variables, trimmed."""
    if env is None:
        env = os.environ
    for name in _ENV_HINT_VARS:
        value = env.get(name)
        if value:
            trimmed = value.strip()
            if trimmed:
                return trimmed
    return None


def _warn_if_reserved(
    extras: Mapping[str, str],
    logger: Logger | None,
) -> None:
    if logger is None:
        return
    for key in extras.keys():
        if key.lower() in _RESERVED:
            logger.warning(
                "extra_headers includes a reserved header — SDK value wins",
                {"header": key},
            )
