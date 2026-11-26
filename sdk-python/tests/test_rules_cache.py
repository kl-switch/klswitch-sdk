"""B4 — Boot-time disk-cache tests for the Python wrapper.

Mirrors sdk-node/test/rules-cache.test.ts.
"""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

import pytest

from klswitch import (
    Action,
    Client,
    LoadRulesError,
    Rule,
    RuleSetPayload,
    RulesFetcher,
    Selector,
)
from klswitch._rules_cache import (
    default_cache_path,
    read_cached_ruleset,
    write_cached_ruleset,
)
from klswitch.logger import NullLogger


def _ruleset(version: int) -> RuleSetPayload:
    return RuleSetPayload(
        schema_version="1.0",
        rules_version=version,
        rules=[
            Rule(
                id="r",
                name="R",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 0}]},
                action=Action(type="block"),
            ),
        ],
    )


# ─── default_cache_path ───────────────────────────────────────────────────


def test_default_cache_path_uses_tmpdir() -> None:
    path = default_cache_path("prod")
    assert path.endswith("ks-cache-prod.json")
    assert path.startswith(tempfile.gettempdir())


def test_default_cache_path_sanitises_unsafe_chars() -> None:
    path = default_cache_path("prod/west")
    assert path.endswith("ks-cache-prod_west.json")


def test_default_cache_path_empty_env_fallback() -> None:
    path = default_cache_path("")
    assert path.endswith("ks-cache-default.json")


# ─── Round trip ──────────────────────────────────────────────────────────


def test_write_then_read_recovers_payload(tmp_path: Path) -> None:
    path = str(tmp_path / "cache.json")
    write_cached_ruleset(path, _ruleset(99), NullLogger())
    got = read_cached_ruleset(path, NullLogger())
    assert got is not None
    assert got.rules_version == 99


def test_write_is_atomic_no_tmp_lingers(tmp_path: Path) -> None:
    path = str(tmp_path / "cache.json")
    write_cached_ruleset(path, _ruleset(1), NullLogger())
    assert not Path(path + ".tmp").exists()


def test_write_creates_parent_directory(tmp_path: Path) -> None:
    path = str(tmp_path / "nested" / "subdir" / "cache.json")
    write_cached_ruleset(path, _ruleset(2), NullLogger())
    assert Path(path).exists()


def test_read_returns_none_for_missing_file(tmp_path: Path) -> None:
    assert read_cached_ruleset(str(tmp_path / "missing.json"), NullLogger()) is None


def test_read_returns_none_for_malformed_json(tmp_path: Path) -> None:
    path = tmp_path / "bad.json"
    path.write_text("{ not valid json", encoding="utf-8")
    assert read_cached_ruleset(str(path), NullLogger()) is None


def test_read_returns_none_for_wrong_shape(tmp_path: Path) -> None:
    path = tmp_path / "wrong-shape.json"
    path.write_text(json.dumps({"unrelated": "thing"}), encoding="utf-8")
    assert read_cached_ruleset(str(path), NullLogger()) is None


def test_read_returns_none_for_empty_file(tmp_path: Path) -> None:
    path = tmp_path / "empty.json"
    path.write_text("", encoding="utf-8")
    assert read_cached_ruleset(str(path), NullLogger()) is None


def test_read_returns_none_for_none_path() -> None:
    assert read_cached_ruleset(None, NullLogger()) is None


# ─── Client integration ──────────────────────────────────────────────────


class _FixedFetcher:
    """Fetcher that returns the same ruleset every time."""

    def __init__(self, rs: RuleSetPayload) -> None:
        self.rs = rs

    def fetch(self) -> RuleSetPayload:
        return self.rs


class _ErrorFetcher:
    """Fetcher that always errors (simulates backend down)."""

    def fetch(self) -> RuleSetPayload:
        raise RuntimeError("backend down")


def test_client_writes_cache_after_successful_fetch(tmp_path: Path) -> None:
    path = str(tmp_path / "cache.json")
    with Client(
        fetcher=_FixedFetcher(_ruleset(42)),
        refresh_interval=0,
        cache_path=path,
    ) as ks:
        assert Path(path).exists()
        cached_raw = Path(path).read_text(encoding="utf-8")
        cached = json.loads(cached_raw)
        assert cached["rules_version"] == 42
        # Active version matches.
        assert ks.get_status().active_rules_version == 42


def test_client_falls_back_to_cache_when_fetch_fails(tmp_path: Path) -> None:
    path = str(tmp_path / "cache.json")
    # Seed cache with a known good ruleset.
    write_cached_ruleset(path, _ruleset(99), NullLogger())

    with Client(
        fetcher=_ErrorFetcher(),
        refresh_interval=0,
        cache_path=path,
    ) as ks:
        # Boot succeeded against the cached ruleset.
        assert ks.get_status().active_rules_version == 99


def test_client_throws_when_no_cache_and_fetch_fails(tmp_path: Path) -> None:
    path = str(tmp_path / "absent.json")  # does NOT exist
    with pytest.raises(LoadRulesError, match="no rules available"):
        Client(
            fetcher=_ErrorFetcher(),
            refresh_interval=0,
            cache_path=path,
        )


def test_client_cache_path_unset_never_reads_disk() -> None:
    with pytest.raises(LoadRulesError, match="no rules available"):
        Client(
            fetcher=_ErrorFetcher(),
            refresh_interval=0,
        )


def test_client_corrupt_cache_is_ignored(tmp_path: Path) -> None:
    path = tmp_path / "corrupt.json"
    path.write_text("{not parseable", encoding="utf-8")
    with pytest.raises(LoadRulesError, match="no rules available"):
        Client(
            fetcher=_ErrorFetcher(),
            refresh_interval=0,
            cache_path=str(path),
        )


def test_client_fresh_fetch_overwrites_stale_cache(tmp_path: Path) -> None:
    path = str(tmp_path / "cache.json")
    # Seed cache with old version.
    write_cached_ruleset(path, _ruleset(1), NullLogger())

    with Client(
        fetcher=_FixedFetcher(_ruleset(5)),  # fresh = 5
        refresh_interval=0,
        cache_path=path,
    ) as ks:
        assert ks.get_status().active_rules_version == 5
        cached = json.loads(Path(path).read_text(encoding="utf-8"))
        assert cached["rules_version"] == 5
