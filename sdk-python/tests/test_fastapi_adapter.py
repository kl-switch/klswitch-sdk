"""FastAPI adapter integration tests.

Drives a real FastAPI app via ``httpx.AsyncClient`` over the in-process
ASGI transport — no socket boot needed, but every request hits the real
Starlette dispatch + the SDK dependency.
"""

from __future__ import annotations

from typing import AsyncIterator

import httpx
import pytest
from fastapi import Depends, FastAPI, Request

from klswitch import AsyncClient
from klswitch.adapters.fastapi import klswitch_depends

from .conftest import make_demo_rules


@pytest.fixture()
async def fastapi_client() -> AsyncIterator[httpx.AsyncClient]:
    ks = AsyncClient(rules=make_demo_rules())
    await ks.start()
    ks_dep = klswitch_depends(ks)

    app = FastAPI()

    @app.post("/v1/payments/{id}")
    async def payment(id: str, request: Request, _dep=Depends(ks_dep)) -> dict:  # type: ignore[type-arg]
        result = request.state.klswitch
        return {"paymentId": id, "matched": [m.id for m in result.matched]}

    @app.get("/v1/users/{id}")
    async def user(id: str, request: Request, _dep=Depends(ks_dep)) -> dict:  # type: ignore[type-arg]
        result = request.state.klswitch
        return {"userId": id, "matched": [m.id for m in result.matched]}

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        try:
            yield client
        finally:
            await ks.aclose()


@pytest.mark.asyncio
async def test_small_payment_passes_through(fastapi_client: httpx.AsyncClient) -> None:
    res = await fastapi_client.post("/v1/payments/abc", json={"amount": 100})
    assert res.status_code == 200
    assert res.json() == {"paymentId": "abc", "matched": []}


@pytest.mark.asyncio
async def test_large_payment_blocks_with_429(fastapi_client: httpx.AsyncClient) -> None:
    res = await fastapi_client.post("/v1/payments/abc", json={"amount": 70000})
    assert res.status_code == 429
    detail = res.json()["detail"]
    assert detail["blocked"] is True
    assert detail["ruleId"] == "block_large_payment"


@pytest.mark.asyncio
async def test_restricted_region_blocks_with_451(fastapi_client: httpx.AsyncClient) -> None:
    res = await fastapi_client.post(
        "/v1/payments/abc", json={"amount": 10, "country": "KP"}
    )
    assert res.status_code == 451
    assert res.json()["detail"]["ruleId"] == "block_restricted_region"


@pytest.mark.asyncio
async def test_admin_user_flagged_not_blocked(fastapi_client: httpx.AsyncClient) -> None:
    res = await fastapi_client.get("/v1/users/42?role=admin")
    assert res.status_code == 200
    assert res.json() == {"userId": "42", "matched": ["flag_admin_access"]}


@pytest.mark.asyncio
async def test_impersonate_header_triggers_flag(fastapi_client: httpx.AsyncClient) -> None:
    res = await fastapi_client.get(
        "/v1/users/42", headers={"X-Impersonate": "ops-user"}
    )
    assert res.status_code == 200
    assert res.json()["matched"] == ["flag_admin_access"]


@pytest.mark.asyncio
async def test_custom_result_handler() -> None:
    ks = AsyncClient(rules=make_demo_rules())
    await ks.start()

    async def custom_handler(result, request):
        # Add a header for matches but never block.
        return result

    ks_dep = klswitch_depends(ks, result_handler=custom_handler)
    app = FastAPI()

    @app.post("/v1/payments/{id}")
    async def payment(id: str, request: Request, _dep=Depends(ks_dep)) -> dict:  # type: ignore[type-arg]
        result = request.state.klswitch
        return {"matched": [m.id for m in result.matched]}

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        try:
            res = await client.post("/v1/payments/abc", json={"amount": 70000})
            # Custom handler doesn't raise → route runs → 200.
            assert res.status_code == 200
            assert res.json()["matched"] == ["block_large_payment"]
        finally:
            await ks.aclose()


@pytest.mark.asyncio
async def test_repeated_query_param_takes_first_value() -> None:
    """Cross-language parity gate: repeated query keys collapse to the FIRST
    value, matching Flask / Java / Go. Starlette's default (last value) would
    silently disagree — see docs/sdk-wrapper-contract.md §request-translation.

    A rule `query.role == "admin"` must fire for `?role=admin&role=user`, not
    for `?role=user&role=admin` only.
    """
    from klswitch import Action, Rule, RuleSetPayload, Selector

    rs = RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="role_is_admin",
                name="role is admin",
                selector=Selector(kind="api", keys={"method": "GET", "path": "/q"}),
                enabled=True,
                priority=10,
                condition={
                    "all": [{"field": "query.role", "operator": "eq", "value": "admin"}]
                },
                action=Action(type="flag", payload={}),
            ),
        ],
    )
    ks = AsyncClient(rules=rs)
    await ks.start()
    ks_dep = klswitch_depends(ks)
    app = FastAPI()

    @app.get("/q")
    async def q(request: Request, _dep=Depends(ks_dep)) -> dict:  # type: ignore[type-arg]
        return {"matched": [m.id for m in request.state.klswitch.matched]}

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        try:
            # admin then user → first ("admin") wins → rule fires.
            r1 = await client.get("/q?role=admin&role=user")
            assert r1.status_code == 200
            assert r1.json()["matched"] == ["role_is_admin"]

            # user then admin → first ("user") wins → rule does not fire.
            r2 = await client.get("/q?role=user&role=admin")
            assert r2.status_code == 200
            assert r2.json()["matched"] == []
        finally:
            await ks.aclose()


@pytest.mark.asyncio
async def test_route_handler_can_still_read_body() -> None:
    """The middleware reads the body for fact extraction. Starlette caches
    the read so the route handler still sees the original bytes.
    """
    ks = AsyncClient(rules=make_demo_rules())
    await ks.start()
    ks_dep = klswitch_depends(ks)
    app = FastAPI()

    @app.post("/v1/echo")
    async def echo(request: Request, _dep=Depends(ks_dep)) -> dict:  # type: ignore[type-arg]
        return {"received": await request.json()}

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        try:
            res = await client.post("/v1/echo", json={"hello": "world"})
            assert res.status_code == 200
            assert res.json() == {"received": {"hello": "world"}}
        finally:
            await ks.aclose()
