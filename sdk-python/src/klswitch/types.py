"""Public JSON-contract types, mirroring ``spec/schemas/``.

These are the shapes exchanged with the WASM engine; the wrapper does NOT
interpret them. ``Rule.condition`` is intentionally a free-form ``dict`` —
the boolean tree (``all`` / ``any`` / ``not`` / leaf) is awkward to model in
Python type system without losing flexibility, and users typically construct
it via dict literals or receive it parsed from JSON.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional


class Operator(str, Enum):
    """v1 leaf comparison operators."""

    EQ = "eq"
    NEQ = "neq"
    GT = "gt"
    GTE = "gte"
    LT = "lt"
    LTE = "lte"
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    IN = "in"
    NOT_IN = "not_in"
    EXISTS = "exists"
    NOT_EXISTS = "not_exists"


@dataclass(frozen=True)
class Selector:
    """v1 supports only ``kind="api"`` with method + path keys."""

    kind: str
    keys: dict[str, str]


@dataclass(frozen=True)
class Action:
    """Engine-opaque outcome returned to the host for matched rules."""

    type: str
    payload: dict[str, Any] | None = None


@dataclass(frozen=True)
class Rule:
    id: str
    name: str
    selector: Selector
    enabled: bool
    condition: dict[str, Any]
    action: Action
    priority: int | None = None
    tags: list[str] | None = None
    metadata: dict[str, Any] | None = None
    #: Effective window, ``{"from": epoch_ms, "to": epoch_ms}``; either bound may be absent.
    #:
    #: Kept as an opaque mapping rather than a typed pair because the engine owns the semantics
    #: and this wrapper only has to relay it faithfully. That is the whole point: a fetched
    #: ruleset is decoded into this class and re-encoded before reaching the engine, so a field
    #: missing here is silently dropped in transit. It was, and scheduled and expiring rules
    #: simply did not work in Python — an expired rule kept firing and a future rule fired early,
    #: with no error anywhere. See ``test_effective_window_survives_round_trip``.
    effective: dict[str, Any] | None = None


@dataclass(frozen=True)
class RuleSetPayload:
    """Full ruleset payload; ``rules_version`` may be int or str per schema."""

    schema_version: str
    rules_version: int | str
    rules: list[Rule]


@dataclass(frozen=True)
class Facts:
    """Five fact buckets for condition field paths.

    All five MUST be present (per ``evaluation-input.schema.json``); the JSON
    encoder substitutes empty maps for any ``None`` bucket so a zero-value
    ``Facts`` still produces engine-acceptable JSON. Mirrors the Go
    ``MarshalJSON`` defensive fill.

    Note: ``frozen`` freezes the *container*; the dict buckets themselves are
    still mutable by reference. Don't rely on the dicts staying constant
    between construction and the call to ``Client.evaluate``.
    """

    headers: dict[str, Any] | None = None
    query: dict[str, Any] | None = None
    params: dict[str, Any] | None = None
    body: dict[str, Any] | None = None
    meta: dict[str, Any] | None = None


@dataclass(frozen=True)
class EvaluationInput:
    selector: Selector
    facts: Facts
    # Trusted current time (epoch millis) to gate effective-window rules. None =>
    # encode fills it with the current time; set it only to pin time (e.g. tests).
    now: Optional[int] = None


@dataclass(frozen=True)
class MatchedRule:
    id: str
    name: str
    priority: int
    action: Action


@dataclass(frozen=True)
class ErrorInfo:
    code: str
    message: str
    rule_id: str | None = None
    field: str | None = None


@dataclass(frozen=True)
class EvaluationResult:
    ok: bool
    matched: list[MatchedRule]
    evaluated: int
    skipped: int
    errors: list[ErrorInfo] = field(default_factory=list)


@dataclass(frozen=True)
class LoadRulesResult:
    ok: bool
    rules_version: int
    rule_count: int
    compiled_rule_count: int
    skipped_rule_count: int
    errors: list[ErrorInfo] = field(default_factory=list)


@dataclass(frozen=True)
class EngineStatus:
    initialized: bool
    schema_version: str
    active_rules_version: int | None
    rule_count: int
    last_load_ok: bool
    last_error: ErrorInfo | None
