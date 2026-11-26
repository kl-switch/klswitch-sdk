"""
The regression: a fetched ruleset is decoded into the typed ``Rule`` and re-encoded before it
reaches the engine, and ``Rule`` had no ``effective`` field — so the window was silently dropped
in transit. Scheduled and expiring rules simply did not work: an expired rule kept firing and a
future rule fired early, with no error anywhere.

The 50 compliance fixtures cover effective windows and all passed throughout, because they build
rules through the typed constructor rather than decoding raw JSON. The fetch → decode → re-encode
path was never exercised. These tests cover exactly that path.
"""
import json

import pytest

from klswitch import Client, build_evaluation_input
from klswitch._json import decode_ruleset_payload, encode_ruleset

PAST_FROM, PAST_TO = 1_600_000_000_000, 1_700_000_000_000   # 2020 → 2023
FUTURE_FROM = 1_900_000_000_000                              # 2030


def _raw(effective=None):
    rule = {
        "id": "r",
        "name": "R",
        "enabled": True,
        "priority": 10,
        "selector": {"kind": "api", "keys": {"method": "POST", "path": "/x"}},
        "condition": {"field": "body.u", "operator": "exists"},
        "action": {"type": "block"},
    }
    if effective is not None:
        rule["effective"] = effective
    return json.dumps({"schema_version": "1.0", "rules_version": 1, "rules": [rule]})


def test_effective_window_survives_round_trip():
    window = {"from": PAST_FROM, "to": PAST_TO}
    out = json.loads(encode_ruleset(decode_ruleset_payload(_raw(window))))
    assert out["rules"][0].get("effective") == window, \
        "the window must reach the engine; dropping it disables scheduling silently"


def _decide(effective):
    client = Client(rules=decode_ruleset_payload(_raw(effective)))
    result = client.evaluate(build_evaluation_input(method="POST", path="/x", body={"u": 1}))
    return [m.id for m in result.matched], result.skipped


def test_expired_rule_does_not_fire():
    matched, skipped = _decide({"from": PAST_FROM, "to": PAST_TO})
    assert matched == [] and skipped == 1


def test_future_rule_does_not_fire_yet():
    matched, skipped = _decide({"from": FUTURE_FROM})
    assert matched == [] and skipped == 1


@pytest.mark.parametrize("effective", [None, {"from": PAST_FROM}])
def test_active_rule_still_fires(effective):
    matched, _ = _decide(effective)
    assert matched == ["r"], "a rule with no window, or an open one, must still match"
