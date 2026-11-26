"""PollingManager unit tests (with fake fetchers, no HTTP)."""

from __future__ import annotations

import threading
import time

from klswitch import DEFAULT_REFRESH_TIMEOUT_SECONDS, FetchError, RuleSetPayload
from klswitch._polling import PollingManager
from klswitch.logger import NullLogger


class _FakeFetcher:
    """Callable-driven RulesFetcher for unit tests."""

    def __init__(self, respond) -> None:
        self.respond = respond
        self.calls = 0
        self._lock = threading.Lock()

    def fetch(self) -> RuleSetPayload:
        with self._lock:
            self.calls += 1
            n = self.calls
        return self.respond(n)  # type: ignore[no-any-return]


def _ruleset(v: int) -> RuleSetPayload:
    return RuleSetPayload(schema_version="1.0", rules_version=v, rules=[])


def test_poll_once_calls_on_fetched() -> None:
    fetcher = _FakeFetcher(lambda _n: _ruleset(7))
    got: list[RuleSetPayload] = []
    p = PollingManager(
        fetcher=fetcher,
        interval=3600,
        logger=NullLogger(),
        on_fetched=got.append,
        on_error=lambda e: (_ for _ in ()).throw(AssertionError(f"unexpected onError: {e}")),
    )
    p.poll_once()
    assert len(got) == 1 and got[0].rules_version == 7


def test_fetch_failure_calls_on_error() -> None:
    fetcher = _FakeFetcher(lambda _n: (_ for _ in ()).throw(FetchError("network down")))
    saw: list[Exception] = []
    p = PollingManager(
        fetcher=fetcher,
        interval=3600,
        logger=NullLogger(),
        on_fetched=lambda _rs: (_ for _ in ()).throw(AssertionError("unexpected onFetched")),
        on_error=saw.append,
    )
    p.poll_once()
    assert saw and "network down" in str(saw[0])


def test_recurring_loop_fires_and_closes_cleanly() -> None:
    fetcher = _FakeFetcher(lambda _n: _ruleset(1))
    p = PollingManager(
        fetcher=fetcher,
        interval=0.03,
        logger=NullLogger(),
        on_fetched=lambda _rs: None,
        on_error=lambda _e: None,
    )
    p.start()
    # Wait for >=3 fetches to confirm the loop is firing.
    deadline = time.time() + 2.0
    while time.time() < deadline and fetcher.calls < 3:
        time.sleep(0.02)
    assert fetcher.calls >= 3, f"expected >=3 polls, saw {fetcher.calls}"
    p.close()  # must not hang


def test_backoff_grows_with_consecutive_failures() -> None:
    fetcher = _FakeFetcher(lambda _n: (_ for _ in ()).throw(FetchError("boom")))
    p = PollingManager(
        fetcher=fetcher,
        interval=0.1,
        logger=NullLogger(),
        on_fetched=lambda _rs: None,
        on_error=lambda _e: None,
    )
    for _ in range(3):
        p.poll_once()
    delay = p._next_delay()
    # 0.1 * 2^3 = 0.8s base, then ±25% jitter → roughly [0.6, 1.0).
    assert delay > 0.1, f"expected backoff > base interval, got {delay}"
    assert delay <= 60.0


def test_success_resets_backoff() -> None:
    mode = {"fail": True}

    def respond(_n: int) -> RuleSetPayload:
        if mode["fail"]:
            raise FetchError("boom")
        return _ruleset(1)

    fetcher = _FakeFetcher(respond)
    p = PollingManager(
        fetcher=fetcher,
        interval=0.1,
        logger=NullLogger(),
        on_fetched=lambda _rs: None,
        on_error=lambda _e: None,
    )
    p.poll_once()
    p.poll_once()
    assert p._next_delay() > 0.1, "backoff should grow after failures"
    mode["fail"] = False
    p.poll_once()
    # Success path → consecutive_failures resets → next delay = base interval
    # (jitter is only applied on the backoff path).
    assert p._next_delay() == 0.1


def test_long_server_cadence_is_never_undercut_by_cap() -> None:
    """B2: a long server cadence (Cache-Control: max-age) is never undercut by
    the backoff cap. With a 10-min cadence, the failure-side delay must stay
    >= the success cadence (modulo jitter), not collapse to the 5-min hard cap.
    """
    from klswitch._polling import PollingManager, MAX_BACKOFF_INTERVAL_SECONDS

    def boom() -> None:
        raise RuntimeError("boom")

    class _ThrowingFetcher:
        def fetch(self):  # type: ignore[no-untyped-def]
            boom()

    pm = PollingManager(
        fetcher=_ThrowingFetcher(),
        interval=30.0,
        logger=NullLogger(),
        on_fetched=lambda _r: None,
        on_error=lambda _e: None,
    )
    # Drive internal state directly to simulate a long server cadence
    # without spinning up the full fetcher stack.
    with pm._lock:
        pm._current_interval = 600.0  # 10 min
        pm._consecutive_failures = 3
    delay = pm._next_delay()
    # ±25 % jitter → lower bound is 0.75 * 600 = 450 s.
    assert delay >= 450.0, (
        f"backoff undercut the success cadence: got {delay}, want >= 450s"
    )
    # MAX_BACKOFF_INTERVAL_SECONDS is the spec-mandated 5 minutes.
    assert MAX_BACKOFF_INTERVAL_SECONDS == 300.0


def test_default_refresh_timeout_is_five_seconds() -> None:
    assert DEFAULT_REFRESH_TIMEOUT_SECONDS == 5.0
