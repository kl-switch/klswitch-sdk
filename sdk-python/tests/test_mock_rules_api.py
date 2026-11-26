"""Drive the wrapper against a real local HTTP rules API.

Mirrors sdk-go's mock_rules_api_test.go: exercises the default HTTPFetcher,
the polling loop, last-known-good preservation on failure, error-body
propagation into status, and the inline-fallback behaviour.
"""

from __future__ import annotations

import gzip
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

import pytest

from klswitch import (
    Action,
    Client,
    EvaluationInput,
    Facts,
    FetchError,
    Rule,
    RuleSetPayload,
    RulesFetcher,
    Selector,
)


def _ruleset_dict(version: int) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "rules_version": version,
        "rules": [
            {
                "id": "r",
                "name": "R",
                "selector": {"kind": "api", "keys": {"method": "POST", "path": "/p"}},
                "enabled": True,
                "priority": 100,
                "condition": {
                    "all": [{"field": "body.amount", "operator": "gt", "value": 100}],
                },
                "action": {"type": "block"},
            },
        ],
    }


def _typed_ruleset(v: int) -> RuleSetPayload:
    return RuleSetPayload(
        schema_version="1.0",
        rules_version=v,
        rules=[
            Rule(
                id="r",
                name="R",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 100}]},
                action=Action(type="block"),
            ),
        ],
    )


def _eval_input() -> EvaluationInput:
    return EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=Facts(body={"amount": 500}),
    )


class MockRulesServer:
    """In-process HTTP server returning successive rulesets."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.current_version: int = 1
        self.fails: bool = False
        self.calls: int = 0

        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib API name
                with outer._lock:
                    outer.calls += 1
                    fails = outer.fails
                    version = outer.current_version
                if fails:
                    body = b"simulated upstream failure: maintenance window"
                    self.send_response(500)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                body = json.dumps(_ruleset_dict(version)).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, fmt: str, *args: Any) -> None:  # silence
                pass

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}/"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


@pytest.fixture()
def mock_server():
    s = MockRulesServer()
    yield s
    s.close()


# ───── tests ─────────────────────────────────────────────────────────────────


def test_init_from_url_loads_rules(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 5
    with Client(token="ks_live_test", rules_url=mock_server.url) as ks:
        st = ks.get_status()
        assert st.ready and st.last_load_ok
        assert st.active_rules_version == 5
        assert st.last_refresh_ok is True
        result = ks.evaluate(_eval_input())
        assert len(result.matched) == 1


def test_refresh_picks_up_new_version(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 1
    with Client(token="ks_live_test", rules_url=mock_server.url) as ks:
        with mock_server._lock:
            mock_server.current_version = 9
        ks.refresh()
        st = ks.get_status()
        assert st.active_rules_version == 9


def test_refresh_failure_keeps_last_known_good(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 7
    with Client(token="ks_live_test", rules_url=mock_server.url) as ks:
        with mock_server._lock:
            mock_server.fails = True
        ks.refresh()  # must not raise
        st = ks.get_status()
        assert st.active_rules_version == 7, "LKG must be preserved"
        assert st.last_refresh_ok is False
        assert st.last_refresh_error is not None
        assert "500" in st.last_refresh_error
        # And evaluate still works against the preserved LKG ruleset.
        result = ks.evaluate(_eval_input())
        assert len(result.matched) == 1


def test_recurring_polling_picks_up_changes(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.current_version = 1
    with Client(token="ks_live_test", rules_url=mock_server.url, refresh_interval=0.05) as ks:
        assert ks.get_status().polling is True
        with mock_server._lock:
            mock_server.current_version = 42
        deadline = time.time() + 3.0
        while time.time() < deadline:
            if ks.get_status().active_rules_version == 42:
                return
            time.sleep(0.02)
        pytest.fail(f"recurring poll never picked up v42; status={ks.get_status()}")


def test_init_fails_when_initial_fetch_fails(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.fails = True
    from klswitch import LoadRulesError

    with pytest.raises(LoadRulesError):
        Client(token="ks_live_test", rules_url=mock_server.url)


def test_inline_fallback_when_url_fetch_fails(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.fails = True
    with Client(token="ks_live_test", rules=_typed_ruleset(3), rules_url=mock_server.url) as ks:
        st = ks.get_status()
        assert st.active_rules_version == 3, "inline LKG must remain"
        assert st.last_refresh_ok is False, "the initial fetch failed"


def test_error_body_snippet_propagates_to_status(mock_server: MockRulesServer) -> None:
    with mock_server._lock:
        mock_server.fails = True
    with Client(token="ks_live_test", rules=_typed_ruleset(1), rules_url=mock_server.url) as ks:
        st = ks.get_status()
        err = st.last_refresh_error or ""
        assert "500" in err
        assert "maintenance" in err, f"expected body snippet in error, got: {err!r}"


def test_with_fetcher_injection() -> None:
    calls = {"n": 0}

    class _Fetcher:
        def fetch(self) -> RuleSetPayload:
            calls["n"] += 1
            return _typed_ruleset(99)

    fetcher: RulesFetcher = _Fetcher()
    with Client(fetcher=fetcher) as ks:
        assert ks.get_status().active_rules_version == 99
    assert calls["n"] == 1, "expected one fetcher call at init"


def test_negative_refresh_interval_rejected() -> None:
    with pytest.raises(ValueError, match="refresh_interval"):
        Client(rules=_typed_ruleset(1), refresh_interval=-1.0)


def test_refresh_interval_zero_disables_polling() -> None:
    with Client(rules=_typed_ruleset(1), refresh_interval=0) as ks:
        assert ks.get_status().polling is False
        assert ks.get_status().active_rules_version == 1


def test_unreachable_url_at_init_raises_load_error() -> None:
    from klswitch import LoadRulesError

    with pytest.raises((LoadRulesError, FetchError, OSError)):
        Client(token="ks_live_test", rules_url="http://127.0.0.1:1/")  # nothing listening


def test_custom_fetcher_path_constructs_cleanly() -> None:
    """Custom fetcher path bypasses the default HTTPFetcher — the operator owns
    their own transport, and no backend URL is required."""

    class _Fetcher:
        def fetch(self) -> RuleSetPayload:
            return _typed_ruleset(1)

    with Client(fetcher=_Fetcher()) as ks:
        assert ks.get_status().ready


class GzipRulesServer:
    """Rules endpoint that gzips its response, as the real backend does when the
    SDK sends ``Accept-Encoding: gzip`` (which the wrapper always does)."""

    def __init__(self, version: int = 7) -> None:
        outer_version = version

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib API name
                raw = json.dumps(_ruleset_dict(outer_version)).encode()
                body = gzip.compress(raw)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Encoding", "gzip")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, fmt: str, *args: Any) -> None:  # silence
                pass

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}/"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


def test_gzipped_ruleset_is_decoded() -> None:
    """Regression: the wrapper sets ``Accept-Encoding: gzip`` but ``urllib`` never
    decompresses transparently, so the fetcher must gunzip the body itself.

    Before the fix the raw deflate bytes reached the decoder, ``.decode("utf-8")``
    raised on the gzip magic byte, every fetch failed, and the client silently ran
    on inline/last-known-good rules — a published ruleset could never land.
    """
    server = GzipRulesServer(version=7)
    try:
        with Client(token="ks_live_test", rules_url=server.url) as ks:
            st = ks.get_status()
            assert st.last_load_ok, "gzipped ruleset must load"
            assert st.last_refresh_ok is True, f"refresh failed: {st.last_refresh_error}"
            assert st.active_rules_version == 7
            assert len(ks.evaluate(_eval_input()).matched) == 1
    finally:
        server.close()
