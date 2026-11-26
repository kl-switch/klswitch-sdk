"""Unit tests for the request-headers builder."""

from __future__ import annotations

from klswitch import build_headers, resolve_env_hint


def test_core_headers_always_set() -> None:
    h = build_headers(
        token="ks_live_test",
        instance_key="pod-7f4a",
        sdk_version="0.1.0",
        env={},
    )
    assert h["Authorization"] == "Bearer ks_live_test"
    assert h["X-Instance-Key"] == "pod-7f4a"
    assert h["User-Agent"] == "klswitch-python/0.1.0"
    assert h["Accept-Encoding"] == "gzip"


def test_extra_headers_merged_but_reserved_not_overridable() -> None:
    h = build_headers(
        token="ks_live_test",
        instance_key="i",
        sdk_version="0.1.0",
        extra_headers={
            "X-Custom": "value",
            "Authorization": "Bearer ATTACK",
            "x-instance-key": "ATTACK",
        },
        env={},
    )
    assert h["X-Custom"] == "value"
    assert h["Authorization"] == "Bearer ks_live_test"
    assert h["X-Instance-Key"] == "i"


def test_env_hint_ks_env() -> None:
    h = build_headers(token="t", instance_key="i", sdk_version="0", env={"KS_ENV": "staging"})
    assert h["X-Env-Hint"] == "staging"


def test_env_hint_pod_namespace() -> None:
    h = build_headers(token="t", instance_key="i", sdk_version="0", env={"POD_NAMESPACE": "prod"})
    assert h["X-Env-Hint"] == "prod"


def test_env_hint_omitted_when_unresolvable() -> None:
    h = build_headers(token="t", instance_key="i", sdk_version="0", env={})
    assert "X-Env-Hint" not in h


def test_explicit_env_hint_wins_over_env() -> None:
    h = build_headers(
        token="t", instance_key="i", sdk_version="0",
        env_hint="explicit", env={"KS_ENV": "from-env"},
    )
    assert h["X-Env-Hint"] == "explicit"


def test_resolve_env_hint_chain_order() -> None:
    assert resolve_env_hint({"KS_ENV": "a", "POD_NAMESPACE": "b", "K_SERVICE_REVISION": "c"}) == "a"
    assert resolve_env_hint({"POD_NAMESPACE": "b", "K_SERVICE_REVISION": "c"}) == "b"
    assert resolve_env_hint({"K_SERVICE_REVISION": "c"}) == "c"
    assert resolve_env_hint({}) is None


def test_resolve_env_hint_skips_empty() -> None:
    assert resolve_env_hint({"KS_ENV": "", "POD_NAMESPACE": "b"}) == "b"
    assert resolve_env_hint({"KS_ENV": "   ", "POD_NAMESPACE": "b"}) == "b"
