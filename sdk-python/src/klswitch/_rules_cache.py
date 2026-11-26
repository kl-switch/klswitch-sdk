"""Boot-time disk cache for the last-known-good ruleset.

Operator opts in via ``Client(cache_path=...)``. After every successful
remote fetch the wrapper persists the accepted ruleset JSON to that
path (atomic: tmpfile + ``Path.replace``). When the SDK boots, if the
very first fetch fails AND a cache file exists, the wrapper loads the
cache and serves traffic against the last-known-good ruleset while
polling keeps retrying the real backend in the background — so an
outage at customer boot time no longer means "no rules".

Pure file IO. The fall-back / persist decisions live in the Client /
AsyncClient; this module just handles read/write.
"""

from __future__ import annotations

import json
import re
import tempfile
from pathlib import Path
from typing import Any

from ._json import decode_ruleset_payload
from .logger import Logger
from .types import RuleSetPayload

_UNSAFE_CACHE_NAME = re.compile(r"[^a-zA-Z0-9._-]")


def default_cache_path(env: str) -> str:
    """Return the conventional ``${tmpdir}/ks-cache-<env>.json`` path.
    Operators can supply their own path verbatim — this is just the
    helper for the common case. Unsafe characters in ``env`` are
    stripped so the result is always inside the system tmp dir.
    """
    safe = env if env else "default"
    safe = _UNSAFE_CACHE_NAME.sub("_", safe)
    return str(Path(tempfile.gettempdir()) / f"ks-cache-{safe}.json")


def read_cached_ruleset(path: str | None, logger: Logger) -> RuleSetPayload | None:
    """Read + parse the cached ruleset from ``path``. Returns ``None``
    if the file doesn't exist, is empty, has the wrong shape, or
    doesn't parse — a corrupt cache is just "no cache available",
    never an error.
    """
    if not path:
        return None
    p = Path(path)
    if not p.exists():
        return None
    try:
        raw = p.read_text(encoding="utf-8")
    except OSError as e:
        logger.warning(
            "ruleset cache unreadable; ignoring",
            {"path": path, "error": str(e)},
        )
        return None
    if not raw:
        logger.warning("ruleset cache is empty; ignoring", {"path": path})
        return None
    # Minimal shape check — engine will validate fully on load_rules.
    try:
        parsed: dict[str, Any] = json.loads(raw)
    except json.JSONDecodeError as e:
        logger.warning(
            "ruleset cache JSON parse failed; ignoring",
            {"path": path, "error": str(e)},
        )
        return None
    if not isinstance(parsed, dict) or "schema_version" not in parsed or not isinstance(parsed.get("rules"), list):
        logger.warning("ruleset cache shape invalid; ignoring", {"path": path})
        return None
    try:
        return decode_ruleset_payload(raw)
    except Exception as e:  # noqa: BLE001 - dataclass build can raise various
        logger.warning(
            "ruleset cache decode failed; ignoring",
            {"path": path, "error": str(e)},
        )
        return None


def write_cached_ruleset(
    path: str | None,
    payload: RuleSetPayload,
    logger: Logger,
) -> None:
    """Atomically persist ``payload`` to ``path``. Writes to a sibling
    ``<path>.tmp`` then renames into place — a crash mid-write leaves
    either the previous good cache OR the new one, never a truncated
    file.

    Best-effort: any IO error is logged and swallowed. Disk cache is
    boot-time resilience, not a critical-path dependency.
    """
    if not path:
        return
    p = Path(path)
    tmp = Path(f"{path}.tmp")
    try:
        # Ensure parent dir exists — operators sometimes point at a
        # subdir that hasn't been pre-created.
        p.parent.mkdir(parents=True, exist_ok=True)
        body = _encode_ruleset(payload)
        tmp.write_text(body, encoding="utf-8")
        tmp.replace(p)
    except OSError as e:
        logger.warning(
            "ruleset cache write failed",
            {"path": path, "error": str(e)},
        )
        try:
            if tmp.exists():
                tmp.unlink()
        except OSError:
            pass


def _encode_ruleset(payload: RuleSetPayload) -> str:
    """Serialise a RuleSetPayload to the wire JSON shape (snake_case,
    dataclass-aware). Goes via the same encoder the engine load path
    uses so the cached bytes are exactly what the engine would have
    accepted.
    """
    from . import _json
    return _json.encode_ruleset(payload)
