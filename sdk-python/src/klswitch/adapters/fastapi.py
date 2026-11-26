"""FastAPI / Starlette adapter — async, ASGI.

Exposes a dependency factory: pass an :class:`AsyncClient` and you get back a
callable suitable for FastAPI's ``Depends``. The dependency reads the request,
runs the evaluation, applies the configured result handler (default
short-circuits on ``block`` with the rule's status code), and returns the
:class:`EvaluationResult` so the route handler can read it.

Path templates come from the matched ``request.scope["route"].path`` (e.g.
``"/v1/payments/{id}"``) and are converted to the engine's ``:name`` syntax.
Body extraction is bounded (default 1 MiB) for the same DoS reason as the
Flask + Go adapters.

**Production note — chunked uploads:** the SDK's body cap uses
``Content-Length`` to skip oversized bodies before calling ``request.body()``.
Requests using ``Transfer-Encoding: chunked`` have no Content-Length, so the
whole body is read into memory before the post-read length check kicks in.
Use your ASGI server's body-size limit (uvicorn ``--limit-request-body``,
hypercorn ``--max-body-size``) — or Starlette's ``MAX_REQUEST_SIZE``
middleware — to bound the read at the framework layer for chunked clients.
"""

from __future__ import annotations

import json
from typing import Any, Awaitable, Callable, Optional

from fastapi import HTTPException, Request

from .._request_context import build_evaluation_input, fastapi_template_to_engine_path
from ..async_client import AsyncClient
from ..types import EvaluationInput, EvaluationResult, MatchedRule


DEFAULT_MAX_BODY_BYTES = 1 << 20  # 1 MiB

# A ResultHandler may raise HTTPException to short-circuit, or return the
# (possibly modified) result to let the route handler proceed. The result
# is also stored on `request.state.klswitch` so handlers can inspect it.
FastAPIResultHandler = Callable[
    [EvaluationResult, Request],
    Awaitable[Optional[EvaluationResult]],
]
FastAPIErrorHandler = Callable[[Exception, Request], Awaitable[None]]


REQUEST_STATE_ATTR = "klswitch"


async def default_result_handler(
    result: EvaluationResult, request: Request
) -> EvaluationResult:
    """Default policy: short-circuit on the first ``block`` match via HTTPException."""
    for m in result.matched:
        if m.action.type == "block":
            status, detail = _block_response(m)
            raise HTTPException(status_code=status, detail=detail)
    return result


async def default_error_handler(err: Exception, request: Request) -> None:
    """Default policy: 500 with the evaluation-error message."""
    raise HTTPException(
        status_code=500,
        detail={"error": "klswitch evaluation failed", "detail": str(err)},
    )


def klswitch_depends(
    client: AsyncClient,
    *,
    result_handler: FastAPIResultHandler | None = None,
    error_handler: FastAPIErrorHandler | None = None,
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES,
) -> Callable[[Request], Awaitable[EvaluationResult]]:
    """Build a FastAPI dependency that guards a route.

    ``start()`` returns ``None``, so call it as a statement — binding its result
    would leave you holding ``None`` rather than the client.

    Attach it to the whole app so every route is covered::

        ks = klswitch.AsyncClient(rules=...)

        @asynccontextmanager
        async def lifespan(app: FastAPI):
            await ks.start()
            yield
            await ks.aclose()

        app = FastAPI(dependencies=[Depends(klswitch_depends(ks))], lifespan=lifespan)

    Or scope it to one route, taking the result as a parameter when you want to
    read it::

        guard = klswitch_depends(ks)

        @app.post("/v1/payments/{id}")
        async def payment(id: str, result = Depends(guard)):
            return {"paymentId": id, "matched": [m.id for m in result.matched]}

    The default ``result_handler`` raises ``HTTPException`` on a matched
    ``block`` action; otherwise the result is returned to the route and
    stored on ``request.state.klswitch``.
    """
    rh = result_handler or default_result_handler
    eh = error_handler or default_error_handler

    async def dependency(request: Request) -> EvaluationResult:
        try:
            ev_input = await _build_input(request, max_body_bytes)
            result = await client.evaluate(ev_input)
        except HTTPException:
            raise
        except Exception as err:  # noqa: BLE001 - host-side error
            await eh(err, request)
            # If the error handler didn't raise, surface a generic 500 anyway
            # so downstream doesn't see a None.
            raise HTTPException(status_code=500, detail="klswitch evaluation failed")

        # Store on request.state so downstream handlers can inspect it
        # regardless of what the result_handler chooses to do.
        setattr(request.state, REQUEST_STATE_ATTR, result)
        modified = await rh(result, request)
        return modified if modified is not None else result

    return dependency


# ───── internals ─────────────────────────────────────────────────────────────


async def _build_input(request: Request, max_body_bytes: int) -> EvaluationInput:
    # Match the engine's templated path when one is known.
    template: str
    route = request.scope.get("route")
    if route is not None and getattr(route, "path", None):
        template = fastapi_template_to_engine_path(route.path)
    else:
        template = request.url.path

    return build_evaluation_input(
        method=request.method,
        path=template,
        headers=dict(request.headers),
        # First-value-wins for repeated keys (?role=admin&role=user → "admin"),
        # matching Flask / Java / Go. Starlette's QueryParams.items() returns
        # the *last* value, which would silently disagree with the other
        # wrappers — see docs/sdk-wrapper-contract.md §request-translation.
        query=_first_query_values(request),
        params=dict(request.path_params or {}),
        body=await _read_json_body(request, max_body_bytes),
    )


def _first_query_values(request: Request) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for k, v in request.query_params.multi_items():
        out.setdefault(k, v)
    return out


async def _read_json_body(request: Request, max_body_bytes: int) -> dict[str, Any]:
    # Use the declared content length to skip oversized bodies early.
    content_length_str = request.headers.get("content-length")
    if content_length_str and content_length_str.isdigit():
        if int(content_length_str) > max_body_bytes:
            return {}
    content_type = request.headers.get("content-type", "").lower()
    if "json" not in content_type:
        return {}
    raw = await request.body()  # Starlette caches the read so the route handler can re-read.
    if not raw:
        return {}
    if len(raw) > max_body_bytes:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    if isinstance(parsed, dict):
        return parsed
    return {}


def _block_response(m: MatchedRule) -> tuple[int, dict[str, Any]]:
    status = 403
    if m.action.payload:
        sc = m.action.payload.get("statusCode")
        if isinstance(sc, int):
            status = sc
    body = {
        "blocked": True,
        "ruleId": m.id,
        "action": {"type": m.action.type, "payload": m.action.payload},
    }
    return status, body
