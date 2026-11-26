"""The manifest must reflect routes registered *after* the client is built.

That ordering is not exotic — the shapes the docs recommend force it. FastAPI's
app-level dependency needs the client before ``FastAPI(...)``, and Flask's
``guard_all`` needs it before ``init_app``, so in both cases routes are declared
after the client exists. The construction-time push therefore races route
registration, and measured on FastAPI it lost: it saw only the framework's
built-in routes and missed every user route.
"""
from __future__ import annotations

import json
import threading
import time
from typing import Any, Mapping

import pytest

from klswitch import Client, ManifestRoute, ManifestSource, build_evaluation_input
from klswitch import _manifest as _manifest_mod

from .conftest import make_demo_rules


class _Recorder:
    """Captures each ``_post_manifest`` call; mirrors test_manifest.py."""

    def __init__(self) -> None:
        self.pushes: list[list[str]] = []
        self.event = threading.Event()

    def __call__(self, url: str, headers: Mapping[str, str], body: bytes, timeout: float) -> int:
        payload: dict[str, Any] = json.loads(body)
        self.pushes.append([f"{r['method']} {r['path']}" for r in payload.get("routes", [])])
        self.event.set()
        return 204


def _client_with_late_routes() -> tuple[Client, list[ManifestRoute]]:
    """Build the client first, then register routes — the forced ordering."""
    routes: list[ManifestRoute] = []
    ks = Client(
        rules=make_demo_rules(),
        token="kls_test",
        base_url="http://localhost:1",
        manifest=ManifestSource(framework="test", collect=lambda: list(routes)),
    )
    routes.append(ManifestRoute(method="POST", path="/v1/orders/:id"))
    routes.append(ManifestRoute(method="GET", path="/v1/users/:id"))
    return ks, routes


def test_first_evaluation_pushes_routes_registered_after_construction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    ks, _ = _client_with_late_routes()
    try:
        ks.evaluate(build_evaluation_input(method="POST", path="/v1/orders/:id", body={"amount": 1}))
        deadline = time.time() + 3
        while time.time() < deadline and not any(rec.pushes):
            time.sleep(0.02)
    finally:
        ks.close()

    carried = [p for p in rec.pushes if p]
    assert carried, "no manifest push carried any route — the race is still open"
    assert sorted(carried[-1]) == ["GET /v1/users/:id", "POST /v1/orders/:id"]


def test_recheck_runs_only_once(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = _Recorder()
    monkeypatch.setattr(_manifest_mod, "_post_manifest", rec)
    ks, _ = _client_with_late_routes()
    try:
        inp = build_evaluation_input(method="POST", path="/v1/orders/:id", body={"amount": 1})
        for _ in range(5):
            ks.evaluate(inp)
        time.sleep(0.3)
    finally:
        ks.close()

    # Hash dedupe makes repeats cheap regardless, but the guard should stop us
    # spawning a thread per request.
    assert len(rec.pushes) <= 2, f"expected at most init + one re-check, got {len(rec.pushes)}"
