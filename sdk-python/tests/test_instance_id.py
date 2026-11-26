"""Unit tests for the instance-id resolver."""

from __future__ import annotations

import re

from klswitch import (
    InstanceIdSource,
    MAX_INSTANCE_ID_LEN,
    is_valid_instance_id,
    resolve_instance_id,
)


def test_is_valid_accepts_allowed_charset() -> None:
    assert is_valid_instance_id("pod-7f4a")
    assert is_valid_instance_id("payments-api-prod-0")
    assert is_valid_instance_id("arn:aws:ecs:us-east-1:123/cluster/task")
    assert is_valid_instance_id("a")
    assert is_valid_instance_id("A_B.C-D:E/F")


def test_is_valid_rejects_bad() -> None:
    assert not is_valid_instance_id(None)
    assert not is_valid_instance_id("")
    assert not is_valid_instance_id("   ")
    assert not is_valid_instance_id("has spaces")
    assert not is_valid_instance_id("with#special")
    assert not is_valid_instance_id("a" * (MAX_INSTANCE_ID_LEN + 1))


def test_resolve_explicit_config_wins() -> None:
    r = resolve_instance_id(
        "from-config",
        env={"KS_INSTANCE_ID": "from-env", "POD_NAME": "from-pod"},
    )
    assert r.value == "from-config"
    assert r.source == InstanceIdSource.CONFIG


def test_resolve_ks_env_beats_pod_name() -> None:
    r = resolve_instance_id(None, env={"KS_INSTANCE_ID": "from-env", "POD_NAME": "from-pod"})
    assert r.value == "from-env"
    assert r.source == InstanceIdSource.ENV


def test_resolve_pod_when_only_it_is_set() -> None:
    r = resolve_instance_id(None, env={"POD_NAME": "from-pod"})
    assert r.value == "from-pod"
    assert r.source == InstanceIdSource.POD


def test_resolve_derived_fallback_pattern() -> None:
    r = resolve_instance_id(None, env={})
    assert r.source == InstanceIdSource.DERIVED
    assert re.match(r"^[\w\-.:]+-\d+-\d+$", r.value)


def test_invalid_explicit_value_falls_through() -> None:
    r = resolve_instance_id("has spaces", env={"KS_INSTANCE_ID": "from-env"})
    assert r.value == "from-env"
    assert r.source == InstanceIdSource.ENV


def test_all_invalid_falls_to_derived() -> None:
    r = resolve_instance_id("bad!", env={"KS_INSTANCE_ID": "bad@", "POD_NAME": "bad#"})
    assert r.source == InstanceIdSource.DERIVED


def test_trims_surrounding_whitespace() -> None:
    r = resolve_instance_id("  pod-7f4a  ", env={})
    assert r.value == "pod-7f4a"
    assert r.source == InstanceIdSource.CONFIG
