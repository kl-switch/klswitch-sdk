"""AsyncClient lifecycle tests."""

from __future__ import annotations

import asyncio
import time

import pytest

from klswitch import (
    AnalyticsEvent,
    AnalyticsOptions,
    AsyncClient,
    ClosedClientError,
    EvaluationInput,
    Facts,
    LoadRulesError,
    Selector,
)

from .conftest import make_ruleset


def _eval_input() -> EvaluationInput:
    return EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=Facts(body={"amount": 500}),
    )


@pytest.mark.asyncio
async def test_inline_rules_init_evaluate_status_aclose() -> None:
    async with AsyncClient(rules=make_ruleset()) as ks:
        status = await ks.get_status()
        assert status.initialized
        assert status.last_load_ok
        assert status.active_rules_version == 12

        result = await ks.evaluate(_eval_input())
        assert result.ok and len(result.matched) == 1 and result.matched[0].id == "block"

    # After context exit:
    after = await ks.get_status()
    assert not after.initialized


@pytest.mark.asyncio
async def test_init_fails_clearly_on_invalid_rules() -> None:
    from klswitch import Action, Rule, RuleSetPayload

    bad = RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="bad",
                name="B",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                enabled=True,
                condition={"all": [{"field": "body.x", "operator": "bogus_op", "value": 1}]},
                action=Action(type="block"),
            ),
        ],
    )
    with pytest.raises(LoadRulesError):
        AsyncClient(rules=bad)


@pytest.mark.asyncio
async def test_init_fails_with_no_rules_and_no_token() -> None:
    # No inline rules + no token + no custom fetcher → token is required.
    with pytest.raises(ValueError, match="token is required"):
        AsyncClient()


@pytest.mark.asyncio
async def test_evaluate_after_aclose_raises() -> None:
    ks = AsyncClient(rules=make_ruleset())
    await ks.aclose()
    with pytest.raises(ClosedClientError):
        await ks.evaluate(_eval_input())


@pytest.mark.asyncio
async def test_aclose_is_idempotent() -> None:
    ks = AsyncClient(rules=make_ruleset())
    await ks.aclose()
    await ks.aclose()  # must not raise


@pytest.mark.asyncio
async def test_repr_reflects_state() -> None:
    ks = AsyncClient(rules=make_ruleset())
    r_open = repr(ks)
    assert "klswitch.AsyncClient" in r_open
    assert "closed=False" in r_open
    await ks.aclose()
    assert "closed=True" in repr(ks)


@pytest.mark.asyncio
async def test_async_sink_records_events_through_evaluate() -> None:
    seen: list[AnalyticsEvent] = []

    async def sink(batch) -> None:
        seen.extend(batch)

    async with AsyncClient(
        rules=make_ruleset(),
        analytics=AnalyticsOptions(sink=sink, batch_size=1, flush_interval=0.05),
    ) as ks:
        for _ in range(3):
            await ks.evaluate(_eval_input())

        # Wait for flusher to deliver.
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            if len(seen) == 3:
                break
            await asyncio.sleep(0.02)

    assert len(seen) == 3, f"expected 3 async-sink events, got {len(seen)}"
    e = seen[0]
    assert len(e.matched) == 1
    assert e.matched[0].rule_id == "block"
    assert e.matched[0].action_type == "block"
    assert e.request.method == "POST"
    assert e.request.path == "/p"


@pytest.mark.asyncio
async def test_concurrent_start_runs_once() -> None:
    """Two coroutines calling start() concurrently must serialise on the
    start lock — only one polling task / analytics reporter is created.
    """
    ks = AsyncClient(rules=make_ruleset())
    try:
        await asyncio.gather(ks.start(), ks.start(), ks.start())
        # The bridge is up exactly once (we'd see weird state if start ran twice).
        assert (await ks.get_status()).active_rules_version == 12
    finally:
        await ks.aclose()


@pytest.mark.asyncio
async def test_concurrent_evaluate_is_safe() -> None:
    """Many coroutines hitting the same AsyncClient must serialise correctly."""
    async with AsyncClient(rules=make_ruleset()) as ks:
        inp = _eval_input()

        async def worker() -> None:
            for _ in range(50):
                r = await ks.evaluate(inp)
                assert r.ok and len(r.matched) == 1

        await asyncio.gather(*(worker() for _ in range(8)))
