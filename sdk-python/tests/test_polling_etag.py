"""B1 — ETag + Cache-Control polling tests.

Covers the v1.1 server-driven polling contract: HEAD-first when an ETag
is cached, conditional GET on flip, server-driven cadence via
Cache-Control, and graceful degradation when the server omits either
header. Drives the real :class:`HTTPFetcher` + :class:`PollingManager`
against an in-process ``http.server`` so the HEAD / 304 / Cache-Control
semantics see real HTTP.
"""

from __future__ import annotations

import http.server
import socketserver
import threading
import time
from dataclasses import dataclass
from typing import Iterator

import pytest

from klswitch._polling import (
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
    FetchResult,
    HTTPFetcher,
    PollingManager,
    _parse_max_age,
)
from klswitch.logger import NullLogger

RULES_V1 = b'{"schema_version":"1.0","rules_version":1,"rules":[]}'
RULES_V2 = b'{"schema_version":"1.0","rules_version":2,"rules":[]}'


@dataclass
class Reply:
    status: int
    etag: str | None = None        # ETag header on the response
    cache_control: str | None = None  # Cache-Control header on the response
    body: bytes = b""              # ignored for HEAD and 304


class MockState:
    """Thread-safe queue of canned replies + a record of seen requests."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.replies: list[Reply] = []
        self.methods: list[str] = []
        self.if_none_match: list[str | None] = []
        self.cursor = 0

    def next_reply(self) -> Reply:
        with self.lock:
            idx = min(self.cursor, len(self.replies) - 1)
            self.cursor += 1
            return self.replies[idx]


@pytest.fixture
def mock_server() -> Iterator[tuple[str, MockState]]:
    state = MockState()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args, **_kw):  # silence stderr noise during tests
            pass

        def _record(self) -> None:
            with state.lock:
                state.methods.append(self.command)
                state.if_none_match.append(self.headers.get("If-None-Match"))

        def _write(self, reply: Reply) -> None:
            self.send_response(reply.status)
            if reply.etag is not None:
                self.send_header("ETag", reply.etag)
            if reply.cache_control is not None:
                self.send_header("Cache-Control", reply.cache_control)
            # send body for GET 2xx; HEAD and 304 are headers-only
            body = reply.body if self.command == "GET" and 200 <= reply.status < 300 else b""
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def do_GET(self):  # noqa: N802
            self._record()
            self._write(state.next_reply())

        def do_HEAD(self):  # noqa: N802
            self._record()
            self._write(state.next_reply())

    with socketserver.TCPServer(("127.0.0.1", 0), Handler) as srv:
        port = srv.server_address[1]
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{port}/", state
        finally:
            srv.shutdown()
            thread.join(timeout=2.0)


def _fetcher(url: str) -> HTTPFetcher:
    return HTTPFetcher(url=url, headers={"Authorization": "Bearer t"}, timeout=2.0)


# ─── _parse_max_age (unit) ───────────────────────────────────────────────


def test_parse_max_age_simple() -> None:
    assert _parse_max_age("max-age=120") == 120.0


def test_parse_max_age_with_extras() -> None:
    assert _parse_max_age("public, max-age=45, must-revalidate") == 45.0


def test_parse_max_age_zero_is_present() -> None:
    assert _parse_max_age("max-age=0") == 0.0


def test_parse_max_age_absent_is_none() -> None:
    assert _parse_max_age(None) is None
    assert _parse_max_age("") is None
    assert _parse_max_age("public, no-cache") is None


# ─── HEAD-first / ETag dedup ─────────────────────────────────────────────


def test_first_fetch_is_get_then_subsequent_are_head(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
        Reply(status=200, etag='"v1"', cache_control="max-age=30"),  # HEAD match
    ]
    f = _fetcher(url)
    r1 = f.fetch_with_meta()
    r2 = f.fetch_with_meta()

    assert state.methods == ["GET", "HEAD"]
    assert not r1.not_modified, "cold GET should deliver a payload"
    assert r2.not_modified, "HEAD ETag match → not_modified"
    assert r2.etag == '"v1"'


def test_head_etag_flip_triggers_conditional_get(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
        Reply(status=200, etag='"v2"', cache_control="max-age=30"),  # HEAD: flip
        Reply(status=200, etag='"v2"', cache_control="max-age=30", body=RULES_V2),
    ]
    f = _fetcher(url)
    f.fetch_with_meta()
    r2 = f.fetch_with_meta()

    assert state.methods == ["GET", "HEAD", "GET"]
    assert state.if_none_match[2] == '"v1"', "conditional GET sends If-None-Match"
    assert not r2.not_modified
    assert r2.etag == '"v2"'
    assert r2.payload is not None and r2.payload.rules_version == 2


def test_conditional_get_304_is_noop(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
        Reply(status=200, etag='"v2"', cache_control="max-age=30"),  # HEAD: claims new
        Reply(status=304, etag='"v1"', cache_control="max-age=30"),  # race: server reverted
    ]
    f = _fetcher(url)
    f.fetch_with_meta()
    r2 = f.fetch_with_meta()

    assert state.methods == ["GET", "HEAD", "GET"]
    assert r2.not_modified, "304 → not_modified"


# ─── Cache-Control max-age tuning ────────────────────────────────────────


def test_cache_control_populates_next_delay(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=120", body=RULES_V1),
    ]
    r = _fetcher(url).fetch_with_meta()
    assert r.next_delay == 120.0


def test_missing_cache_control_means_none_next_delay(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [Reply(status=200, etag='"v1"', body=RULES_V1)]
    r = _fetcher(url).fetch_with_meta()
    assert r.next_delay is None


# ─── PollingManager — server-driven schedule ─────────────────────────────


def test_polling_status_reflects_last_etag_and_current_interval(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v9"', cache_control="max-age=120", body=RULES_V1),
    ]
    fetched: list = []
    pm = PollingManager(
        fetcher=_fetcher(url),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=fetched.append,
        on_error=lambda _e: None,
    )
    pm.poll_once()
    st = pm.status()
    assert st.last_etag == '"v9"'
    assert st.current_interval == 120.0, "server max-age sets the cadence"
    assert len(fetched) == 1


def test_polling_clamps_max_age_zero_to_min(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [Reply(status=200, etag='"v1"', cache_control="max-age=0", body=RULES_V1)]
    pm = PollingManager(
        fetcher=_fetcher(url),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=lambda _r: None,
        on_error=lambda _e: None,
    )
    pm.poll_once()
    assert pm.status().current_interval == MIN_POLL_INTERVAL_SECONDS


def test_polling_clamps_enormous_max_age_to_max(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=999999", body=RULES_V1),
    ]
    pm = PollingManager(
        fetcher=_fetcher(url),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=lambda _r: None,
        on_error=lambda _e: None,
    )
    pm.poll_once()
    assert pm.status().current_interval == MAX_POLL_INTERVAL_SECONDS


def test_polling_skips_on_fetched_when_server_says_no_change(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
        Reply(status=200, etag='"v1"', cache_control="max-age=30"),  # HEAD match
    ]
    fetched: list = []
    pm = PollingManager(
        fetcher=_fetcher(url),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=fetched.append,
        on_error=lambda _e: None,
    )
    pm.poll_once()
    pm.poll_once()
    assert len(fetched) == 1, "HEAD match → no second on_fetched"


# ─── Graceful degradation ────────────────────────────────────────────────


def test_no_etag_in_response_means_every_poll_is_a_cold_get(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [Reply(status=200, body=RULES_V1)]  # no ETag — sticks
    f = _fetcher(url)
    f.fetch_with_meta()
    f.fetch_with_meta()
    f.fetch_with_meta()
    assert state.methods == ["GET", "GET", "GET"], (
        "no cached ETag → no HEAD optimisation"
    )


def test_legacy_fetch_always_issues_plain_get(
    mock_server: tuple[str, MockState],
) -> None:
    url, state = mock_server
    state.replies = [
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
        Reply(status=200, etag='"v1"', cache_control="max-age=30", body=RULES_V1),
    ]
    f = _fetcher(url)
    f.fetch()
    f.fetch()
    assert state.methods == ["GET", "GET"], "legacy fetch() never uses HEAD"


def test_polling_status_before_any_fetch() -> None:
    pm = PollingManager(
        fetcher=_StubFetcher(),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=lambda _r: None,
        on_error=lambda _e: None,
    )
    st = pm.status()
    assert st.last_etag is None
    assert st.next_poll_at is None
    assert st.current_interval == 30.0, "bootstrap interval is the default"


class _StubFetcher:
    """Minimal RulesFetcher (no fetch_with_meta) — exercises the legacy-only
    fallback path in PollingManager.
    """

    def fetch(self):  # type: ignore[no-untyped-def]
        from klswitch import RuleSetPayload
        return RuleSetPayload(schema_version="1.0", rules_version=1, rules=[])
