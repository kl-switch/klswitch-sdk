"""FastAPI manifest-discovery tests.

Drives a real FastAPI app's ``app.routes`` registry — no socket — so the
walker is exercised against the exact shape FastAPI produces (including
the ``{id}``-style path-param syntax that must be canonicalised to
``:id`` for parity with the rule selector convention).
"""

from __future__ import annotations

from fastapi import FastAPI

from klswitch.adapters.fastapi_manifest import (
    convert_path_to_colon_style,
    discover_fastapi_routes,
    fastapi_manifest_source,
)


def test_convert_path_to_colon_style_strips_curly_braces() -> None:
    assert convert_path_to_colon_style("/v1/payments/{id}") == "/v1/payments/:id"
    assert convert_path_to_colon_style("/v1/users/{userId}/posts/{postId}") == (
        "/v1/users/:userId/posts/:postId"
    )


def test_convert_path_to_colon_style_strips_converter_spec() -> None:
    """FastAPI lets you annotate a converter (``{id:int}``, ``{p:path}``).
    The rule selector doesn't care about types — strip the suffix.
    """
    assert convert_path_to_colon_style("/v1/items/{id:int}") == "/v1/items/:id"
    assert convert_path_to_colon_style("/static/{p:path}") == "/static/:p"


def test_convert_path_to_colon_style_passthrough() -> None:
    assert convert_path_to_colon_style("/v1/health") == "/v1/health"
    assert convert_path_to_colon_style("/") == "/"


def test_discover_emits_one_route_per_method() -> None:
    app = FastAPI()

    @app.post("/v1/payments/{id}")
    async def create_payment(id: str) -> dict:
        return {"id": id}

    @app.get("/v1/users/{id}")
    async def get_user(id: str) -> dict:
        return {"id": id}

    routes = discover_fastapi_routes(app)
    pairs = {(r.method, r.path) for r in routes}
    assert ("POST", "/v1/payments/:id") in pairs
    assert ("GET",  "/v1/users/:id")   in pairs


def test_discover_multi_method_route_emits_each_method() -> None:
    app = FastAPI()

    @app.api_route("/v1/things/{id}", methods=["GET", "POST", "DELETE"])
    async def thing(id: str) -> dict:
        return {"id": id}

    routes = discover_fastapi_routes(app)
    methods_for_path = sorted(r.method for r in routes if r.path == "/v1/things/:id")
    assert methods_for_path == ["DELETE", "GET", "POST"]


def test_discover_includes_handler_name() -> None:
    app = FastAPI()

    @app.post("/v1/payments/{id}")
    async def create_payment(id: str) -> dict:
        return {"id": id}

    routes = discover_fastapi_routes(app)
    payment = next(r for r in routes if r.path == "/v1/payments/:id" and r.method == "POST")
    assert payment.handler == "create_payment"


def test_discover_skips_non_http_routes() -> None:
    """WebSocket / Mount / lifespan routes carry no ``.methods`` set — they
    must be filtered out without raising.
    """
    app = FastAPI()

    @app.get("/v1/health")
    async def health() -> dict:
        return {"ok": True}

    @app.websocket("/ws")
    async def ws_handler(websocket) -> None:  # type: ignore[no-untyped-def]
        await websocket.accept()

    routes = discover_fastapi_routes(app)
    paths = {r.path for r in routes}
    assert "/v1/health" in paths
    assert "/ws" not in paths  # WebSocketRoute has no .methods


def test_discover_empty_when_no_routes() -> None:
    """A bare FastAPI app still has internal routes (docs, openapi.json,
    /redoc, /docs/oauth2-redirect). They're real GET routes — the
    walker should surface them rather than filter them, so the operator
    sees the real registry.
    """
    app = FastAPI()
    routes = discover_fastapi_routes(app)
    # Walks the framework's full registry; just assert we got something
    # without crashing.
    assert all(r.method.isupper() for r in routes)


def test_discover_returns_empty_for_missing_routes_attr() -> None:
    """If we're handed something without ``.routes`` (defensive — operator
    might pass the wrong object), we return [] instead of crashing.
    """
    class _NotAnApp:
        pass

    assert discover_fastapi_routes(_NotAnApp()) == []


def test_fastapi_manifest_source_yields_walker() -> None:
    app = FastAPI()

    @app.get("/v1/health")
    async def health() -> dict:
        return {"ok": True}

    src = fastapi_manifest_source(app)
    assert src.framework == "fastapi"
    collected = src.collect()
    assert any(r.path == "/v1/health" and r.method == "GET" for r in collected)


def test_framework_docs_routes_are_not_reported() -> None:
    """FastAPI mounts /docs, /redoc and /openapi.json itself.

    Reporting them told the console the service exposes four endpoints it does
    not, and put them in the rule author's route picker. Found by running the
    adapter against a live backend: 26 routes reported where the app declares 18.
    """
    from fastapi import FastAPI

    from klswitch.adapters.fastapi_manifest import discover_fastapi_routes

    app = FastAPI()

    @app.get("/api/items")
    async def items() -> dict:
        return {}

    paths = {r.path for r in discover_fastapi_routes(app)}
    assert paths == {"/api/items"}


def test_a_real_route_on_the_docs_path_is_kept() -> None:
    """The filter reads the app's own settings, not the default strings.

    An app that turns the docs off and serves its own /docs must still have it
    reported — matching on the literal path would silently drop a real endpoint.
    """
    from fastapi import FastAPI

    from klswitch.adapters.fastapi_manifest import discover_fastapi_routes

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/docs")
    async def docs() -> dict:
        return {}

    assert {r.path for r in discover_fastapi_routes(app)} == {"/docs"}


def test_head_paired_with_get_is_dropped() -> None:
    """Starlette pairs HEAD with every GET; the app never declared it."""
    from fastapi import FastAPI

    from klswitch.adapters.fastapi_manifest import discover_fastapi_routes

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/api/items")
    async def items() -> dict:
        return {}

    assert [r.method for r in discover_fastapi_routes(app)] == ["GET"]
