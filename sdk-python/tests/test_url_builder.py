"""Unit tests for URL derivation."""

from __future__ import annotations

from klswitch import DEFAULT_BASE_URL, resolve_urls


def test_defaults_when_nothing_supplied() -> None:
    u = resolve_urls(base_url=None, rules_url=None, decisions_url=None)
    assert u.rules == f"{DEFAULT_BASE_URL}/v1/ruleset"
    assert u.decisions == f"{DEFAULT_BASE_URL}/v1/analytics"


def test_base_url_flows_to_every_endpoint() -> None:
    u = resolve_urls(
        base_url="http://self-hosted:8080",
        rules_url=None, decisions_url=None,
    )
    assert u.rules == "http://self-hosted:8080/v1/ruleset"
    assert u.decisions == "http://self-hosted:8080/v1/analytics"


def test_trailing_slash_normalized() -> None:
    u = resolve_urls(base_url="http://api/", rules_url=None, decisions_url=None)
    assert u.rules == "http://api/v1/ruleset"


def test_per_endpoint_overrides_win() -> None:
    u = resolve_urls(
        base_url="http://ignored",
        rules_url="http://custom/rules",
        decisions_url="http://custom/dec",
    )
    assert u.rules == "http://custom/rules"
    assert u.decisions == "http://custom/dec"


def test_mix_base_and_one_override() -> None:
    u = resolve_urls(
        base_url="http://api.example.com",
        rules_url="http://separate-cdn/rules",
        decisions_url=None,
    )
    assert u.rules == "http://separate-cdn/rules"
    assert u.decisions == "http://api.example.com/v1/analytics"
