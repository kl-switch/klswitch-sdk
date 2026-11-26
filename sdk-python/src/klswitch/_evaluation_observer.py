"""Observers notified after each evaluation, so that ``evaluate()`` can evaluate.

Before this existed, ``evaluate()`` also re-checked the route manifest, timed
itself, walked condition trees for missing fields and assembled an analytics
event — four reasons for one method to change, three of them telemetry riding on
the hot path. They are observers now; the hot path notifies and returns.

The same objects serve the sync and async clients. Nothing here awaits: the work
is synchronous in both, and an observer that blocked would block an evaluation.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from ._analytics import (
    AnalyticsEvent,
    AnalyticsRequest,
    MatchedAction,
    latency_bucket,
)
from ._collaborators import AnalyticsRecorder, ManifestControl
from ._schema_drift import compute_missing_fields
from .types import EvaluationInput, EvaluationResult


@dataclass(frozen=True)
class EvaluationContext:
    """Everything an observer could want, gathered once by the caller.

    ``rules_by_id`` is passed by reference — observers must not mutate it.
    """

    evaluation_input: EvaluationInput
    result: EvaluationResult
    elapsed_seconds: float
    rules_version: int | None
    rules_by_id: dict


class EvaluationObserver(Protocol):
    """Notified after every evaluation.

    Implementations must not raise and must not block: they run inline, on the
    caller's thread, between the engine returning and the caller getting its
    result.
    """

    def on_evaluated(self, ctx: EvaluationContext) -> None: ...


class NoopEvaluationObserver:
    """Does nothing. The default, so the hot path never branches on ``None``."""

    def on_evaluated(self, ctx: EvaluationContext) -> None:
        return None


class AnalyticsEvaluationObserver:
    """Builds a decision event and hands it to the reporter.

    Owns the rule about *which* evaluations are reportable: only a successful one
    against a known ruleset version. An ``ok=False`` result is a caller or
    lifecycle error (INVALID_INPUT / INVALID_HANDLE — the WASM boundary returns
    these rather than raising), not a decision, and reporting it would put
    non-decisions in the decision stream. That judgement is telemetry's, which is
    why it lives here and no longer in ``evaluate()``.
    """

    def __init__(self, reporter: AnalyticsRecorder) -> None:
        self._reporter = reporter

    def on_evaluated(self, ctx: EvaluationContext) -> None:
        if ctx.rules_version is None or not ctx.result.ok:
            return
        # Schema-drift Layer 3: walk each matched rule's condition tree against
        # the input. Computed here, inline, so a slow walk is paid by this caller
        # rather than the analytics flush thread.
        missing = compute_missing_fields(
            ctx.result.matched, ctx.rules_by_id, ctx.evaluation_input
        )
        self._reporter.record(
            AnalyticsEvent(
                occurred_at=_iso_now(),
                rules_version=ctx.rules_version,
                request=_extract_analytics_request(ctx.evaluation_input),
                matched=[
                    MatchedAction(rule_id=m.id, action_type=m.action.type)
                    for m in ctx.result.matched
                ],
                evaluated=ctx.result.evaluated,
                skipped=ctx.result.skipped,
                latency_bucket=latency_bucket(ctx.elapsed_seconds),
                missing_fields=missing,
            ),
        )


class ManifestRecheckObserver:
    """Pushes the route manifest once, on the first evaluation.

    This cannot move to construction, which is where it belongs on the face of
    it: routes are registered after the client exists, and with an async
    framework that registration finishes after the initial deferred push has
    already walked an empty route table. By the first request every route exists.

    So the trigger stays where it was; what changed is that ``evaluate()`` no
    longer knows about it. Cheap: ``report()`` hashes the route list and skips the
    network when nothing has changed.
    """

    def __init__(self, reporter: ManifestControl) -> None:
        self._reporter = reporter
        self._done = False

    def on_evaluated(self, ctx: EvaluationContext) -> None:
        if self._done:
            return
        self._done = True
        self._reporter.report()


class CompositeEvaluationObserver:
    """Fans out to several observers, in order."""

    def __init__(self, observers: list) -> None:
        self._observers = list(observers)

    def on_evaluated(self, ctx: EvaluationContext) -> None:
        for o in self._observers:
            o.on_evaluated(ctx)


# ─── Telemetry helpers ─────────────────────────────────────────────────────

def _extract_analytics_request(evaluation_input: EvaluationInput) -> AnalyticsRequest:
    """Pull the templated request shape out of the evaluation input's selector.

    Falls back to safe defaults for non-API selectors so the analytics event
    always satisfies the console schema.
    """
    keys = evaluation_input.selector.keys if evaluation_input.selector else {}
    method = str(keys.get("method", "UNKNOWN")).upper() if keys else "UNKNOWN"
    path = str(keys.get("path", "")) if keys else ""
    return AnalyticsRequest(method=method, path=path)

def _iso_now() -> str:
    """Current UTC time as ISO 8601 with the ``Z`` suffix."""
    import datetime as _dt
    return _dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
