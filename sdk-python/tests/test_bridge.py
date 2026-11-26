"""Exercise the WASM export ABI directly (no Client wrapper).

Mirrors sdk-go's bridge_test.go: full lifecycle, invalid handle, malformed
input, repeated calls — proving the wasmtime + memory protocol works.
"""

from __future__ import annotations

from klswitch._bridge import Bridge

from .conftest import INPUT_MATCH_JSON, RULESET_JSON


def test_bridge_full_lifecycle(bridge: Bridge) -> None:
    handle = bridge.create_engine()
    assert handle != 0, "handle is non-zero"

    # Fresh engine.
    status = bridge.get_status(handle)
    assert '"last_load_ok":false' in status
    assert '"active_rules_version":null' in status

    # Load.
    load = bridge.load_rules(handle, RULESET_JSON)
    assert '"ok":true' in load
    assert '"rules_version":12' in load

    # Status reflects the active ruleset.
    status = bridge.get_status(handle)
    assert '"active_rules_version":12' in status
    assert '"rule_count":1' in status
    assert '"last_load_ok":true' in status

    # Evaluate a matching request.
    result = bridge.evaluate(handle, INPUT_MATCH_JSON)
    assert '"id":"block"' in result

    bridge.destroy_engine(handle)
    status = bridge.get_status(handle)
    assert '"initialized":false' in status
    assert '"INVALID_HANDLE"' in status


def test_bridge_invalid_handle(bridge: Bridge) -> None:
    load = bridge.load_rules(999, RULESET_JSON)
    assert '"INVALID_HANDLE"' in load

    result = bridge.evaluate(999, INPUT_MATCH_JSON)
    assert '"INVALID_HANDLE"' in result


def test_bridge_malformed_input(bridge: Bridge) -> None:
    handle = bridge.create_engine()
    load = bridge.load_rules(handle, "{ not json")
    assert '"ok":false' in load

    result = bridge.evaluate(handle, "{}")
    assert '"INVALID_INPUT"' in result
    bridge.destroy_engine(handle)


def test_bridge_repeated_calls_are_stable(bridge: Bridge) -> None:
    handle = bridge.create_engine()
    for _ in range(500):
        load = bridge.load_rules(handle, RULESET_JSON)
        assert '"ok":true' in load
        result = bridge.evaluate(handle, INPUT_MATCH_JSON)
        assert '"id":"block"' in result
    bridge.destroy_engine(handle)
