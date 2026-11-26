"""AsyncPollingManager + AsyncHTTPFetcher-via-wrapper tests."""

from __future__ import annotations

import asyncio
import time
from typing import Awaitable, Callable

import pytest

from klswitch import FetchError, RuleSetPayload
from klswitch._polling import AsyncPollingManager
from klswitch.logger import NullLogger


class _FakeAsyncFetcher:
    def __init__(self, respond: Callable[[int], Awaitable[RuleSetPayload]]) -> None:
        self.respond = respond
        self.calls = 0

    async def fetch(self) -> RuleSetPayload:
        self.calls += 1
        return await self.respond(self.calls)


def _rs(v: int) -> RuleSetPayload:
    return RuleSetPayload(schema_version="1.0", rules_version=v, rules=[])


@pytest.mark.asyncio
async def test_poll_once_dispatches_to_on_fetched() -> None:
    async def respond(_n: int) -> RuleSetPayload:
        return _rs(7)

    got: list[RuleSetPayload] = []

    async def on_fetched(rs):
        got.append(rs)

    async def on_error(_e):
        raise AssertionError("unexpected on_error")

    p = AsyncPollingManager(_FakeAsyncFetcher(respond), 3600, NullLogger(), on_fetched, on_error)
    await p.poll_once()
    assert len(got) == 1 and got[0].rules_version == 7


@pytest.mark.asyncio
async def test_fetch_failure_dispatches_to_on_error() -> None:
    async def respond(_n: int):
        raise FetchError("network down")

    saw: list[Exception] = []

    async def on_fetched(_rs):
        raise AssertionError("unexpected on_fetched")

    async def on_error(e):
        saw.append(e)

    p = AsyncPollingManager(_FakeAsyncFetcher(respond), 3600, NullLogger(), on_fetched, on_error)
    await p.poll_once()
    assert saw and "network down" in str(saw[0])


@pytest.mark.asyncio
async def test_recurring_loop_fires_and_closes_cleanly() -> None:
    fetcher = _FakeAsyncFetcher(lambda _n: asyncio.sleep(0, result=_rs(1)))

    async def noop(_x):
        pass

    p = AsyncPollingManager(fetcher, 0.03, NullLogger(), noop, noop)
    p.start()
    # Wait for >=3 fetches.
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline and fetcher.calls < 3:
        await asyncio.sleep(0.02)
    assert fetcher.calls >= 3
    await p.close()


@pytest.mark.asyncio
async def test_backoff_grows_with_consecutive_failures() -> None:
    async def fail(_n: int):
        raise FetchError("boom")

    async def noop(_x):
        pass

    p = AsyncPollingManager(_FakeAsyncFetcher(fail), 0.1, NullLogger(), noop, noop)
    for _ in range(3):
        await p.poll_once()
    assert p._next_delay() > 0.1
    assert p._next_delay() <= 60.0
