"""FastAPI demo server — Python async counterpart to ``examples-python/flask_server.py``.

Wires :class:`klswitch.AsyncClient` to a FastAPI app via the SDK's
:func:`klswitch_depends`. To make the *block* response shape match the
Node / Java / Go / Flask demos byte-for-byte, the example registers a
custom :class:`KLSwitchBlock` exception + handler — FastAPI's default
``HTTPException`` would wrap the body in ``{"detail": ...}``, which is
idiomatic but inconsistent with the other example servers. The pattern
is documented in the README so users can decide whether they want it.

Run::

    uvicorn fastapi_server:app                # listens on :8087 if configured
    PORT=9000 uvicorn fastapi_server:app --port 9000

For test / scripted use::

    python fastapi_server.py
"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse

import os

from klswitch import AnalyticsOptions, AsyncClient, ManifestRoute, ManifestSource
from klswitch.adapters.fastapi import klswitch_depends
from klswitch.types import EvaluationResult

from demo_rules import DEMO_RULES


class KLSwitchBlock(Exception):
    """Raised by our custom result_handler when a rule blocks the request.

    A dedicated exception class lets us register one handler that produces the
    canonical ``{"blocked": true, "ruleId": ..., "action": ...}`` body the
    other example servers use, without FastAPI wrapping it in ``detail``.
    """

    def __init__(self, status_code: int, body: dict[str, Any]) -> None:
        self.status_code = status_code
        self.body = body


async def _block_handler(
    result: EvaluationResult, request: Request
) -> EvaluationResult:
    for m in result.matched:
        if m.action.type == "block":
            status = 403
            if m.action.payload:
                sc = m.action.payload.get("statusCode")
                if isinstance(sc, int):
                    status = sc
            raise KLSwitchBlock(
                status_code=status,
                body={
                    "blocked": True,
                    "ruleId": m.id,
                    "action": {"type": m.action.type, "payload": m.action.payload},
                },
            )
    return result


def build_app(client: AsyncClient) -> FastAPI:
    """Construct the FastAPI app. Split out so tests can wire their own client."""

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        await client.start()
        try:
            yield
        finally:
            await client.aclose()

    app = FastAPI(lifespan=lifespan)
    ks_dep = klswitch_depends(client, result_handler=_block_handler)

    @app.exception_handler(KLSwitchBlock)
    async def _on_block(request: Request, exc: KLSwitchBlock) -> JSONResponse:
        return JSONResponse(content=exc.body, status_code=exc.status_code)

    @app.post("/v1/payments/{id}")
    async def payment(
        id: str, request: Request, _dep: EvaluationResult = Depends(ks_dep)
    ) -> dict[str, Any]:
        return _ok(request, {"paymentId": id})

    @app.get("/v1/users/{id}")
    async def user(
        id: str, request: Request, _dep: EvaluationResult = Depends(ks_dep)
    ) -> dict[str, Any]:
        return _ok(request, {"userId": id})

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {"ok": True, "status": await _status(client)}

    return app


# Module-level `app` so ``uvicorn fastapi_server:app`` works.
# When KS_TOKEN is set, point at a real backend (klswitch) and emit analytics; the
# per-(service,env) API key lets the backend derive service + env.
if os.environ.get("KS_TOKEN"):
    _default_client = AsyncClient(
        rules=DEMO_RULES,
        token=os.environ["KS_TOKEN"],
        base_url=os.environ.get("KS_BASE_URL", "http://localhost:40010"),
        analytics=AnalyticsOptions(batch_size=1, flush_interval=0.5),
        # Explicit API surface → powers the console's APIs tab (auto-discovery from FastAPI's
        # app.routes is available via fastapi_manifest_source, kept explicit here for simplicity).
        manifest=ManifestSource(
            framework="fastapi",
            collect=lambda: [
                ManifestRoute(method="POST", path="/v1/payments/:id"),
                ManifestRoute(method="GET", path="/v1/users/:id"),
            ],
        ),
    )
else:
    _default_client = AsyncClient(rules=DEMO_RULES)
app = build_app(_default_client)


# ────────────────────────────────────────────────────────────────────────────
# response shape helpers — match the Node / Java / Go / Flask demos
# ────────────────────────────────────────────────────────────────────────────


def _ok(request: Request, extra: dict[str, Any]) -> dict[str, Any]:
    result: EvaluationResult | None = getattr(request.state, "klswitch", None)
    matched = (
        [{"id": m.id, "action": m.action.type} for m in result.matched]
        if result is not None
        else []
    )
    return {"ok": True, "matched": matched, **extra}


async def _status(client: AsyncClient) -> dict[str, Any]:
    s = await client.get_status()
    return {
        "initialized": s.initialized,
        "activeRulesVersion": s.active_rules_version,
        "ruleCount": s.rule_count,
        "lastLoadOk": s.last_load_ok,
        "lastRefreshOk": s.last_refresh_ok,
        "lastRefreshError": s.last_refresh_error,
    }


def main() -> None:
    import uvicorn

    port = int(os.environ.get("PORT", "8087"))
    uvicorn.run(
        "fastapi_server:app",
        host="0.0.0.0",
        port=port,
        log_level="info",
        limit_concurrency=None,
        # Bound chunked uploads at the ASGI layer — the SDK's Content-Length
        # check can't see them.
        h11_max_incomplete_event_size=1 << 20,
    )


if __name__ == "__main__":
    main()
