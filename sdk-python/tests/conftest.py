"""Shared pytest fixtures and constants."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, cast

import pytest

from klswitch import (
    Action,
    Facts,
    Rule,
    RuleSetPayload,
    Selector,
)
from klswitch._bridge import Bridge
from klswitch._resources import bundled_wasm

# Same payload as sdk-go's bridge_test.go so cross-language parity is obvious.
RULESET_JSON = """{"schema_version":"1.0","rules_version":12,"rules":[
    {"id":"block","name":"Block","selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
     "enabled":true,"priority":100,
     "condition":{"all":[{"field":"body.amount","operator":"gt","value":100}]},
     "action":{"type":"block","payload":{"code":"RULE_BLOCKED"}}}]}"""

INPUT_MATCH_JSON = """{"selector":{"kind":"api","keys":{"method":"POST","path":"/p"}},
    "facts":{"headers":{},"query":{},"params":{},"body":{"amount":500},"meta":{}}}"""


def make_ruleset() -> RuleSetPayload:
    """Construct the typed equivalent of RULESET_JSON via the SDK types."""
    return RuleSetPayload(
        schema_version="1.0",
        rules_version=12,
        rules=[
            Rule(
                id="block",
                name="Block",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 100}]},
                action=Action(type="block", payload={"code": "RULE_BLOCKED"}),
            ),
        ],
    )


def make_input_match() -> Facts:
    """Facts that satisfy block_large_payment."""
    return Facts(body={"amount": 500})


@pytest.fixture()
def bridge() -> Bridge:
    """Fresh bridge per test (lightweight; ~80 ms to instantiate)."""
    return Bridge(bundled_wasm())


@pytest.fixture(scope="session")
def compliance_cases_dir() -> Path:
    """Resolve the shared compliance suite (lives outside the package)."""
    d = Path(__file__).resolve().parent.parent.parent / "spec" / "compliance" / "v1" / "cases"
    if not d.is_dir():
        raise RuntimeError(f"compliance cases dir not found at {d}")
    return d


@pytest.fixture(scope="session")
def compliance_case_paths(compliance_cases_dir: Path) -> list[Path]:
    return sorted(compliance_cases_dir.glob("*.json"))


def load_fixture(path: Path) -> dict[str, Any]:
    return cast("dict[str, Any]", json.loads(path.read_text()))


# ── Shared "demo" rules used by the framework-adapter tests ──────────────────
# Same three rules the Node/Java/Go example servers ship so the adapter tests
# prove behavioural parity across languages (same status codes, same matched
# rule ids for the same curl).


def make_demo_rules() -> RuleSetPayload:
    from klswitch import Action, Rule

    return RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="block_large_payment",
                name="Block large payment",
                selector=Selector(
                    kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}
                ),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 50000}]},
                action=Action(
                    type="block",
                    payload={"statusCode": 429, "code": "PAYMENT_TOO_LARGE"},
                ),
            ),
            Rule(
                id="block_restricted_region",
                name="Block restricted region",
                selector=Selector(
                    kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}
                ),
                enabled=True,
                priority=110,
                condition={
                    "all": [
                        {"field": "body.country", "operator": "in", "value": ["KP", "IR"]}
                    ]
                },
                action=Action(
                    type="block",
                    payload={"statusCode": 451, "code": "RESTRICTED_REGION"},
                ),
            ),
            Rule(
                id="flag_admin_access",
                name="Flag admin access",
                selector=Selector(
                    kind="api", keys={"method": "GET", "path": "/v1/users/:id"}
                ),
                enabled=True,
                priority=50,
                condition={
                    "any": [
                        {"field": "query.role", "operator": "eq", "value": "admin"},
                        {"field": "headers.x-impersonate", "operator": "exists"},
                    ]
                },
                action=Action(type="flag", payload={"code": "ADMIN_ACCESS"}),
            ),
        ],
    )
