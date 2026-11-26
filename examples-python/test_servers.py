"""End-to-end tests for the Python example servers.

Boots each demo via its test transport (Flask's WSGI ``test_client`` for the
Flask example, httpx's ``ASGITransport`` for the FastAPI example) and replays
the same curl invocations the README documents. Same status codes + same
matched rule ids across both servers — and across the Node / Java / Go demos.
"""

from __future__ import annotations

from typing import AsyncIterator, Iterator

import flask
import httpx
import pytest

from klswitch import AsyncClient, Client

from demo_rules import DEMO_RULES
from fastapi_server import build_app as build_fastapi
from flask_server import build_app as build_flask


# ────────────────────────────────────────────────────────────────────────────
# Flask
# ────────────────────────────────────────────────────────────────────────────


@pytest.fixture()
def flask_client() -> Iterator[flask.testing.FlaskClient]:
    ks = Client(rules=DEMO_RULES)
    app = build_flask(ks)
    try:
        yield app.test_client()
    finally:
        ks.close()


def test_flask_small_payment_passes(flask_client: flask.testing.FlaskClient) -> None:
    r = flask_client.post("/v1/payments/abc", json={"amount": 100})
    assert r.status_code == 200
    body = r.get_json()
    assert body == {"ok": True, "matched": [], "paymentId": "abc"}


def test_flask_large_payment_blocks_429(flask_client: flask.testing.FlaskClient) -> None:
    r = flask_client.post("/v1/payments/abc", json={"amount": 70000})
    assert r.status_code == 429
    body = r.get_json()
    assert body["blocked"] is True and body["ruleId"] == "block_large_payment"


def test_flask_restricted_region_blocks_451(
    flask_client: flask.testing.FlaskClient,
) -> None:
    r = flask_client.post(
        "/v1/payments/abc", json={"amount": 10, "country": "KP"}
    )
    assert r.status_code == 451
    assert r.get_json()["ruleId"] == "block_restricted_region"


def test_flask_admin_query_flags(flask_client: flask.testing.FlaskClient) -> None:
    r = flask_client.get("/v1/users/42?role=admin")
    assert r.status_code == 200
    assert r.get_json() == {
        "ok": True,
        "matched": [{"id": "flag_admin_access", "action": "flag"}],
        "userId": "42",
    }


def test_flask_admin_header_flags(flask_client: flask.testing.FlaskClient) -> None:
    r = flask_client.get("/v1/users/42", headers={"X-Impersonate": "ops-user"})
    assert r.status_code == 200
    assert r.get_json()["matched"] == [{"id": "flag_admin_access", "action": "flag"}]


def test_flask_health_includes_status(flask_client: flask.testing.FlaskClient) -> None:
    r = flask_client.get("/health")
    assert r.status_code == 200
    body = r.get_json()
    assert body["ok"] is True
    assert body["status"]["initialized"] is True
    assert body["status"]["ruleCount"] == 3


# ────────────────────────────────────────────────────────────────────────────
# FastAPI
# ────────────────────────────────────────────────────────────────────────────


@pytest.fixture()
async def fastapi_client() -> AsyncIterator[httpx.AsyncClient]:
    ks = AsyncClient(rules=DEMO_RULES)
    app = build_fastapi(ks)
    transport = httpx.ASGITransport(app=app)
    # The lifespan context will start/aclose `ks` for us.
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        async with c.stream("GET", "/health"):
            pass  # trigger lifespan startup via the first request
        yield c


@pytest.mark.asyncio
async def test_fastapi_small_payment_passes(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.post("/v1/payments/abc", json={"amount": 100})
    assert r.status_code == 200
    assert r.json() == {"ok": True, "matched": [], "paymentId": "abc"}


@pytest.mark.asyncio
async def test_fastapi_large_payment_blocks_429(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.post("/v1/payments/abc", json={"amount": 70000})
    assert r.status_code == 429
    body = r.json()
    # Canonical shape — same as Node/Java/Go/Flask (NOT wrapped in `detail`).
    assert body["blocked"] is True
    assert body["ruleId"] == "block_large_payment"


@pytest.mark.asyncio
async def test_fastapi_restricted_region_blocks_451(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.post(
        "/v1/payments/abc", json={"amount": 10, "country": "KP"}
    )
    assert r.status_code == 451
    assert r.json()["ruleId"] == "block_restricted_region"


@pytest.mark.asyncio
async def test_fastapi_admin_query_flags(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.get("/v1/users/42?role=admin")
    assert r.status_code == 200
    assert r.json() == {
        "ok": True,
        "matched": [{"id": "flag_admin_access", "action": "flag"}],
        "userId": "42",
    }


@pytest.mark.asyncio
async def test_fastapi_admin_header_flags(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.get(
        "/v1/users/42", headers={"X-Impersonate": "ops-user"}
    )
    assert r.status_code == 200
    assert r.json()["matched"] == [{"id": "flag_admin_access", "action": "flag"}]


@pytest.mark.asyncio
async def test_fastapi_health_includes_status(
    fastapi_client: httpx.AsyncClient,
) -> None:
    r = await fastapi_client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["status"]["ruleCount"] == 3
