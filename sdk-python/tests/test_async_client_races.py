"""AsyncClient.start() race test.

The async setup is guarded by an asyncio.Lock so two coroutines hitting
``start()`` concurrently can't both spawn polling/analytics
tasks. The lock + ``_started`` flag together must collapse N parallel
starts into ONE initialisation sequence.

We measure that by counting how many times the operator-supplied
fetcher is called during the start sequence — the first fetcher call
inside ``start()`` is the canonical "initialisation happened" signal.
"""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from klswitch import AsyncClient, AsyncRulesFetcher, RuleSetPayload


class _CountingFetcher:
    """AsyncRulesFetcher that records every call. Returns a fixed,
    empty-but-valid ruleset so init can complete.
    """

    def __init__(self) -> None:
        self.calls = 0

    async def fetch(self) -> RuleSetPayload:
        self.calls += 1
        # Tiny await so concurrent callers genuinely interleave on the
        # event loop instead of running synchronously in start().
        await asyncio.sleep(0)
        return RuleSetPayload(schema_version="1.0", rules_version=1, rules=[])


@pytest.mark.asyncio
async def test_concurrent_start_collapses_to_one_initialisation() -> None:
    fetcher = _CountingFetcher()
    ks = AsyncClient(fetcher=fetcher, refresh_interval=0)

    # Fire FIVE concurrent start() coroutines. The asyncio.Lock + _started
    # flag must collapse to exactly ONE initial fetch — subsequent
    # waiters see _started=True after the first one returns and skip
    # the body.
    try:
        await asyncio.gather(
            ks.start(), ks.start(), ks.start(), ks.start(), ks.start(),
        )
        assert fetcher.calls == 1, (
            f"expected exactly 1 init fetch under concurrent start(); got {fetcher.calls}"
        )
    finally:
        await ks.aclose()


@pytest.mark.asyncio
async def test_sequential_start_is_idempotent() -> None:
    """A second start() after the first returns must also be a no-op —
    the ``if self._started`` guard short-circuits."""
    fetcher = _CountingFetcher()
    ks = AsyncClient(fetcher=fetcher, refresh_interval=0)
    try:
        await ks.start()
        await ks.start()
        await ks.start()
        assert fetcher.calls == 1, (
            f"expected 1 init fetch across 3 sequential start() calls; got {fetcher.calls}"
        )
    finally:
        await ks.aclose()
