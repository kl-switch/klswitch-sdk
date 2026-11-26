"""AsyncAnalyticsReporter tests."""

from __future__ import annotations

import asyncio
import time

import pytest

from klswitch import (
    AnalyticsEvent,
    AnalyticsOptions,
    AnalyticsRequest,
    AnalyticsTransport,
    LatencyBucket,
)
from klswitch._analytics import AsyncAnalyticsReporter


def _evt(tag: str = "") -> AnalyticsEvent:
    return AnalyticsEvent(
        occurred_at=tag or "2026-06-23T12:00:00Z",
        rules_version=1,
        request=AnalyticsRequest(method="POST", path="/p"),
        matched=[],
        evaluated=0,
        skipped=0,
        latency_bucket=LatencyBucket.LT_500US,
    )


async def _wait_for(pred, timeout: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if pred():
            return True
        await asyncio.sleep(0.01)
    return False


def _sink_transport(sink) -> AnalyticsTransport:
    return AnalyticsTransport(
        sdk_lang="python", sdk_version="0.1.0", instance_key="pod-1", sink=sink,
    )


@pytest.mark.asyncio
async def test_sync_sink_is_supported() -> None:
    seen: list[AnalyticsEvent] = []

    def sink(batch):
        seen.extend(batch)

    r = AsyncAnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=3, flush_interval=0.05),
        _sink_transport(sink),
    )
    r.start()
    try:
        for _ in range(5):
            r.record(_evt())
        ok = await _wait_for(lambda: len(seen) == 5)
        assert ok, f"expected 5 events; got {seen}"
    finally:
        await r.close()


@pytest.mark.asyncio
async def test_async_sink_is_awaited() -> None:
    seen: list[AnalyticsEvent] = []

    async def async_sink(batch) -> None:
        await asyncio.sleep(0)
        seen.extend(batch)

    r = AsyncAnalyticsReporter(
        AnalyticsOptions(sink=async_sink, batch_size=2, flush_interval=0.05),
        _sink_transport(async_sink),
    )
    r.start()
    try:
        for _ in range(4):
            r.record(_evt())
        ok = await _wait_for(lambda: len(seen) == 4)
        assert ok, f"async sink should receive all 4 events; got {seen}"
    finally:
        await r.close()


@pytest.mark.asyncio
async def test_overflow_drops_oldest_keeps_most_recent() -> None:
    seen: list[AnalyticsEvent] = []

    async def sink(batch) -> None:
        seen.extend(batch)

    r = AsyncAnalyticsReporter(
        AnalyticsOptions(
            sink=sink, batch_size=100, flush_interval=3600, max_queue_size=2,
        ),
        _sink_transport(sink),
    )
    r.start()
    try:
        for i in range(5):
            r.record(_evt(f"evt-{i}"))
        # Yield to allow start() task to be running but no flush nudge yet
        # (batch_size=100 way above max_queue_size, so no auto-flush).
        await asyncio.sleep(0)
        await r._flush()  # force flush
        assert len(seen) == 2
        assert seen[0].occurred_at == "evt-3"
        assert seen[1].occurred_at == "evt-4"
        st = r.status()
        assert st.events_dropped == 3
    finally:
        await r.close()


@pytest.mark.asyncio
async def test_close_drains_remaining_with_async_sink() -> None:
    seen = 0

    async def sink(batch) -> None:
        nonlocal seen
        seen += len(batch)

    r = AsyncAnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=1000, flush_interval=3600, max_queue_size=100),
        _sink_transport(sink),
    )
    r.start()
    for _ in range(7):
        r.record(_evt())
    await r.close()
    assert seen == 7
