"""FastAPI route discovery.

FastAPI's ``app.routes`` exposes the registered routes directly — each
``APIRoute`` carries:

  - ``path``    : str  (templated, FastAPI-native ``{id}`` syntax)
  - ``methods`` : set[str]
  - ``endpoint``: callable (the handler function)
  - ``name``    : str  (operation name, useful as the handler identifier)

We convert ``{id}`` → ``:id`` so the manifest matches the rule selector
convention used across the wrappers (Fastify/Express both use ``:id``).
Without this, the console's route picker on ``/rules/new`` wouldn't
match rules authored against ``:id``.

If FastAPI isn't installed the import side has no cost — we only touch
``app.routes`` (duck-typed) at discovery time.
"""

from __future__ import annotations

import re
from typing import Any

from .._manifest import ManifestRoute, ManifestSource

_HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
# Matches a FastAPI/Starlette path parameter: "{id}" or "{id:int}" or "{path:path}".
# We strip any converter spec — the manifest only needs the name.
_PARAM_PATTERN = re.compile(r"\{([^:{}]+)(?::[^{}]+)?\}")


def fastapi_manifest_source(app: Any) -> ManifestSource:
    """Returns a :class:`ManifestSource` that walks FastAPI's
    ``app.routes`` registry.

    ::

        from klswitch import Client
        from klswitch.adapters.fastapi_manifest import fastapi_manifest_source

        ks = Client(
            token=os.environ["KS_TOKEN"],
            manifest=fastapi_manifest_source(app),
        )
    """
    return ManifestSource(
        framework="fastapi",
        collect=lambda: discover_fastapi_routes(app),
    )


def discover_fastapi_routes(app: Any) -> list[ManifestRoute]:
    """Exposed for tests. Walks ``app.routes`` and emits one
    :class:`ManifestRoute` per ``(method, path)`` pair.

    Routes without HTTP methods (e.g. WebSocket, Mount, lifespan) are
    skipped. Methods outside the standard set are skipped.
    """
    routes = getattr(app, "routes", None)
    if routes is None:
        return []
    out: list[ManifestRoute] = []
    for route in routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if not isinstance(path, str) or not methods:
            continue
        if _is_framework_route(app, path):
            continue
        canonical_path = convert_path_to_colon_style(path)
        handler = _extract_handler_name(route)
        for method in _filter_auto_added({str(m).upper() for m in methods}):
            if method not in _HTTP_METHODS:
                continue
            out.append(ManifestRoute(
                method=method,
                path=canonical_path,
                handler=handler,
            ))
    return out


def _is_framework_route(app: Any, path: str) -> bool:
    """True for the documentation endpoints FastAPI mounts on its own.

    ``/docs``, ``/redoc``, ``/openapi.json`` and the OAuth2 redirect are added by
    the framework, not by the application. Reporting them tells the console that
    the service exposes four API endpoints it does not, and they then appear in
    the rule author's route picker as though a rule could sensibly target them.

    Read from the app's own settings rather than matched against the default
    strings, because all four are configurable — an app that moves its docs to
    ``/internal/docs`` would otherwise have them reported, and one that sets a
    real route at ``/docs`` would have it dropped.
    """
    configured = {
        getattr(app, "docs_url", None),
        getattr(app, "redoc_url", None),
        getattr(app, "openapi_url", None),
        getattr(app, "swagger_ui_oauth2_redirect_url", None),
    }
    return path in {p for p in configured if isinstance(p, str)}


def _filter_auto_added(methods: set[str]) -> list[str]:
    """Drop the methods Starlette auto-attaches, matching the Flask adapter.

    ``HEAD`` is paired with every ``GET`` by Starlette's routing, so reporting it
    doubles the route count with verbs the application never declared. A route
    registered as HEAD-only keeps it — the filter only fires on the auto-pair.
    """
    keep = set(methods)
    if "GET" in keep:
        keep.discard("HEAD")
    return sorted(keep)


def convert_path_to_colon_style(path: str) -> str:
    """Convert FastAPI's ``{id}`` / ``{id:int}`` syntax to ``:id``.

    The converter spec (``:int``, ``:path`` etc.) is stripped — the rule
    selectors don't track types.
    """
    return _PARAM_PATTERN.sub(lambda m: f":{m.group(1)}", path)


def _extract_handler_name(route: Any) -> str | None:
    """Best-effort handler identifier for the manifest. Prefers the
    operation's ``name`` (FastAPI sets this to the endpoint's
    ``__name__`` by default), falls back to the endpoint's qualname.
    """
    name = getattr(route, "name", None)
    if isinstance(name, str) and name:
        return name
    endpoint = getattr(route, "endpoint", None)
    if endpoint is not None:
        qual = getattr(endpoint, "__qualname__", None)
        if isinstance(qual, str) and qual:
            return qual
    return None
