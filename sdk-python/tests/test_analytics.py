"""AnalyticsReporter unit tests (sync flavour)."""

from __future__ import annotations

import gzip as _gzip
import http.server
import json
import threading
import time
from contextlib import contextmanager
from typing import Iterator

from klswitch import (
    AnalyticsEvent,
    AnalyticsOptions,
    AnalyticsRequest,
    AnalyticsStatus,
    AnalyticsTransport,
    LatencyBucket,
    MatchedAction,
    latency_bucket,
)
from klswitch._analytics import AnalyticsReporter


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


def _wait_for(pred, timeout: float = 2.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pred():
            return True
        time.sleep(0.01)
    return False


def _sink_transport(sink) -> AnalyticsTransport:
    return AnalyticsTransport(
        sdk_lang="python", sdk_version="0.1.0", instance_key="pod-1", sink=sink,
    )


def test_latency_bucket_new_enum() -> None:
    assert latency_bucket(0.0001 / 1000) == LatencyBucket.LT_50US      # 0.0001 ms
    assert latency_bucket(0.0001)         == LatencyBucket.LT_500US    # 0.1 ms
    assert latency_bucket(0.002)          == LatencyBucket.LT_5MS      # 2 ms
    assert latency_bucket(0.050)          == LatencyBucket.GE_5MS      # 50 ms


def test_record_then_flush_delivers_batches() -> None:
    mu = threading.Lock()
    batches: list[list[AnalyticsEvent]] = []

    def sink(events: list[AnalyticsEvent]) -> None:
        with mu:
            batches.append(list(events))

    r = AnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=3, flush_interval=0.05),
        _sink_transport(sink),
    )
    try:
        for _ in range(5):
            r.record(_evt())
        assert _wait_for(lambda: sum(len(b) for b in batches) == 5), (
            f"expected 5 events flushed, got batches={batches}"
        )
    finally:
        r.close()


def test_overflow_drops_oldest_keeps_most_recent() -> None:
    mu = threading.Lock()
    seen: list[AnalyticsEvent] = []

    def sink(events: list[AnalyticsEvent]) -> None:
        with mu:
            seen.extend(events)

    r = AnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=100, flush_interval=3600, max_queue_size=2),
        _sink_transport(sink),
    )
    try:
        for i in range(5):
            r.record(_evt(f"evt-{i}"))
        r.flush_now()
        with mu:
            assert len(seen) == 2, f"buffer capped at 2; saw {len(seen)}"
            assert seen[0].occurred_at == "evt-3", (
                f"drop-oldest should preserve evt-3; got {seen[0].occurred_at}"
            )
            assert seen[1].occurred_at == "evt-4"
        st = r.status()
        assert st.events_dropped == 3
    finally:
        r.close()


def test_sink_error_counts_as_failure() -> None:
    def sink(events: list[AnalyticsEvent]) -> None:
        raise RuntimeError("sink boom")

    r = AnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=1, flush_interval=3600),
        _sink_transport(sink),
    )
    try:
        r.record(_evt())
        r.flush_now()
        st = r.status()
        assert st.events_sent == 0
        assert st.batches_failed == 1
        assert st.last_flush_ok is False
        assert st.last_flush_error is not None
        assert "boom" in st.last_flush_error
        assert st.consecutive_failures == 1
    finally:
        r.close()


def test_status_snapshots_counters() -> None:
    def sink(events: list[AnalyticsEvent]) -> None:
        pass

    r = AnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=100, flush_interval=3600),
        _sink_transport(sink),
    )
    try:
        for _ in range(3):
            r.record(_evt())
        before = r.status()
        assert before.buffer_size == 3
        assert before.events_recorded == 3
        assert before.events_sent == 0
        assert isinstance(before, AnalyticsStatus)

        r.flush_now()
        after = r.status()
        assert after.buffer_size == 0
        assert after.events_sent == 3
        assert after.last_flush_ok is True
    finally:
        r.close()


def test_close_drains_remaining() -> None:
    mu = threading.Lock()
    seen = {"n": 0}

    def sink(events: list[AnalyticsEvent]) -> None:
        with mu:
            seen["n"] += len(events)

    r = AnalyticsReporter(
        AnalyticsOptions(sink=sink, batch_size=1000, flush_interval=3600, max_queue_size=100),
        _sink_transport(sink),
    )
    for _ in range(7):
        r.record(_evt())
    r.close()
    assert seen["n"] == 7, f"close must drain remaining events; saw {seen['n']}/7"


# ── HTTP transport ──────────────────────────────────────────────────────


@contextmanager
def _mock_server(reply_status: int = 204) -> Iterator[tuple[str, list[bytes], list[dict]]]:
    bodies: list[bytes] = []
    headers_list: list[dict] = []

    class _Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802 — http.server requires this name
            length = int(self.headers.get("Content-Length", "0"))
            bodies.append(self.rfile.read(length))
            headers_list.append({k: v for k, v in self.headers.items()})
            self.send_response(reply_status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format, *args):  # silence
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True, name="ana-mock")
    t.start()
    try:
        url = f"http://127.0.0.1:{srv.server_address[1]}/v1/decisions"
        yield url, bodies, headers_list
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=2.0)


def test_http_transport_posts_gzipped_envelope() -> None:
    with _mock_server() as (url, bodies, headers_list):
        transport = AnalyticsTransport(
            url=url,
            headers={"Authorization": "Bearer t", "X-Instance-Key": "pod-1"},
            sdk_lang="python", sdk_version="0.1.0", instance_key="pod-1",
        )
        r = AnalyticsReporter(
            AnalyticsOptions(batch_size=2, flush_interval=3600),
            transport,
        )
        try:
            r.record(AnalyticsEvent(
                occurred_at="2026-06-23T12:00:00Z", rules_version=1,
                request=AnalyticsRequest(method="POST", path="/p"),
                matched=[MatchedAction(rule_id="r1", action_type="block")],
                evaluated=1, skipped=0,
                latency_bucket=LatencyBucket.LT_500US,
            ))
            r.record(AnalyticsEvent(
                occurred_at="2026-06-23T12:00:01Z", rules_version=1,
                request=AnalyticsRequest(method="POST", path="/p"),
                matched=[MatchedAction(rule_id="r2", action_type="notify")],
                evaluated=1, skipped=0,
                latency_bucket=LatencyBucket.LT_500US,
            ))
            assert _wait_for(lambda: len(bodies) >= 1)
            assert headers_list[0].get("Content-Encoding") == "gzip"
            assert headers_list[0].get("Authorization") == "Bearer t"

            inflated = _gzip.decompress(bodies[0])
            envelope = json.loads(inflated)
            assert envelope["sdkLang"] == "python"
            assert envelope["sdkVersion"] == "0.1.0"
            assert envelope["instanceKey"] == "pod-1"
            assert len(envelope["events"]) == 2
            assert envelope["events"][0]["matched"][0]["ruleId"] == "r1"
            assert envelope["events"][0]["latencyBucket"] == "lt_500us"
            assert envelope["events"][0]["type"] == "evaluation"
            assert "errorCount" not in envelope["events"][0]
        finally:
            r.close()


def test_http_non_2xx_counts_as_failure() -> None:
    with _mock_server(reply_status=500) as (url, _bodies, _h):
        transport = AnalyticsTransport(
            url=url, sdk_lang="python", sdk_version="0.1.0", instance_key="pod-1",
        )
        r = AnalyticsReporter(
            AnalyticsOptions(batch_size=1, flush_interval=3600),
            transport,
        )
        try:
            r.record(_evt())
            # Wait for the auto-flush triggered by batch_size=1.
            assert _wait_for(lambda: r.status().batches_failed >= 1 or r.status().events_sent > 0)
            r.flush_now()
            st = r.status()
            assert st.events_sent == 0
            assert st.batches_failed >= 1
            assert st.last_flush_ok is False
            assert st.last_flush_error is not None
            assert "500" in st.last_flush_error
        finally:
            r.close()
