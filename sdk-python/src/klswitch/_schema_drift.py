"""Schema-drift detection — Layer 3 (runtime).

After every evaluation, we walk each *matched* rule's condition tree and
tag the analytics event with the set of fields the rule referenced that
weren't present in the request input. Operators see these in the console
as "rule X referenced ``body.coupon_code``, but ``coupon_code`` isn't in
this route's body" — early warning that a rule was authored against a
stale request shape.

Pure wrapper-side; the engine doesn't know about this. See
``docs/schema-drift-detection.md`` for the four-layer detection plan.

The walker / presence checker is intentionally tiny — Python's
``Rule.condition`` is a raw ``dict[str, Any]``, so we walk that
directly rather than re-introducing a typed Condition model.
"""

from __future__ import annotations

from typing import Any, Iterable, Iterator

from .types import EvaluationInput, MatchedRule, Rule


def rules_by_id(rules: Iterable[Rule]) -> dict[str, Rule]:
    """Build a lookup keyed by rule ID. Cheap; built once per
    successful ruleset swap, not once per evaluate.
    """
    return {r.id: r for r in rules}


def compute_missing_fields(
    matched: list[MatchedRule],
    rules_by_id: dict[str, Rule],
    input_obj: EvaluationInput,
) -> list[str]:
    """Walk each matched rule's condition tree against the request
    input and return a deduplicated, sorted list of field paths the
    rule referenced but the input didn't carry. Empty when no matched
    rule referenced a missing field (common case — keeps the wire
    format slim).
    """
    if not matched or not rules_by_id:
        return []
    missing: set[str] = set()
    for m in matched:
        rule = rules_by_id.get(m.id)
        if rule is None:
            continue
        for leaf in _walk_leaves(rule.condition):
            field = leaf.get("field")
            if not isinstance(field, str) or not field:
                continue
            if not has_field(input_obj, field):
                missing.add(field)
    return sorted(missing)


def _walk_leaves(node: Any) -> Iterator[dict[str, Any]]:
    """Recursively yield every leaf condition. Leaves are dicts with a
    ``field`` key; combinators (``all`` / ``any`` / ``not``) are
    mutually exclusive with leaves per the schema.
    """
    if not isinstance(node, dict):
        return
    if "all" in node and isinstance(node["all"], list):
        for child in node["all"]:
            yield from _walk_leaves(child)
        return
    if "any" in node and isinstance(node["any"], list):
        for child in node["any"]:
            yield from _walk_leaves(child)
        return
    if "not" in node:
        yield from _walk_leaves(node["not"])
        return
    if "field" in node:
        yield node


def has_field(input_obj: EvaluationInput, path: str) -> bool:
    """Returns True iff the dotted ``path`` resolves to a defined non-
    ``None`` value within the request input. ``body.amount`` walks
    ``input.facts.body["amount"]``; ``headers.x-api-key`` walks
    ``input.facts.headers["x-api-key"]``.

    Treats ``None`` as "absent" (matching the engine's missing-field
    semantics). Empty string and zero are PRESENT — they're real
    values an operator might have explicitly sent.
    """
    if not path:
        return False
    segments = path.split(".")
    if not segments:
        return False
    bucket = _facts_bucket(input_obj, segments[0])
    if bucket is None:
        return False
    cursor: Any = bucket
    for i in range(1, len(segments)):
        if not isinstance(cursor, dict):
            return False
        cursor = cursor.get(segments[i])
        if cursor is None:
            return False
    return cursor is not None


def _facts_bucket(input_obj: EvaluationInput, name: str) -> dict[str, Any] | None:
    """Return the named fact bucket as a dict, or ``None`` when the
    bucket isn't one of the five known names or is itself unset.
    Distinguishes ``None`` (bucket missing) from ``{}`` (bucket empty —
    still a valid bucket; the absence is in the lookup of the next
    segment).
    """
    facts = input_obj.facts
    if name == "headers":
        return facts.headers
    if name == "query":
        return facts.query
    if name == "params":
        return facts.params
    if name == "body":
        return facts.body
    if name == "meta":
        return facts.meta
    return None
