"""Combined engine + wrapper status returned by :meth:`Client.get_status`."""

from __future__ import annotations

from dataclasses import dataclass

from .types import EngineStatus


@dataclass(frozen=True)
class Status(EngineStatus):
    """Engine status plus wrapper-owned fields.

    Inherits every field from :class:`EngineStatus` (``initialized``,
    ``schema_version``, ``active_rules_version``, ``rule_count``,
    ``last_load_ok``, ``last_error``) so callers can access them directly.

    The wrapper-owned fields cover the host runtime view that the engine
    cannot know about — poll state and the last refresh outcome. The
    ``last_refresh_error`` field exists because the Phase 1 review of
    Node/Java surfaced that ``last_refresh_ok`` alone forced operators to
    grep logs to find out *why* a refresh failed.
    """

    # Wrapper-owned. `ready` and `polling` have no defaults because a Status
    # with ready=False would be contradictory next to its inherited
    # initialized/last_load_ok fields; callers (the Client) always know both.
    ready: bool
    polling: bool
    # Epoch milliseconds of the last refresh attempt (success or failure).
    last_refresh_at: int | None = None
    last_refresh_ok: bool | None = None
    last_refresh_error: str | None = None
