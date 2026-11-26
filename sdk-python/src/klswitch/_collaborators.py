"""What the clients and observers need from their collaborators — and no more.

The observers used to take the concrete ``AnalyticsReporter`` and
``ManifestReporter``, which meant neither could be substituted in a test without
a real HTTP transport and a real route walk. These Protocols are deliberately
narrow: a fake implements one or three methods, not batching, flushing and
single-flight logic that nothing here asks for.

**Why the clients' own fields are not Protocol-typed.** The sync and async stacks
genuinely differ in signature — ``poll_once`` and ``close`` are ``def`` in one and
``async def`` in the other — so one Protocol cannot describe both, and two
parallel hierarchies would cost more than they explain for a surface the clients
touch in four places. What *is* shared is typed here; the rest is left concrete
rather than wrapped in a fiction.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from ._analytics import AnalyticsEvent
from ._manifest import ManifestStatus


@runtime_checkable
class AnalyticsRecorder(Protocol):
    """The recording half of analytics — what the observer needs.

    Separate from the lifecycle half on purpose: the observer records and never
    closes, the client closes and never records. One Protocol carrying both would
    hand each of them a method it has no business calling.

    ``record`` is synchronous in both the sync and async reporters, which is what
    makes this one shareable where the lifecycle methods are not.
    """

    def record(self, event: AnalyticsEvent) -> None: ...


@runtime_checkable
class ManifestControl(Protocol):
    """What the client and the re-check observer need from manifest reporting.

    One class serves both stacks, so this Protocol is honest for both.
    """

    def report(self) -> bool: ...

    def status(self) -> ManifestStatus: ...

    def stop(self) -> None: ...
