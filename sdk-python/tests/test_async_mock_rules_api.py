"""AsyncClient against a real local HTTP rules API."""

from __future__ import annotations

import asyncio
import time

import pytest

from klswitch import (
    AsyncClient,
    AsyncRulesFetcher,
    EvaluationInput,
    Facts,
    LoadRulesError,
    RuleSetPayload,
    Selector,
)

from .test_mock_rules_api import MockRulesServer, _typed_ruleset


def _eval_input() -> EvaluationInput:
    return EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=Facts(body={"amount": 500}),
    )


@pytest.fixture()
async def mock_server():
    s = MockRulesServer()
    try:
        yield s
    finally:
        s.close()


@pytest.mark.asyncio
async def test_init_from_url_loads_rules(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 5
    async with AsyncClient(token="ks_live_test", rules_url=mock_server.url) as ks:
        st = await ks.get_status()
        assert st.ready and st.last_load_ok
        assert st.active_rules_version == 5
        assert st.last_refresh_ok is True
        result = await ks.evaluate(_eval_input())
        assert len(result.matched) == 1


@pytest.mark.asyncio
async def test_refresh_picks_up_new_version(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 1
    async with AsyncClient(token="ks_live_test", rules_url=mock_server.url) as ks:
        with mock_server._lock:
            mock_server.current_version = 9
        await ks.refresh()
        st = await ks.get_status()
        assert st.active_rules_version == 9


@pytest.mark.asyncio
async def test_refresh_failure_keeps_last_known_good(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 7
    async with AsyncClient(token="ks_live_test", rules_url=mock_server.url) as ks:
        with mock_server._lock:
            mock_server.fails = True
        await ks.refresh()  # must not raise
        st = await ks.get_status()
        assert st.active_rules_version == 7
        assert st.last_refresh_ok is False
        assert st.last_refresh_error is not None
        assert "500" in st.last_refresh_error


@pytest.mark.asyncio
async def test_recurring_polling_picks_up_changes(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 1
    async with AsyncClient(token="ks_live_test", rules_url=mock_server.url, refresh_interval=0.05) as ks:
        st = await ks.get_status()
        assert st.polling is True
        with mock_server._lock:
            mock_server.current_version = 42
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            s = await ks.get_status()
            if s.active_rules_version == 42:
                return
            await asyncio.sleep(0.02)
        pytest.fail(f"recurring poll never picked up v42; status={await ks.get_status()}")


@pytest.mark.asyncio
async def test_init_fails_when_initial_fetch_fails(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.fails = True
    with pytest.raises(LoadRulesError):
        async with AsyncClient(token="ks_live_test", rules_url=mock_server.url):
            pass


@pytest.mark.asyncio
async def test_inline_fallback_when_url_fetch_fails(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.fails = True
    async with AsyncClient(token="ks_live_test", rules=_typed_ruleset(3), rules_url=mock_server.url) as ks:
        st = await ks.get_status()
        assert st.active_rules_version == 3, "inline LKG must remain"
        assert st.last_refresh_ok is False


@pytest.mark.asyncio
async def test_user_supplied_async_fetcher_is_used_natively() -> None:
    calls = {"n": 0}

    class _AsyncFetcher:
        async def fetch(self) -> RuleSetPayload:
            calls["n"] += 1
            await asyncio.sleep(0)  # native async fetch
            return _typed_ruleset(99)

    fetcher: AsyncRulesFetcher = _AsyncFetcher()
    async with AsyncClient(fetcher=fetcher) as ks:
        assert (await ks.get_status()).active_rules_version == 99
    assert calls["n"] == 1


@pytest.mark.asyncio
async def test_sync_fetcher_is_wrapped_for_async() -> None:
    """A sync RulesFetcher passed to AsyncClient runs via asyncio.to_thread."""
    calls = {"n": 0}

    class _SyncFetcher:
        def fetch(self) -> RuleSetPayload:
            calls["n"] += 1
            return _typed_ruleset(123)

    async with AsyncClient(fetcher=_SyncFetcher()) as ks:
        assert (await ks.get_status()).active_rules_version == 123
    assert calls["n"] == 1


@pytest.mark.asyncio
async def test_negative_refresh_interval_rejected() -> None:
    with pytest.raises(ValueError, match="refresh_interval"):
        AsyncClient(rules=_typed_ruleset(1), refresh_interval=-1.0)
