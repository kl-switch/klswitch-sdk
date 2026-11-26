"""B3 — Schema-drift Layer 3 (missing fields) tests for the Python wrapper.

Mirrors sdk-node/test/schema-drift.test.ts.
"""

from __future__ import annotations

from typing import Any

import pytest

from klswitch import (
    Action,
    EvaluationInput,
    Facts,
    MatchedRule,
    Rule,
    Selector,
)
from klswitch._schema_drift import (
    compute_missing_fields,
    has_field,
    rules_by_id,
)


# ─── Fixtures ─────────────────────────────────────────────────────────────


def _rule(rid: str, condition: dict[str, Any]) -> Rule:
    return Rule(
        id=rid,
        name=rid,
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        enabled=True,
        condition=condition,
        action=Action(type="block"),
    )


def _input(
    body: dict[str, Any] | None = None,
    headers: dict[str, Any] | None = None,
    query: dict[str, Any] | None = None,
) -> EvaluationInput:
    return EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=Facts(
            headers=headers if headers is not None else {},
            query=query if query is not None else {},
            params={},
            body=body if body is not None else {},
            meta={},
        ),
    )


def _matched(*ids: str) -> list[MatchedRule]:
    return [
        MatchedRule(id=i, name=i, priority=0, action=Action(type="block"))
        for i in ids
    ]


# ─── has_field ────────────────────────────────────────────────────────────


def test_has_field_dotted_path() -> None:
    i = _input(body={"transaction": {"amount": 100}})
    assert has_field(i, "body.transaction.amount") is True
    assert has_field(i, "body.transaction.currency") is False
    assert has_field(i, "body.absent") is False


def test_has_field_missing_bucket_returns_false() -> None:
    i = EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=Facts(),  # all buckets None
    )
    assert has_field(i, "body.amount") is False


def test_has_field_zero_and_empty_string_are_present() -> None:
    i = _input(body={"coupon": "", "amount": 0})
    assert has_field(i, "body.coupon") is True
    assert has_field(i, "body.amount") is True


def test_has_field_none_is_absent() -> None:
    i = _input(body={"coupon": None})
    assert has_field(i, "body.coupon") is False


def test_has_field_dashed_header_key() -> None:
    i = _input(headers={"x-api-key": "abc"})
    assert has_field(i, "headers.x-api-key") is True
    assert has_field(i, "headers.x-missing") is False


def test_has_field_rejects_empty_path() -> None:
    assert has_field(_input(), "") is False


# ─── compute_missing_fields ───────────────────────────────────────────────


def test_no_matched_rules_returns_empty() -> None:
    rules = [_rule("r1", {"all": [{"field": "body.x", "operator": "gt", "value": 0}]})]
    assert compute_missing_fields([], rules_by_id(rules), _input()) == []


def test_matched_rule_with_missing_field_is_reported() -> None:
    r = _rule("r", {"all": [{"field": "body.amount", "operator": "gt", "value": 0}]})
    out = compute_missing_fields(_matched("r"), rules_by_id([r]), _input(body={}))
    assert out == ["body.amount"]


def test_matched_rule_with_field_present_returns_empty() -> None:
    r = _rule("r", {"all": [{"field": "body.amount", "operator": "gt", "value": 0}]})
    out = compute_missing_fields(_matched("r"), rules_by_id([r]), _input(body={"amount": 5}))
    assert out == []


def test_walks_nested_all_any_not() -> None:
    r = _rule(
        "nested",
        {
            "any": [
                {
                    "all": [
                        {"field": "body.amount", "operator": "gt", "value": 0},
                        {"field": "body.country", "operator": "in", "value": ["US"]},
                    ]
                },
                {"not": {"field": "headers.x-api-key", "operator": "exists"}},
            ]
        },
    )
    out = compute_missing_fields(
        _matched("nested"),
        rules_by_id([r]),
        _input(body={"amount": 5}, headers={}),
    )
    assert out == ["body.country", "headers.x-api-key"]


def test_dedup_across_matched_rules() -> None:
    r1 = _rule("r1", {"all": [{"field": "body.amount", "operator": "gt", "value": 0}]})
    r2 = _rule("r2", {"all": [{"field": "body.amount", "operator": "lt", "value": 10}]})
    out = compute_missing_fields(_matched("r1", "r2"), rules_by_id([r1, r2]), _input(body={}))
    assert out == ["body.amount"]


def test_results_sorted_alphabetically() -> None:
    r = _rule(
        "r",
        {
            "all": [
                {"field": "headers.x-zeta", "operator": "exists"},
                {"field": "body.alpha", "operator": "exists"},
                {"field": "query.middle", "operator": "exists"},
            ]
        },
    )
    out = compute_missing_fields(_matched("r"), rules_by_id([r]), _input())
    assert out == ["body.alpha", "headers.x-zeta", "query.middle"]


def test_every_leaf_operator_treated_the_same() -> None:
    r = _rule(
        "ops",
        {
            "any": [
                {"field": "body.a", "operator": "eq", "value": 1},
                {"field": "body.b", "operator": "neq", "value": 1},
                {"field": "body.c", "operator": "gt", "value": 0},
                {"field": "body.d", "operator": "lt", "value": 0},
                {"field": "body.e", "operator": "gte", "value": 0},
                {"field": "body.f", "operator": "lte", "value": 0},
                {"field": "body.g", "operator": "in", "value": [1]},
                {"field": "body.h", "operator": "not_in", "value": [1]},
                {"field": "body.i", "operator": "exists"},
                {"field": "body.j", "operator": "not_exists"},
            ]
        },
    )
    out = compute_missing_fields(_matched("ops"), rules_by_id([r]), _input(body={}))
    assert out == [
        "body.a", "body.b", "body.c", "body.d", "body.e",
        "body.f", "body.g", "body.h", "body.i", "body.j",
    ]


def test_matched_rule_not_in_index_is_ignored() -> None:
    out = compute_missing_fields(_matched("ghost"), {}, _input())
    assert out == []


# ─── Client integration ──────────────────────────────────────────────────


def test_client_evaluate_stamps_missing_fields() -> None:
    """End-to-end: missing-field detection rides on the analytics event."""
    from klswitch import AnalyticsOptions, Client, RuleSetPayload

    captured: list = []

    def _sink(events) -> None:
        captured.extend(events)

    rs = RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="audit_either",
                name="audit",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                enabled=True,
                priority=50,
                condition={
                    "any": [
                        {"field": "body.amount", "operator": "gt", "value": 0},
                        {"field": "body.country", "operator": "in", "value": ["KP"]},
                    ],
                },
                action=Action(type="flag"),
            ),
        ],
    )

    with Client(
        rules=rs,
        analytics=AnalyticsOptions(sink=_sink, batch_size=1, flush_interval=0.05),
    ) as ks:
        ks.evaluate(_input(body={"amount": 5, "country": "US"}))  # both fields present
        ks.evaluate(_input(body={"amount": 5}))                    # country MISSING
        import time as _t
        _t.sleep(0.15)

    assert len(captured) == 2
    assert captured[0].missing_fields == []
    assert captured[1].missing_fields == ["body.country"]
