"""ManifestReporter unit tests + Client/AsyncClient integration tests.

Parallels ``sdk-node/test/manifest.test.ts``. The reporter's HTTP call is
the module-level ``klswitch._manifest._post_manifest`` — we monkey-patch
it with a recorder so no socket is opened.
"""

from __future__ import annotations

import asyncio
import json
import threading
import time
from typing import Any, Mapping

import pytest

from klswitch import (
    AsyncClient,
    Client,
    ManifestField,
    ManifestReporter,
    ManifestRoute,
    ManifestRouteFields,
    ManifestSource,
    RuleSetPayload,
)
from klswitch import _manifest as _manifest_mod
from klswitch.logger import NullLogger

from .conftest import make_ruleset


# ─── Helpers ─────────────────────────────────────────────────────────────


class _Recorder:
    """Captures every ``_post_manifest`` call and replies with a configurable status."""

    def __init__(self, status: int = 204) -> None:
        self.status = status
        self.calls: list[dict[str, Any]] = []
        self.event = threading.Event()

    def __call__(
        self,
        url: str,
        headers: Mapping[str, str],
        body: bytes,
        timeout: float,
    ) -> int:
        self.calls.append(
            {
                "url": url,
                "headers": dict(headers),
                "body": json.loads(body.decode("utf-8")),
                "timeout": timeout,
            }
        )
        self.event.set()
        return self.status


def _source(routes: list[ManifestRoute]) -> ManifestSource:
    return ManifestSource(framework="fastify", collect=lambda: routes)


def _make_reporter(
    *,
    source: ManifestSource,
    url: str = "http://api.test/v1/manifest",
    instance_key: str = "pod-1",
    logger=None,
) -> ManifestReporter:
    return ManifestReporter(
        url=url,
        headers={"Authorization": "Bearer t", "X-Instance-Key": instance_key},
        source=source,
        instance_key=instance_key,
        logger=logger or NullLogger(),
    )


# ─── Reporter behaviour ──────────────────────────────────────────────────


def test_report_posts_normalised_routes(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source(
            [
                # Out-of-order, mixed-case methods — the reporter normalises.
                ManifestRoute(method="get",  path="/v1/healthz"),
                ManifestRoute(method="POST", path="/v1/payments"),
                ManifestRoute(method="get",  path="/v1/orders/:id"),
            ]
        ),
    )
    assert rep.report() is True
    assert len(rec.calls) == 1
    body = rec.calls[0]["body"]
    assert body["framework"] == "fastify"
    assert body["instanceKey"] == "pod-1"
    assert [f'{r["method"]} {r["path"]}' for r in body["routes"]] == [
        "GET /v1/healthz",
        "GET /v1/orders/:id",
        "POST /v1/payments",
    ]
    assert len(body["hash"]) == 64  # sha256 hex


def test_report_dedup_by_hash(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source([ManifestRoute(method="POST", path="/v1/payments")])
    )
    assert rep.report() is True
    assert len(rec.calls) == 1
    # Same source on a re-report → no extra POST.
    assert rep.report() is True
    assert len(rec.calls) == 1


def test_report_pushes_again_when_routes_change(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    routes: list[ManifestRoute] = [ManifestRoute(method="POST", path="/v1/payments")]
    rep = ManifestReporter(
        url="http://api.test/v1/manifest",
        headers={"Authorization": "Bearer t"},
        source=ManifestSource(framework="fastify", collect=lambda: list(routes)),
        instance_key="pod-1",
    )
    assert rep.report() is True
    assert len(rec.calls) == 1

    # Operator added a route at runtime.
    routes.append(ManifestRoute(method="POST", path="/v1/refunds"))
    assert rep.report() is True
    assert len(rec.calls) == 2
    assert len(rec.calls[1]["body"]["routes"]) == 2


def test_non_2xx_response_counts_as_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder(status=500)
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source([ManifestRoute(method="POST", path="/v1/payments")])
    )
    assert rep.report() is False
    st = rep.status()
    assert st.last_push_ok is False
    assert st.last_push_error and "status 500" in st.last_push_error
    assert st.pushes_failed == 1
    assert st.pushes_succeeded == 0


def test_thrown_source_counts_as_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)

    def _explode() -> list[ManifestRoute]:
        raise RuntimeError("walk boom")

    rep = _make_reporter(source=ManifestSource(framework="fastify", collect=_explode))
    assert rep.report() is False
    st = rep.status()
    assert st.pushes_failed == 1
    assert "walk boom" in (st.last_push_error or "")
    # Source threw → no POST ever happened.
    assert len(rec.calls) == 0


def test_status_snapshots_counters(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source([ManifestRoute(method="POST", path="/v1/payments")])
    )
    st = rep.status()
    assert st.enabled is True
    assert st.last_reported_at is None
    assert st.push_attempts == 0

    rep.report()
    st = rep.status()
    assert st.push_attempts == 1
    assert st.pushes_succeeded == 1
    assert st.last_push_ok is True
    assert st.route_count == 1
    assert st.last_reported_at is not None


def test_stop_makes_report_a_noop(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source([ManifestRoute(method="POST", path="/v1/payments")])
    )
    rep.stop()
    assert rep.report() is False
    assert len(rec.calls) == 0


def test_route_fields_serialise_through_to_wire(monkeypatch: pytest.MonkeyPatch) -> None:
    """Best-effort field shape rides along on each route when present."""
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    rep = _make_reporter(
        source=_source(
            [
                ManifestRoute(
                    method="POST",
                    path="/v1/payments",
                    handler="createPayment",
                    fields=ManifestRouteFields(
                        body=[ManifestField(name="amount", type="number")],
                    ),
                )
            ]
        ),
    )
    rep.report()
    route = rec.calls[0]["body"]["routes"][0]
    assert route["handler"] == "createPayment"
    assert route["fields"]["body"] == [{"name": "amount", "type": "number"}]
    assert route["fields"]["query"] == []


# ─── Client integration ──────────────────────────────────────────────────


class _StaticFetcher:
    """Stub RulesFetcher used so Client init doesn't try to reach the network
    for /v1/ruleset. Returns a fixed ruleset and never raises.
    """

    def __init__(self, rules: RuleSetPayload) -> None:
        self._rules = rules

    def fetch(self) -> RuleSetPayload:
        return self._rules


def test_client_pushes_manifest_at_init(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)

    ks = Client(
        token="ks_live_test",
        fetcher=_StaticFetcher(make_ruleset()),
        manifest=ManifestSource(
            framework="fastify",
            collect=lambda: [ManifestRoute(method="POST", path="/v1/payments")],
        ),
        refresh_interval=0,
    )
    try:
        # Initial push runs on a daemon thread — wait briefly.
        assert rec.event.wait(timeout=2.0), "manifest POST never fired"
        st = ks.manifest_status()
        assert st.enabled is True
        assert st.framework == "fastify"
        assert st.route_count == 1
        assert rec.calls[0]["url"].endswith("/v1/manifest")
        assert rec.calls[0]["headers"].get("Authorization") == "Bearer ks_live_test"
    finally:
        ks.close()


def test_client_manifest_status_disabled_without_source() -> None:
    with Client(rules=make_ruleset()) as ks:
        st = ks.manifest_status()
        assert st.enabled is False
        assert st.framework is None


def test_client_report_manifest_false_opts_out(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    ks = Client(
        token="ks_live_test",
        fetcher=_StaticFetcher(make_ruleset()),
        manifest=ManifestSource(
            framework="fastify",
            collect=lambda: [ManifestRoute(method="POST", path="/v1/payments")],
        ),
        report_manifest=False,
        refresh_interval=0,
    )
    try:
        # Give any rogue push thread a beat to (not) fire.
        time.sleep(0.1)
        assert ks.manifest_status().enabled is False
        assert len(rec.calls) == 0
    finally:
        ks.close()


def test_client_report_manifest_called_again_is_noop(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    ks = Client(
        token="ks_live_test",
        fetcher=_StaticFetcher(make_ruleset()),
        manifest=ManifestSource(
            framework="fastify",
            collect=lambda: [ManifestRoute(method="POST", path="/v1/payments")],
        ),
        refresh_interval=0,
    )
    try:
        assert rec.event.wait(timeout=2.0)
        before = len(rec.calls)
        ks.report_manifest()
        # Same source → hash dedup means no extra POST.
        assert len(rec.calls) == before
    finally:
        ks.close()


# ─── AsyncClient integration ─────────────────────────────────────────────


class _StaticAsyncFetcher:
    def __init__(self, rules: RuleSetPayload) -> None:
        self._rules = rules

    async def fetch(self) -> RuleSetPayload:
        return self._rules


@pytest.mark.asyncio
async def test_async_client_pushes_manifest_at_start(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)

    ks = AsyncClient(
        token="ks_live_test",
        fetcher=_StaticAsyncFetcher(make_ruleset()),
        manifest=ManifestSource(
            framework="fastify",
            collect=lambda: [ManifestRoute(method="POST", path="/v1/payments")],
        ),
        refresh_interval=0,
    )
    try:
        await ks.start()
        # Initial push is asyncio.to_thread under create_task — give it time.
        for _ in range(50):
            if rec.calls:
                break
            await asyncio.sleep(0.02)
        assert rec.calls, "manifest POST never fired"
        st = ks.manifest_status()
        assert st.enabled is True
        assert st.framework == "fastify"
        assert st.route_count == 1
    finally:
        await ks.aclose()


@pytest.mark.asyncio
async def test_async_client_manifest_status_disabled_without_source() -> None:
    async with AsyncClient(rules=make_ruleset()) as ks:
        st = ks.manifest_status()
        assert st.enabled is False


@pytest.mark.asyncio
async def test_async_client_report_manifest_method(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)

    routes: list[ManifestRoute] = [ManifestRoute(method="POST", path="/v1/payments")]
    ks = AsyncClient(
        token="ks_live_test",
        fetcher=_StaticAsyncFetcher(make_ruleset()),
        manifest=ManifestSource(framework="fastify", collect=lambda: list(routes)),
        refresh_interval=0,
    )
    try:
        await ks.start()
        # Wait for the init push.
        for _ in range(50):
            if rec.calls:
                break
            await asyncio.sleep(0.02)
        before = len(rec.calls)
        # Mutate routes — explicit report_manifest call should fire a new POST.
        routes.append(ManifestRoute(method="POST", path="/v1/refunds"))
        ok = await ks.report_manifest()
        assert ok is True
        assert len(rec.calls) == before + 1
        assert len(rec.calls[-1]["body"]["routes"]) == 2
    finally:
        await ks.aclose()


def test_canonicalise_path_normalises_the_dialect() -> None:
    """Five frameworks published ``/api/items/{id}`` and eight ``/api/items/:id``.

    A rule written from the console's route picker then matched nothing, because
    evaluation normalises to the colon form. Doing it in the reporter means a
    hand-written custom source cannot get it wrong either.
    """
    from klswitch._manifest import canonicalise_path

    assert canonicalise_path("/api/items/{id}") == "/api/items/:id"
    assert (
        canonicalise_path("/api/items/{id}/reviews/{reviewId}")
        == "/api/items/:id/reviews/:reviewId"
    )
    # A converter spec is a routing constraint, not part of the path.
    assert canonicalise_path("/api/items/{id:int}") == "/api/items/:id"
    assert canonicalise_path("/api/items/:id") == "/api/items/:id"
    assert canonicalise_path("/health") == "/health"
