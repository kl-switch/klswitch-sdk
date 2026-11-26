"""Client lifecycle: init / evaluate / status / close."""

from __future__ import annotations

import threading

import pytest

from klswitch import (
    Action,
    Client,
    ClosedClientError,
    EvaluationInput,
    Facts,
    LoadRulesError,
    Rule,
    RuleSetPayload,
    Selector,
)

from .conftest import make_input_match, make_ruleset


def _eval_input() -> EvaluationInput:
    return EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/p"}),
        facts=make_input_match(),
    )


def test_init_evaluate_status_close() -> None:
    with Client(rules=make_ruleset()) as ks:
        status = ks.get_status()
        assert status.initialized
        assert status.last_load_ok
        assert status.active_rules_version == 12
        assert status.rule_count == 1

        result = ks.evaluate(_eval_input())
        assert result.ok
        assert len(result.matched) == 1
        assert result.matched[0].id == "block"
        assert result.matched[0].action.type == "block"
        assert result.matched[0].action.payload == {"code": "RULE_BLOCKED"}

    # After context exit: closed-state status is safe, evaluate raises.
    status_after = ks.get_status()
    assert not status_after.initialized


def test_init_fails_clearly_on_invalid_rules() -> None:
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
    with pytest.raises(LoadRulesError) as ei:
        Client(rules=bad)
    assert "failed to load" in str(ei.value)
    assert ei.value.result is not None


def test_init_fails_with_no_rules_and_no_token() -> None:
    # No inline rules + no token + no custom fetcher → token is required.
    with pytest.raises(ValueError, match="token is required"):
        Client()


def test_instance_id_exposed_after_init() -> None:
    with Client(rules=make_ruleset(), instance_id="pod-7f4a") as ks:
        assert ks.instance_id == "pod-7f4a"


def test_urls_and_sdk_version_exposed() -> None:
    with Client(rules=make_ruleset()) as ks:
        assert ks.urls.rules == "https://api.klswitch.dev/v1/ruleset"
        assert ks.urls.decisions == "https://api.klswitch.dev/v1/analytics"
        assert ks.sdk_version  # non-empty


def test_base_url_overrides_every_endpoint() -> None:
    with Client(rules=make_ruleset(), base_url="http://self-hosted:8080") as ks:
        assert ks.urls.rules == "http://self-hosted:8080/v1/ruleset"
        assert ks.urls.decisions == "http://self-hosted:8080/v1/analytics"


def test_evaluate_after_close_raises() -> None:
    ks = Client(rules=make_ruleset())
    ks.close()
    with pytest.raises(ClosedClientError):
        ks.evaluate(_eval_input())


def test_close_is_idempotent() -> None:
    ks = Client(rules=make_ruleset())
    ks.close()
    ks.close()  # must not raise


def test_context_manager_closes_on_exception() -> None:
    """An exception raised inside ``with`` must still call ``close()``.

    Python guarantees ``__exit__`` runs on exit, but a regression test pins
    down the SDK's reliance on the language guarantee.
    """
    raised = False
    ks_ref: Client | None = None
    try:
        with Client(rules=make_ruleset()) as ks:
            ks_ref = ks
            raise RuntimeError("boom from inside `with`")
    except RuntimeError as e:
        raised = str(e) == "boom from inside `with`"
    assert raised, "exception must propagate out of `with`"
    assert ks_ref is not None
    # Closed: evaluate raises, status reports not initialised.
    with pytest.raises(ClosedClientError):
        ks_ref.evaluate(_eval_input())
    assert not ks_ref.get_status().initialized


def test_repr_reflects_state() -> None:
    ks = Client(rules=make_ruleset())
    r_open = repr(ks)
    assert "klswitch.Client" in r_open
    assert "closed=False" in r_open
    assert "active_rules_version=12" in r_open
    ks.close()
    assert "closed=True" in repr(ks)


def test_analytics_records_one_event_per_evaluate() -> None:
    """Every evaluate call records exactly one event with the right shape."""
    import time

    from klswitch import AnalyticsEvent, AnalyticsOptions

    mu = threading.Lock()
    events: list[AnalyticsEvent] = []

    def sink(batch):
        with mu:
            events.extend(batch)

    with Client(
        rules=make_ruleset(),
        analytics=AnalyticsOptions(sink=sink, batch_size=1, flush_interval=0.05),
    ) as ks:
        for _ in range(4):
            ks.evaluate(_eval_input())

        deadline = time.time() + 2.0
        while time.time() < deadline:
            with mu:
                if len(events) == 4:
                    break
            time.sleep(0.02)

    with mu:
        assert len(events) == 4, f"expected 4 events, got {len(events)}"
        e = events[0]
        # New contract shape: matched is a list of MatchedAction records.
        assert len(e.matched) == 1
        assert e.matched[0].rule_id == "block"
        assert e.matched[0].action_type == "block"
        assert e.evaluated == 1
        assert e.rules_version == 12
        assert e.latency_bucket  # any enum value
        # Request shape is extracted from the input's selector.
        assert e.request.method == "POST"
        assert e.request.path == "/p"
        assert e.event_type == "evaluation"
        assert e.occurred_at  # non-empty ISO timestamp


def test_analytics_sink_error_does_not_break_evaluate() -> None:
    """A throwing sink is logged + dropped; evaluate keeps returning real results."""
    from klswitch import AnalyticsOptions

    def sink(_batch):
        raise RuntimeError("sink boom")

    with Client(
        rules=make_ruleset(),
        analytics=AnalyticsOptions(sink=sink, batch_size=1, flush_interval=0.05),
    ) as ks:
        r = ks.evaluate(_eval_input())
        assert r.ok and len(r.matched) == 1


def test_sync_client_rejects_async_sink() -> None:
    """The sync Client must refuse a coroutine sink at construction time so
    the user doesn't silently spawn un-awaited coroutines.
    """
    from klswitch import AnalyticsOptions

    async def async_sink(_batch) -> None:
        pass

    with pytest.raises(ValueError, match="AsyncClient for coroutine sinks"):
        Client(
            rules=make_ruleset(),
            analytics=AnalyticsOptions(sink=async_sink, batch_size=1, flush_interval=0.05),
        )


def test_concurrent_evaluate_is_safe() -> None:
    """A shared Client hit by many threads must serialise engine access and
    never corrupt the single WASM instance.
    """
    inp = _eval_input()
    threads = 8
    per_thread = 250
    errors: list[BaseException] = []
    errors_lock = threading.Lock()

    with Client(rules=make_ruleset()) as ks:

        def worker() -> None:
            try:
                for _ in range(per_thread):
                    r = ks.evaluate(inp)
                    if not r.ok or len(r.matched) != 1 or r.matched[0].id != "block":
                        raise AssertionError(f"unexpected result: {r}")
            except BaseException as e:
                with errors_lock:
                    errors.append(e)

        ts = [threading.Thread(target=worker) for _ in range(threads)]
        for t in ts:
            t.start()
        for t in ts:
            t.join(timeout=30)
            assert not t.is_alive(), "thread should finish"

    assert not errors, f"concurrent evaluate errors: {errors}"
