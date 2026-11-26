"""Shared request → :class:`EvaluationInput` builder.

Used by the Flask, FastAPI and Django adapters so the engine sees the same
field-name/casing conventions regardless of which framework called in
(headers lower-cased, method upper-cased, all five fact buckets present —
matches the Node / Java / Go wrappers).

Re-exported from the package root as public API: frameworks without an adapter
(Litestar, Tornado, aiohttp, Sanic, Falcon, Bottle, Pyramid, Quart, …) integrate
by calling :func:`build_evaluation_input` and then ``client.evaluate``. The
module stays underscore-prefixed because the *module path* is not the contract —
``from klswitch import build_evaluation_input`` is.
"""

from __future__ import annotations

import re
from typing import Any

from .types import EvaluationInput, Facts, Selector


def canonicalize_template(path: str) -> str:
    """Accept any of the three common route-template dialects and return the engine's.

    ``{id}`` (FastAPI/Starlette), ``<id>`` / ``<int:id>`` (Flask/Werkzeug) and ``:id`` (engine,
    Express) all become ``:id``.

    Applied automatically by :func:`build_evaluation_input`. A host integrating without an
    adapter otherwise has to know to call the per-dialect converters, and the failure when they
    do not is close to invisible: rules whose selectors have no path parameter still match, so
    the integration looks healthy while every parameterised rule silently misses. Braces and
    angle brackets are not valid in an engine selector, so rewriting them cannot change the
    meaning of a path that was already correct.
    """
    return fastapi_template_to_engine_path(flask_template_to_engine_path(path))


def build_evaluation_input(
    *,
    method: str,
    path: str,
    headers: dict[str, Any] | None = None,
    query: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
    body: dict[str, Any] | None = None,
) -> EvaluationInput:
    """Construct an :class:`EvaluationInput` from request parts.

    * Method is upper-cased.
    * Header keys are lower-cased (matches Node / Java / Go).
    * Any missing bucket becomes ``{}`` — defensive against ``None`` inputs.

    ``path`` is **yours to supply, already templated**: pass
    ``/v1/orders/:id``, not ``/v1/orders/8123``. The engine matches the path as
    written (rule-model §8), so a concrete path only matches a rule that spells
    out that exact id — or a ``*`` pattern. Your framework knows the template;
    this function cannot infer it.

    The dialect, however, does not matter: ``{id}``, ``<id>`` and ``:id`` are all
    accepted and canonicalised to ``:id`` via :func:`canonicalize_template`.

    You do **not** need to supply the current time. ``EvaluationInput.now``
    defaults to ``None`` and is filled with the wall clock during encoding, so
    rules carrying an ``effective`` window evaluate correctly without any action
    from the caller. Set it explicitly only to pin time, e.g. in tests.

    Example, for a framework with no adapter::

        from klswitch import build_evaluation_input

        result = client.evaluate(build_evaluation_input(
            method=request.method,
            path="/v1/orders/:id",        # your canonical template
            headers=dict(request.headers),
            query=dict(request.query),
            params={"id": order_id},
            body=await request.json(),
        ))
        blocked = next(
            (m for m in result.matched if m.action.type == "block"), None
        )
    """
    return EvaluationInput(
        selector=Selector(
            kind="api",
            keys={"method": method.upper(), "path": canonicalize_template(path)},
        ),
        facts=Facts(
            headers={str(k).lower(): v for k, v in (headers or {}).items()},
            query=dict(query or {}),
            params=dict(params or {}),
            body=dict(body or {}),
            meta={},
        ),
    )


_FLASK_PATH_RE = re.compile(r"<(?:[^:>]+:)?([^>]+)>")
# Capture only the name portion; ignore any `:converter` (Starlette supports
# `{file_path:path}` for catch-all parameters — engine should see `:file_path`).
_FASTAPI_PATH_RE = re.compile(r"\{([^}:]+)(?::[^}]+)?\}")


def flask_template_to_engine_path(rule: str) -> str:
    """Convert a Flask route template to the engine's ``:name`` syntax.

    ``"/v1/payments/<id>"`` → ``"/v1/payments/:id"``;
    ``"/v1/payments/<int:id>"`` → ``"/v1/payments/:id"``;
    ``"/static/<path:filename>"`` → ``"/static/:filename"``.
    """
    return _FLASK_PATH_RE.sub(lambda m: f":{m.group(1)}", rule)


def fastapi_template_to_engine_path(rule: str) -> str:
    """Convert a FastAPI/Starlette route template to the engine's ``:name`` syntax.

    ``"/v1/payments/{id}"`` → ``"/v1/payments/:id"``;
    ``"/static/{file_path:path}"`` → ``"/static/:file_path"``.
    """
    return _FASTAPI_PATH_RE.sub(lambda m: f":{m.group(1)}", rule)
