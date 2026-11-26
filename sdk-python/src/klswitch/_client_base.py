"""Behaviour shared by the sync and async clients.

Both wrappers answer the same questions about the same state: who this instance
is, what version is loaded, what the engine and the reporters are doing. None of
that is I/O-bound, so none of it has any business existing twice — and it did,
character for character, in ``client.py`` and ``async_client.py``.

What stays in the two clients is the part ``async`` genuinely forces apart:
construction, the awaited fetch and post paths, ``close``/``aclose``, and the
context-manager protocol. Everything here is the part that never needed to
differ, and keeping it in one place is what stops the next fix landing in one
client and not the other.

This base owns no lifecycle. It reads state the subclasses create and must not
be instantiated on its own.
"""

from __future__ import annotations

import threading
import time

from . import _json
from ._analytics import AnalyticsStatus, disabled_analytics_status
from ._instance_id import InstanceIdResolution
from ._bridge import Bridge
from ._manifest import ManifestStatus, disabled_manifest_status
from ._url_builder import ResolvedUrls
from .errors import ClosedClientError
from .status import Status
from .types import EngineStatus


class _ClientBase:
    """Read-only accessors and status assembly common to both clients."""

    @property
    def instance_id(self) -> str:
        """Resolved instance identifier. Stable for the process's lifetime."""
        return self._instance_id.value

    @property
    def urls(self) -> ResolvedUrls:
        """Resolved backend URLs (rules, decisions, manifest)."""
        return self._urls

    @property
    def sdk_version(self) -> str:
        """SDK version string, surfaced for adapters that want to log it."""
        return SDK_VERSION

    @property
    def active_rules_version(self) -> int | None:
        """Currently active ruleset version, or ``None`` if no successful load."""
        with self._mu:
            return self._active_rules_version

    def analytics_status(self) -> AnalyticsStatus:
        """Snapshot of the analytics reporter's status (or ``disabled``)."""
        with self._mu:
            rep = self._analytics
        return rep.status() if rep is not None else disabled_analytics_status()

    def manifest_status(self) -> ManifestStatus:
        """Snapshot of the manifest reporter's status (or ``disabled``)."""
        with self._mu:
            rep = self._manifest
        return rep.status() if rep is not None else disabled_manifest_status()

    def get_status(self) -> Status:
        """Combined engine + wrapper status. Safe after :meth:`close`."""
        with self._mu:
            bridge, handle = self._bridge, getattr(self, "_handle", 0)
            polling_active = self._polling_active
            last_refresh_at = self._last_refresh_at
            last_refresh_ok = self._last_refresh_ok
            last_refresh_error = self._last_refresh_error

        ready = bridge is not None and handle != 0
        if not ready:
            return Status(
                initialized=False,
                schema_version="1.0",
                active_rules_version=None,
                rule_count=0,
                last_load_ok=False,
                last_error=None,
                ready=False,
                polling=False,
                last_refresh_at=last_refresh_at,
                last_refresh_ok=last_refresh_ok,
                last_refresh_error=last_refresh_error,
            )
        assert bridge is not None  # narrow for mypy
        engine: EngineStatus = _json.decode_engine_status(bridge.get_status(handle))
        return Status(
            initialized=engine.initialized,
            schema_version=engine.schema_version,
            active_rules_version=engine.active_rules_version,
            rule_count=engine.rule_count,
            last_load_ok=engine.last_load_ok,
            last_error=engine.last_error,
            ready=True,
            polling=polling_active,
            last_refresh_at=last_refresh_at,
            last_refresh_ok=last_refresh_ok,
            last_refresh_error=last_refresh_error,
        )

    def _snapshot(self) -> tuple[Bridge, int]:
        with self._mu:
            bridge, handle = self._bridge, getattr(self, "_handle", 0)
        if bridge is None or handle == 0:
            raise ClosedClientError()
        return bridge, handle


    def _on_refresh_failed(self, err: Exception) -> None:
        with self._mu:
            closed = self._bridge is None
        if closed:
            return
        with self._mu:
            self._last_refresh_at = int(time.time() * 1000)
            self._last_refresh_ok = False
            self._last_refresh_error = str(err)
            # Reserved for the future instance-health channel —
            # refresh failures are no longer stamped onto decision events.
            self._pending_error_count += 1


# ─── Analytics helpers ─────────────────────────────────────────────────────


# ─── Shared module-level values ────────────────────────────────────────────
# These lived in client.py, and async_client.py imported them from it — so the async
# wrapper depended on the sync one for a version string and two helpers. They belong
# to neither client in particular.

SDK_VERSION = "0.1.0"
DEFAULT_REFRESH_INTERVAL_SECONDS = 30.0
