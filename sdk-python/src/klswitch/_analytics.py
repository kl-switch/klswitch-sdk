"""Wrapper-owned analytics reporter.

One event is recorded per :meth:`Client.evaluate` call (including no-match
cases). Events live in a bounded ring buffer; a background worker flushes
batches as gzipped POSTs to ``/v1/decisions``. The hot path (``record``)
is non-blocking and never raises.

Overflow policy: **drop-oldest**. When the buffer fills (backend outage,
etc.), the reporter keeps the most-recent activity — operators reach for
the dashboard during incidents, not last week's events. ``collections.deque``
with ``maxlen`` provides this natively.

Failure policy: **drop-batch, no retry**. A failed POST loses that batch;
the next flush picks up newer events. Retry without an idempotency key
would create duplicates if the original landed but the response was lost.

Custom sink: pass ``AnalyticsOptions.sink`` to bypass the HTTP POST entirely
(useful for piping events into the operator's own analytics pipeline).
SDK-side ring/flush plumbing still applies.
"""

from __future__ import annotations

import asyncio
import enum
import gzip as _gzip
import inspect
import json
import threading
import urllib.error
import urllib.request
import uuid
from collections import deque
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Mapping, cast

from .logger import Logger, NullLogger

# Defaults — referenced by callers via the public re-export.
DEFAULT_BATCH_SIZE = 100
DEFAULT_FLUSH_INTERVAL_SECONDS = 5.0
DEFAULT_MAX_QUEUE_SIZE = 10_000
DEFAULT_POST_TIMEOUT_SECONDS = 5.0

#: Polymorphic discriminator value for evaluation analytics events. The
#: analytics ingest (``POST /v1/analytics``) dispatches on the wire ``type``;
#: only evaluation events exist today.
ANALYTICS_EVENT_TYPE = "evaluation"


class LatencyBucket(str, enum.Enum):
    """Cross-language latency-bucket enum. Wire-format strings match
    Node/Java/Go and ``klswitch-console/src/lib/zod/analytics.ts``.
    """

    LT_50US = "lt_50us"
    LT_500US = "lt_500us"
    LT_5MS = "lt_5ms"
    GE_5MS = "ge_5ms"

    @classmethod
    def for_seconds(cls, elapsed_seconds: float) -> "LatencyBucket":
        """Map an elapsed-seconds duration to the right bucket."""
        ms = elapsed_seconds * 1000.0
        if ms < 0.05:
            return cls.LT_50US
        if ms < 0.5:
            return cls.LT_500US
        if ms < 5:
            return cls.LT_5MS
        return cls.GE_5MS


def latency_bucket(elapsed_seconds: float) -> LatencyBucket:
    """Backwards-compatible helper — equivalent to :meth:`LatencyBucket.for_seconds`."""
    return LatencyBucket.for_seconds(elapsed_seconds)


@dataclass(frozen=True)
class AnalyticsRequest:
    """Templated request shape — e.g. ``{ "method": "POST", "path": "/v1/payments/:id" }``."""

    method: str
    path: str


@dataclass(frozen=True)
class MatchedAction:
    """One per matched rule. Denormalised so the console can render
    badges without joining to the rules table at render time.
    """

    rule_id: str
    action_type: str


@dataclass(frozen=True)
class AnalyticsEvent:
    """One event per :meth:`Client.evaluate` call.

    Backend enriches each event with ``id``, ``orgId``, ``serviceId``,
    ``envId``, and ``instanceId`` (the last from the ``X-Instance-Key``
    header) before persisting; the wrapper sends the fields below
    verbatim. Mirrors ``klswitch-console/src/lib/zod/analytics.ts``.

    Never includes raw facts — only the templated request shape +
    matched-rule outcomes.
    """

    occurred_at: str               # ISO 8601 UTC
    rules_version: int
    request: AnalyticsRequest
    matched: list[MatchedAction]
    evaluated: int
    skipped: int
    latency_bucket: LatencyBucket
    #: Schema-drift Layer 3: deduplicated, sorted list of field paths that
    #: matched rules referenced but the request input didn't carry. Always
    #: present on the wire (empty list when none) so console queries don't
    #: have to special-case null. Defaulted so pre-B3 call sites keep
    #: compiling without churn.
    missing_fields: list[str] = field(default_factory=list)
    #: Client-generated UUID v4 idempotency key, minted once at event birth
    #: and stable for its lifetime. The backend enforces ``UNIQUE(eventId)``
    #: so a redelivered batch collapses to a single stored decision. Defaulted
    #: via a factory so every constructed event carries one automatically.
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    #: Polymorphic discriminator for the analytics ingest (``POST /v1/analytics``).
    #: Only ``"evaluation"`` exists today (future: poll-failure / health events).
    event_type: str = ANALYTICS_EVENT_TYPE

    def to_wire_dict(self) -> dict:
        """Serialise to the JSON shape the backend expects (camelCase keys)."""
        return {
            "type":          self.event_type,
            "eventId":       self.event_id,
            "occurredAt":    self.occurred_at,
            "rulesVersion":  self.rules_version,
            "request":       {"method": self.request.method, "path": self.request.path},
            "matched":       [
                {"ruleId": m.rule_id, "actionType": m.action_type}
                for m in self.matched
            ],
            "evaluated":     self.evaluated,
            "skipped":       self.skipped,
            "latencyBucket": self.latency_bucket.value,
            "missingFields": list(self.missing_fields),
        }


# Sinks may be any callable accepting a list of events. Sync ``Client`` only
# accepts sync sinks (runtime check). Async ``AsyncClient`` accepts either a
# sync or an async-coroutine sink. Errors raised by the sink are logged and
# dropped; never propagated to the evaluator.
AnalyticsSink = Callable[[list[AnalyticsEvent]], "None | Awaitable[None]"]

# Alias kept for discoverability in async-only code paths.
AsyncAnalyticsSink = AnalyticsSink


@dataclass
class AnalyticsOptions:
    """Configuration for the analytics reporter.

    Pass a sync callable for use with :class:`Client`; sync or async
    (coroutine) for :class:`AsyncClient`. Async sinks passed to the sync
    Client are rejected with ``ValueError`` at construction time.

    When ``sink`` is ``None`` the reporter requires the HTTP transport
    (set by the SDK from ``token``) — the operator gets one or the other.
    """

    sink: AnalyticsSink | None = None
    batch_size: int = DEFAULT_BATCH_SIZE
    flush_interval: float = DEFAULT_FLUSH_INTERVAL_SECONDS
    max_queue_size: int = DEFAULT_MAX_QUEUE_SIZE
    post_timeout: float = DEFAULT_POST_TIMEOUT_SECONDS

    def _normalised(self) -> "AnalyticsOptions":
        return AnalyticsOptions(
            sink=self.sink,
            batch_size=self.batch_size if self.batch_size > 0 else DEFAULT_BATCH_SIZE,
            flush_interval=self.flush_interval
            if self.flush_interval > 0
            else DEFAULT_FLUSH_INTERVAL_SECONDS,
            max_queue_size=self.max_queue_size
            if self.max_queue_size > 0
            else DEFAULT_MAX_QUEUE_SIZE,
            post_timeout=self.post_timeout
            if self.post_timeout > 0
            else DEFAULT_POST_TIMEOUT_SECONDS,
        )


@dataclass
class AnalyticsTransport:
    """Where flushed batches go. Either ``sink`` (custom in-process consumer)
    OR ``url`` + headers (default HTTP POST). The reporter requires at
    least one to be present.
    """

    url: str | None = None
    headers: Mapping[str, str] = field(default_factory=dict)
    sdk_lang: str = "python"
    sdk_version: str = "0"
    instance_key: str = "i"
    sink: AnalyticsSink | None = None
    post_timeout: float = DEFAULT_POST_TIMEOUT_SECONDS


@dataclass(frozen=True)
class AnalyticsStatus:
    """Snapshot of the reporter's health — surfaced via
    :meth:`Client.analytics_status` / :meth:`AsyncClient.analytics_status`
    so the console can render "instance X dropped N events" badges
    without further coordination.
    """

    enabled: bool
    buffer_size: int
    buffer_capacity: int
    events_recorded: int
    events_dropped: int
    events_sent: int
    batches_failed: int
    last_flush_at: str | None
    last_flush_ok: bool | None
    last_flush_error: str | None
    consecutive_failures: int


def disabled_analytics_status() -> AnalyticsStatus:
    return AnalyticsStatus(
        enabled=False,
        buffer_size=0,
        buffer_capacity=0,
        events_recorded=0,
        events_dropped=0,
        events_sent=0,
        batches_failed=0,
        last_flush_at=None,
        last_flush_ok=None,
        last_flush_error=None,
        consecutive_failures=0,
    )


def _post_json_gzipped(
    url: str,
    headers: Mapping[str, str],
    body: bytes,
    timeout: float,
) -> int:
    """POST a gzipped JSON body and return the response status code.

    Raises :class:`urllib.error.URLError` / :class:`OSError` on transport
    failure (network down, timeout). Non-2xx is returned as an int — the
    caller decides how to react.
    """
    merged: dict[str, str] = dict(headers)
    merged.setdefault("Content-Type", "application/json")
    merged["Content-Encoding"] = "gzip"
    req = urllib.request.Request(url, data=body, headers=merged, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        # HTTPError is raised for non-2xx — return the code so we can decide.
        return e.code


# ───────────────────────────── sync reporter ─────────────────────────────────


class AnalyticsReporter:
    """Background-thread analytics flush worker (sync :class:`~klswitch.Client`)."""

    def __init__(
        self,
        opts: AnalyticsOptions,
        transport: AnalyticsTransport,
        logger: Logger | None = None,
    ) -> None:
        self._opts = opts._normalised()
        self._transport = transport
        if self._transport.sink is None and not self._transport.url:
            raise ValueError("AnalyticsReporter requires a sink or a url")
        self._logger: Logger = logger if logger is not None else NullLogger()

        # deque(maxlen=N) provides the drop-oldest policy natively. `append`
        # is O(1) and atomic in CPython; we still take a lock for the
        # paired counter updates.
        self._queue: deque[AnalyticsEvent] = deque(maxlen=self._opts.max_queue_size)
        self._lock = threading.Lock()
        self._flushing = False
        self._stop_event = threading.Event()
        self._flush_signal = threading.Event()
        self._stopped = False

        # Counters (mutated under self._lock).
        self._events_recorded = 0
        self._events_dropped = 0
        self._events_sent = 0
        self._batches_failed = 0
        self._last_flush_at: str | None = None
        self._last_flush_ok: bool | None = None
        self._last_flush_error: str | None = None
        self._consecutive_failures = 0

        self._thread = threading.Thread(
            target=self._run, daemon=True, name="klswitch-analytics",
        )
        self._thread.start()

    # ───── public ────────────────────────────────────────────────────────────

    def record(self, event: AnalyticsEvent) -> None:
        """Enqueue an event. Synchronous and non-blocking; never raises.

        On overflow (queue at capacity), the deque drops the oldest item
        automatically and the ``events_dropped`` counter is incremented.
        """
        with self._lock:
            if self._stopped:
                return
            self._events_recorded += 1
            if len(self._queue) == self._opts.max_queue_size:
                # The append below will silently evict the oldest (deque
                # maxlen semantics); record the drop explicitly.
                self._events_dropped += 1
            self._queue.append(event)
            should_flush = len(self._queue) >= self._opts.batch_size
        if should_flush:
            self._flush_signal.set()

    def flush_now(self) -> None:
        """Force a synchronous flush. Mostly for tests + graceful close."""
        self._flush()

    def close(self) -> None:
        """Stop the worker, wait for any in-flight flush, drain remainder. Idempotent."""
        with self._lock:
            if self._stopped:
                return
            self._stopped = True
        self._stop_event.set()
        self._flush_signal.set()
        self._thread.join(timeout=2.0)
        # Final best-effort drain on the calling thread — bypass the
        # _stopped flag so the drain still runs.
        with self._lock:
            self._stopped = False
        try:
            self._flush()
        finally:
            with self._lock:
                self._stopped = True

    def status(self) -> AnalyticsStatus:
        with self._lock:
            return AnalyticsStatus(
                enabled=True,
                buffer_size=len(self._queue),
                buffer_capacity=self._opts.max_queue_size,
                events_recorded=self._events_recorded,
                events_dropped=self._events_dropped,
                events_sent=self._events_sent,
                batches_failed=self._batches_failed,
                last_flush_at=self._last_flush_at,
                last_flush_ok=self._last_flush_ok,
                last_flush_error=self._last_flush_error,
                consecutive_failures=self._consecutive_failures,
            )

    # ───── internals ────────────────────────────────────────────────────────

    def _run(self) -> None:
        while not self._stop_event.is_set():
            self._flush_signal.wait(timeout=self._opts.flush_interval)
            self._flush_signal.clear()
            if self._stop_event.is_set():
                return
            self._flush()

    def _flush(self) -> None:
        """Drain ONE batch and ship it. Single-flight; never raises."""
        with self._lock:
            if self._flushing:
                return
            if not self._queue:
                return
            self._flushing = True
            take = min(self._opts.batch_size, len(self._queue))
            batch = [self._queue.popleft() for _ in range(take)]
        try:
            ok = self._ship(batch)
            now = _utc_now_iso()
            with self._lock:
                self._last_flush_at = now
                self._last_flush_ok = ok
                if ok:
                    self._events_sent += len(batch)
                    self._consecutive_failures = 0
                    self._last_flush_error = None
                else:
                    self._batches_failed += 1
                    self._consecutive_failures += 1
        finally:
            with self._lock:
                self._flushing = False

    def _ship(self, batch: list[AnalyticsEvent]) -> bool:
        """Returns True on 2xx; False on any failure. Records lastFlushError."""
        if self._transport.sink is not None:
            try:
                self._transport.sink(batch)
                return True
            except Exception as e:  # noqa: BLE001 - sink contract
                with self._lock:
                    self._last_flush_error = str(e)
                self._logger.warning(
                    "analytics sink threw; dropping batch",
                    {"error": str(e), "dropped": len(batch)},
                )
                return False
        if not self._transport.url:
            with self._lock:
                self._last_flush_error = "no analytics transport configured"
            return False
        return self._ship_http(batch)

    def _ship_http(self, batch: list[AnalyticsEvent]) -> bool:
        url = cast(str, self._transport.url)
        envelope = {
            "sdkLang":     self._transport.sdk_lang,
            "sdkVersion":  self._transport.sdk_version,
            "instanceKey": self._transport.instance_key,
            "events":      [e.to_wire_dict() for e in batch],
        }
        try:
            raw = json.dumps(envelope, ensure_ascii=False).encode("utf-8")
            body = _gzip.compress(raw)
        except Exception as e:  # noqa: BLE001
            with self._lock:
                self._last_flush_error = f"serialise failed: {e}"
            self._logger.warning("analytics serialise failed", {"error": str(e)})
            return False
        try:
            status = _post_json_gzipped(
                url, self._transport.headers, body, self._transport.post_timeout,
            )
        except urllib.error.URLError as e:
            with self._lock:
                self._last_flush_error = str(e.reason)
                failures = self._consecutive_failures + 1
            self._logger.warning(
                "analytics POST failed; dropping batch",
                {"url": url, "error": str(e.reason),
                 "dropped": len(batch), "consecutive_failures": failures},
            )
            return False
        except OSError as e:
            with self._lock:
                self._last_flush_error = str(e)
            self._logger.warning(
                "analytics POST failed; dropping batch",
                {"url": url, "error": str(e), "dropped": len(batch)},
            )
            return False
        if status >= 400:
            with self._lock:
                self._last_flush_error = f"analytics POST returned status {status}"
            self._logger.warning(
                "analytics POST rejected",
                {"url": url, "status": status, "dropped": len(batch)},
            )
            return False
        return True


# ───────────────────────────── async reporter ────────────────────────────────


class AsyncAnalyticsReporter:
    """asyncio counterpart to :class:`AnalyticsReporter`.

    The HTTP path uses :func:`asyncio.to_thread` to dispatch the
    (blocking) ``urllib`` POST off the event loop. Custom async sinks
    are awaited directly.
    """

    def __init__(
        self,
        opts: AnalyticsOptions,
        transport: AnalyticsTransport,
        logger: Logger | None = None,
    ) -> None:
        self._opts = opts._normalised()
        self._transport = transport
        if self._transport.sink is None and not self._transport.url:
            raise ValueError("AsyncAnalyticsReporter requires a sink or a url")
        self._logger: Logger = logger if logger is not None else NullLogger()
        self._queue: deque[AnalyticsEvent] = deque(maxlen=self._opts.max_queue_size)
        self._flushing = False
        self._stop_event = asyncio.Event()
        self._flush_signal = asyncio.Event()
        self._sink_is_async = (
            self._transport.sink is not None
            and inspect.iscoroutinefunction(self._transport.sink)
        )
        self._task: asyncio.Task[None] | None = None

        # Counters.
        self._events_recorded = 0
        self._events_dropped = 0
        self._events_sent = 0
        self._batches_failed = 0
        self._last_flush_at: str | None = None
        self._last_flush_ok: bool | None = None
        self._last_flush_error: str | None = None
        self._consecutive_failures = 0

    def start(self) -> None:
        """Start the background flush task. Idempotent."""
        if self._task is not None:
            return
        self._task = asyncio.create_task(self._run(), name="klswitch-async-analytics")

    def record(self, event: AnalyticsEvent) -> None:
        """Enqueue an event. Sync because asyncio is cooperative — no race."""
        self._events_recorded += 1
        if len(self._queue) == self._opts.max_queue_size:
            self._events_dropped += 1
        self._queue.append(event)  # deque auto-evicts oldest
        if len(self._queue) >= self._opts.batch_size:
            self._flush_signal.set()

    async def close(self) -> None:
        """Cancel the worker, drain remainder. Idempotent."""
        self._stop_event.set()
        self._flush_signal.set()
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, timeout=2.0)
            except asyncio.TimeoutError:
                self._task.cancel()
                try:
                    await self._task
                except asyncio.CancelledError:
                    pass
            self._task = None
        await self._flush()  # best-effort final drain

    def status(self) -> AnalyticsStatus:
        return AnalyticsStatus(
            enabled=True,
            buffer_size=len(self._queue),
            buffer_capacity=self._opts.max_queue_size,
            events_recorded=self._events_recorded,
            events_dropped=self._events_dropped,
            events_sent=self._events_sent,
            batches_failed=self._batches_failed,
            last_flush_at=self._last_flush_at,
            last_flush_ok=self._last_flush_ok,
            last_flush_error=self._last_flush_error,
            consecutive_failures=self._consecutive_failures,
        )

    # ───── internals ────────────────────────────────────────────────────────

    async def _run(self) -> None:
        while not self._stop_event.is_set():
            try:
                await asyncio.wait_for(
                    self._flush_signal.wait(), timeout=self._opts.flush_interval,
                )
            except asyncio.TimeoutError:
                pass
            except asyncio.CancelledError:
                return
            self._flush_signal.clear()
            if self._stop_event.is_set():
                return
            await self._flush()

    async def _flush(self) -> None:
        if self._flushing or not self._queue:
            return
        self._flushing = True
        take = min(self._opts.batch_size, len(self._queue))
        batch = [self._queue.popleft() for _ in range(take)]
        try:
            ok = await self._ship(batch)
            self._last_flush_at = _utc_now_iso()
            self._last_flush_ok = ok
            if ok:
                self._events_sent += len(batch)
                self._consecutive_failures = 0
                self._last_flush_error = None
            else:
                self._batches_failed += 1
                self._consecutive_failures += 1
        finally:
            self._flushing = False

    async def _ship(self, batch: list[AnalyticsEvent]) -> bool:
        if self._transport.sink is not None:
            try:
                if self._sink_is_async:
                    await cast("Awaitable[None]", self._transport.sink(batch))
                else:
                    self._transport.sink(batch)
                return True
            except Exception as e:  # noqa: BLE001
                self._last_flush_error = str(e)
                self._logger.warning(
                    "analytics sink threw; dropping batch",
                    {"error": str(e), "dropped": len(batch)},
                )
                return False
        if not self._transport.url:
            self._last_flush_error = "no analytics transport configured"
            return False
        url = cast(str, self._transport.url)
        envelope = {
            "sdkLang":     self._transport.sdk_lang,
            "sdkVersion":  self._transport.sdk_version,
            "instanceKey": self._transport.instance_key,
            "events":      [e.to_wire_dict() for e in batch],
        }
        try:
            raw = json.dumps(envelope, ensure_ascii=False).encode("utf-8")
            body = _gzip.compress(raw)
        except Exception as e:  # noqa: BLE001
            self._last_flush_error = f"serialise failed: {e}"
            return False
        try:
            status = await asyncio.to_thread(
                _post_json_gzipped, url, self._transport.headers,
                body, self._transport.post_timeout,
            )
        except urllib.error.URLError as e:
            self._last_flush_error = str(e.reason)
            self._logger.warning(
                "analytics POST failed; dropping batch",
                {"url": url, "error": str(e.reason), "dropped": len(batch)},
            )
            return False
        except OSError as e:
            self._last_flush_error = str(e)
            self._logger.warning(
                "analytics POST failed; dropping batch",
                {"url": url, "error": str(e), "dropped": len(batch)},
            )
            return False
        if status >= 400:
            self._last_flush_error = f"analytics POST returned status {status}"
            self._logger.warning(
                "analytics POST rejected",
                {"url": url, "status": status, "dropped": len(batch)},
            )
            return False
        return True


def _utc_now_iso() -> str:
    """Current UTC time as ISO 8601 with the ``Z`` suffix."""
    import datetime as _dt
    return _dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


__all__ = [
    "ANALYTICS_EVENT_TYPE",
    "DEFAULT_BATCH_SIZE",
    "DEFAULT_FLUSH_INTERVAL_SECONDS",
    "DEFAULT_MAX_QUEUE_SIZE",
    "DEFAULT_POST_TIMEOUT_SECONDS",
    "AnalyticsEvent",
    "AnalyticsOptions",
    "AnalyticsReporter",
    "AnalyticsRequest",
    "AnalyticsSink",
    "AnalyticsStatus",
    "AnalyticsTransport",
    "AsyncAnalyticsReporter",
    "AsyncAnalyticsSink",
    "LatencyBucket",
    "MatchedAction",
    "disabled_analytics_status",
    "latency_bucket",
]
