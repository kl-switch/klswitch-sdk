"""Replay the canonical compliance fixtures through the wasmtime bridge and
assert byte-identical output to the frozen expectations — the same JSON files
the Rust engine, Node, Java, and Go wrappers already match.

Two harnesses:
  * single-shot ``eval-*`` fixtures (one ruleset, one input, one expected result)
  * multi-step ``scenario`` fixtures (load_rules / evaluate / get_status against
    one engine instance)
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from klswitch._bridge import Bridge
from klswitch._resources import bundled_wasm

from .conftest import load_fixture


def _json_equal(a: str, b: str | dict[str, Any]) -> bool:
    """Structural JSON equality (key-order / whitespace / int-vs-float normalised)."""
    av = json.loads(a) if isinstance(a, str) else a
    bv = json.loads(b) if isinstance(b, str) else b
    return json.dumps(av, sort_keys=True) == json.dumps(bv, sort_keys=True)


def test_eval_fixtures_match_expected(compliance_case_paths: list[Path]) -> None:
    bridge = Bridge(bundled_wasm())
    checked = 0
    for path in compliance_case_paths:
        fx = load_fixture(path)
        if "fixture_kind" in fx or "expected_evaluation_result" not in fx:
            continue

        handle = bridge.create_engine()
        load_out = bridge.load_rules(handle, json.dumps(fx["ruleset"]))
        assert json.loads(load_out)["ok"] is True, f"{path.name}: ruleset should compile"
        eval_out = bridge.evaluate(handle, json.dumps(fx["evaluation_input"]))
        assert _json_equal(eval_out, fx["expected_evaluation_result"]), (
            f"{path.name}: mismatch\n  got: {eval_out}\n  want: {fx['expected_evaluation_result']}"
        )
        bridge.destroy_engine(handle)
        checked += 1

    # Exact count — catches silent skips and surfaces drift the moment a
    # fixture is added or removed. Canonical set in docs/cross-language-parity.md.
    assert checked == 41, f"expected 41 eval-* fixtures; checked {checked}"


def test_scenario_fixtures_replay_through_engine(
    compliance_case_paths: list[Path],
) -> None:
    scenarios, steps = 0, 0
    for path in compliance_case_paths:
        fx = load_fixture(path)
        if fx.get("fixture_kind") != "scenario":
            continue
        scenarios += 1
        # Fresh bridge per scenario so state never bleeds.
        bridge = Bridge(bundled_wasm())
        handle = bridge.create_engine()
        for i, step in enumerate(fx["steps"]):
            op = step["op"]
            label = f"{path.name} step {i} ({op})"
            if op == "load_rules":
                out = bridge.load_rules(handle, json.dumps(step["ruleset"]))
                assert _json_equal(out, step["expected_load_rules_result"]), (
                    f"{label} mismatch\n  got: {out}\n  want: {step['expected_load_rules_result']}"
                )
            elif op == "evaluate":
                out = bridge.evaluate(handle, json.dumps(step["evaluation_input"]))
                assert _json_equal(out, step["expected_evaluation_result"]), (
                    f"{label} mismatch\n  got: {out}\n  want: {step['expected_evaluation_result']}"
                )
            elif op == "get_status":
                out = bridge.get_status(handle)
                assert _json_equal(out, step["expected_engine_status"]), (
                    f"{label} mismatch\n  got: {out}\n  want: {step['expected_engine_status']}"
                )
            else:
                raise AssertionError(f"unknown op {op!r} in {path.name}")
            steps += 1
        bridge.destroy_engine(handle)

    # Exact counts — see the eval-fixture comment above for rationale.
    assert scenarios == 9, f"expected 9 scenario fixtures; found {scenarios}"
    assert steps == 18, f"expected 18 scenario steps; replayed {steps}"
