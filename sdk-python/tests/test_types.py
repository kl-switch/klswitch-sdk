"""Type-shape and JSON round-trip tests.

The most important property: a zero-value or partially-constructed
:class:`Facts` must JSON-encode with all five buckets as ``{}`` (not ``null``)
— mirrors the Go ``MarshalJSON`` fix.
"""

from __future__ import annotations

import json

from klswitch import (
    Action,
    Client,
    EvaluationInput,
    Facts,
    Operator,
    Rule,
    RuleSetPayload,
    Selector,
)
from klswitch import _json as kjson

from .conftest import make_ruleset


def test_facts_zero_value_encodes_with_empty_buckets() -> None:
    encoded = kjson.encode_evaluation_input(
        EvaluationInput(
            selector=Selector(kind="api", keys={"method": "GET", "path": "/x"}),
            facts=Facts(),
        ),
    )
    obj = json.loads(encoded)
    for bucket in ("headers", "query", "params", "body", "meta"):
        assert bucket in obj["facts"], f"missing bucket {bucket}"
        assert obj["facts"][bucket] == {}, f"bucket {bucket} should be empty dict, got {obj['facts'][bucket]}"
    assert "null" not in encoded


def test_facts_partial_construct_fills_missing_buckets() -> None:
    encoded = kjson.encode_evaluation_input(
        EvaluationInput(
            selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
            facts=Facts(body={"amount": 500}),
        ),
    )
    obj = json.loads(encoded)
    assert obj["facts"]["body"] == {"amount": 500}
    for bucket in ("headers", "query", "params", "meta"):
        assert obj["facts"][bucket] == {}


def test_partial_facts_actually_evaluates_through_engine() -> None:
    """End-to-end equivalent of sdk-go's TestFacts_PartialConstructEvaluatesThroughEngine."""
    with Client(rules=make_ruleset()) as ks:
        result = ks.evaluate(
            EvaluationInput(
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                facts=Facts(body={"amount": 500}),  # only one bucket set
            ),
        )
        assert result.ok
        assert len(result.matched) == 1
        assert result.matched[0].id == "block"


def test_ruleset_encode_omits_optional_fields_when_none() -> None:
    rs = RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="r",
                name="R",
                selector=Selector(kind="api", keys={"method": "GET", "path": "/x"}),
                enabled=True,
                condition={"field": "body.a", "operator": "exists"},
                action=Action(type="allow"),
            ),
        ],
    )
    obj = json.loads(kjson.encode_ruleset(rs))
    r0 = obj["rules"][0]
    assert "priority" not in r0
    assert "tags" not in r0
    assert "metadata" not in r0
    assert "payload" not in r0["action"]


def test_operator_enum_values_match_schema() -> None:
    assert Operator.EQ.value == "eq"
    assert Operator.NOT_CONTAINS.value == "not_contains"
    assert Operator.NOT_EXISTS.value == "not_exists"
