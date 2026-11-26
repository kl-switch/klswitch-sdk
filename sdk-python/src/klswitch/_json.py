"""JSON serialisation for the engine boundary.

Encoders/decoders for every JSON shape that crosses the WASM bridge. Kept
separate from :mod:`klswitch.types` so the dataclass module has no JSON
dependency, and so users wanting to use the SDK's types in their own
serialisation pipelines can do so without dragging this module along.
"""

from __future__ import annotations

import json
import time
from typing import Any

from .types import (
    Action,
    EngineStatus,
    ErrorInfo,
    EvaluationInput,
    EvaluationResult,
    Facts,
    LoadRulesResult,
    MatchedRule,
    Rule,
    RuleSetPayload,
    Selector,
)

# ────── encoders (Python → engine JSON) ───────────────────────────────────────


def _action_to_dict(a: Action) -> dict[str, Any]:
    d: dict[str, Any] = {"type": a.type}
    if a.payload is not None:
        d["payload"] = a.payload
    return d


def _selector_to_dict(s: Selector) -> dict[str, Any]:
    return {"kind": s.kind, "keys": dict(s.keys)}


def _facts_to_dict(f: Facts) -> dict[str, Any]:
    """Defensive: every bucket becomes ``{}`` if ``None`` — the engine rejects ``null`` buckets."""
    return {
        "headers": f.headers if f.headers is not None else {},
        "query": f.query if f.query is not None else {},
        "params": f.params if f.params is not None else {},
        "body": f.body if f.body is not None else {},
        "meta": f.meta if f.meta is not None else {},
    }


def _rule_to_dict(r: Rule) -> dict[str, Any]:
    d: dict[str, Any] = {
        "id": r.id,
        "name": r.name,
        "selector": _selector_to_dict(r.selector),
        "enabled": r.enabled,
        "condition": r.condition,
        "action": _action_to_dict(r.action),
    }
    if r.priority is not None:
        d["priority"] = r.priority
    if r.tags is not None:
        d["tags"] = list(r.tags)
    if r.metadata is not None:
        d["metadata"] = dict(r.metadata)
    if r.effective is not None:
        d["effective"] = dict(r.effective)
    return d


def encode_ruleset(rs: RuleSetPayload) -> str:
    return json.dumps(
        {
            "schema_version": rs.schema_version,
            "rules_version": rs.rules_version,
            "rules": [_rule_to_dict(r) for r in rs.rules],
        }
    )


def encode_evaluation_input(inp: EvaluationInput) -> str:
    # Inject the trusted clock the engine lacks (for effective-window rules);
    # epoch millis, consistent with the other wrappers. Respect a caller value.
    now = inp.now if inp.now is not None else int(time.time() * 1000)
    return json.dumps(
        {
            "selector": _selector_to_dict(inp.selector),
            "facts": _facts_to_dict(inp.facts),
            "now": now,
        }
    )


# ────── decoders (engine JSON → Python) ───────────────────────────────────────


def _action_from_dict(d: dict[str, Any]) -> Action:
    return Action(type=d["type"], payload=d.get("payload"))


def _matched_rule_from_dict(d: dict[str, Any]) -> MatchedRule:
    return MatchedRule(
        id=d["id"],
        name=d["name"],
        priority=d["priority"],
        action=_action_from_dict(d["action"]),
    )


def _error_info_from_dict(d: dict[str, Any]) -> ErrorInfo:
    return ErrorInfo(
        code=d["code"],
        message=d["message"],
        rule_id=d.get("rule_id"),
        field=d.get("field"),
    )


def _selector_from_dict(d: dict[str, Any]) -> Selector:
    return Selector(kind=d["kind"], keys=dict(d["keys"]))


def _rule_from_dict(d: dict[str, Any]) -> Rule:
    return Rule(
        id=d["id"],
        name=d["name"],
        selector=_selector_from_dict(d["selector"]),
        enabled=d["enabled"],
        condition=d["condition"],
        action=_action_from_dict(d["action"]),
        priority=d.get("priority"),
        tags=d.get("tags"),
        metadata=d.get("metadata"),
        effective=d.get("effective"),
    )


def decode_ruleset_payload(s: str) -> RuleSetPayload:
    """Decode a ruleset JSON string (as returned by a rules-API endpoint)."""
    d = json.loads(s)
    return RuleSetPayload(
        schema_version=d["schema_version"],
        rules_version=d["rules_version"],
        rules=[_rule_from_dict(r) for r in d["rules"]],
    )


def decode_evaluation_result(s: str) -> EvaluationResult:
    d = json.loads(s)
    return EvaluationResult(
        ok=d["ok"],
        matched=[_matched_rule_from_dict(m) for m in d["matched"]],
        evaluated=d["evaluated"],
        skipped=d["skipped"],
        errors=[_error_info_from_dict(e) for e in d.get("errors", [])],
    )


def decode_load_rules_result(s: str) -> LoadRulesResult:
    d = json.loads(s)
    return LoadRulesResult(
        ok=d["ok"],
        rules_version=d["rules_version"],
        rule_count=d["rule_count"],
        compiled_rule_count=d["compiled_rule_count"],
        skipped_rule_count=d["skipped_rule_count"],
        errors=[_error_info_from_dict(e) for e in d.get("errors", [])],
    )


def decode_engine_status(s: str) -> EngineStatus:
    d = json.loads(s)
    last_error = _error_info_from_dict(d["last_error"]) if d.get("last_error") else None
    return EngineStatus(
        initialized=d["initialized"],
        schema_version=d["schema_version"],
        active_rules_version=d.get("active_rules_version"),
        rule_count=d["rule_count"],
        last_load_ok=d["last_load_ok"],
        last_error=last_error,
    )


__all__ = [
    "encode_ruleset",
    "encode_evaluation_input",
    "decode_ruleset_payload",
    "decode_evaluation_result",
    "decode_load_rules_result",
    "decode_engine_status",
]
