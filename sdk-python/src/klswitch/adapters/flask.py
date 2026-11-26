"""Flask adapter — sync, WSGI.

Wraps a :class:`klswitch.Client` so a Flask view function gets the
evaluation result on ``flask.g.klswitch`` (or whatever the configured
result handler decides). Defaults to the same block-on-block policy the
Go middleware ships: a matched ``block`` action short-circuits with
``action.payload.statusCode`` (or 403), JSON body. Anything else flows
through to the view.

Path templates are read from ``request.url_rule.rule`` and converted to
the engine's ``:name`` syntax. Body extraction is bounded (default 1 MiB)
so an oversized JSON payload can't OOM the middleware.

**Production note — chunked uploads:** the SDK's body cap relies on
``Content-Length`` to skip oversized bodies before calling
``request.get_data()``. Requests using ``Transfer-Encoding: chunked``
have no Content-Length, so the entire body is read into memory before
the post-read length check kicks in. Set Flask's
``app.config["MAX_CONTENT_LENGTH"]`` (or your WSGI server's request-size
limit) to bound the read at the framework layer for chunked clients.
"""

from __future__ import annotations

import functools
import json
import logging
from typing import Any, Callable, Optional

import flask

from .._request_context import build_evaluation_input, flask_template_to_engine_path
from ..client import Client
from ..types import EvaluationInput, EvaluationResult, MatchedRule

_LOG = logging.getLogger("klswitch.flask")


# Default body cap matches Go's httpmiddleware + Java's KLSwitchFilter.
DEFAULT_MAX_BODY_BYTES = 1 << 20  # 1 MiB

# Where the EvaluationResult lands on `flask.g`.
RESULT_ATTR = "klswitch"

_DOUBLE_GUARD_MESSAGE = (
    "KLSwitch is guarding this app twice: init_app(guard_all=True) covers every "
    "view, and @guard() adds a second evaluation on top.\n"
    "\n"
    "Every doubly-guarded request would be evaluated twice — paying the latency "
    "twice and, worse, emitting two analytics events, so decision counts read "
    "double.\n"
    "\n"
    "Keep one:\n"
    "  - guard_all=True  — every view, nothing per-view (recommended)\n"
    "  - @guard()        — only the views you decorate; drop guard_all"
)


# A ResultHandler may return a Flask response to short-circuit, or `None`
# to let the view function execute.
FlaskResultHandler = Callable[
    [EvaluationResult, "flask.Request"], Optional["flask.Response"]
]

# An ErrorHandler turns a Client.evaluate exception into a Flask response.
FlaskErrorHandler = Callable[[Exception, "flask.Request"], "flask.Response"]


def default_result_handler(
    result: EvaluationResult, request: "flask.Request"
) -> Optional["flask.Response"]:
    """Default policy: short-circuit on the first ``block`` match, else continue."""
    for m in result.matched:
        if m.action.type == "block":
            return _write_block(m)
    return None


def default_error_handler(err: Exception, request: "flask.Request") -> "flask.Response":
    """Default policy: 500 with a JSON error body."""
    return flask.make_response(
        flask.jsonify({"error": "klswitch evaluation failed", "detail": str(err)}),
        500,
    )


class KLSwitchExtension:
    """Standard Flask extension pattern.

    Construct once with the SDK client and register it on the app via
    ``init_app``; use :meth:`guard` as a per-view decorator. The default
    handlers match the Go middleware's defaults so behaviour is comparable
    across SDKs.

    Typical wiring::

        ks = klswitch.Client(rules=...)

        # One-time: every view guarded, nothing per-endpoint.
        KLSwitchExtension(app, client=ks, guard_all=True)

        @app.route("/v1/payments/<id>", methods=["POST"])
        def payment(id):
            return {"paymentId": id, "matched": flask.g.klswitch.matched}

    Or scope it per view with :meth:`guard` instead::

        ks_ext = KLSwitchExtension(app, client=ks)

        @app.route("/v1/payments/<id>", methods=["POST"])
        @ks_ext.guard()
        def payment(id):
            ...
    """

    def __init__(
        self,
        app: Optional["flask.Flask"] = None,
        *,
        client: Client,
        result_handler: FlaskResultHandler | None = None,
        error_handler: FlaskErrorHandler | None = None,
        max_body_bytes: int = DEFAULT_MAX_BODY_BYTES,
        guard_all: bool = False,
    ) -> None:
        self._client = client
        self._result_handler = result_handler or default_result_handler
        self._error_handler = error_handler or default_error_handler
        self._max_body_bytes = max_body_bytes
        # Both are needed because the two registrations can happen in either
        # order: `guard()` at import time then `init_app` in a factory, or
        # `init_app` first and decorators after.
        self._guard_all = False
        self._decorated = False
        self._warned = False
        if app is not None:
            self.init_app(app, guard_all=guard_all)

    def init_app(self, app: "flask.Flask", *, guard_all: bool = False) -> None:
        """Register the extension on a Flask app.

        Pass ``guard_all=True`` to evaluate **every** request from this one call,
        with no per-view decorator — the Django/FastAPI shape. New views are then
        covered automatically, which is the property you want from a klswitch:
        forgetting leaves a route protected, not exposed.

        Implemented as a ``before_request`` hook, which is the earliest point
        where Flask has resolved the route — so ``request.url_rule`` carries the
        template and ``request.view_args`` the path params, exactly as the
        decorator sees them. Requests that match no route (404) leave
        ``url_rule`` as ``None`` and are skipped: there is no route to guard, and
        the concrete path would only produce a misleading evaluation.
        """
        if not hasattr(app, "extensions") or app.extensions is None:
            app.extensions = {}
        app.extensions["klswitch"] = self
        if guard_all:
            if self._decorated:
                raise RuntimeError(_DOUBLE_GUARD_MESSAGE)
            self._guard_all = True
            app.before_request(self._guard_all_hook)
        else:
            # Opt-in mode guards nothing until a view is decorated, and an app that registers
            # the extension and stops there starts cleanly, logs nothing, and allows every
            # request — indistinguishable from a working install until something should have
            # been blocked and was not. Checked on the first request rather than here, because
            # views are often decorated after init_app; by then the answer is definitive.
            app.before_request(self._warn_if_unguarded)

    def _warn_if_unguarded(self) -> None:
        """One-shot check: warn on the first request if nothing is actually guarding."""
        if self._warned:
            return
        self._warned = True
        if not self._guard_all and not self._decorated:
            _LOG.warning(
                "KLSwitch is registered on this Flask app but no route is guarded: "
                "no view uses @extension.guard and init_app was called without "
                "guard_all=True. Every request is being allowed. Pass "
                "init_app(app, guard_all=True) to evaluate every route."
            )

    def _guard_all_hook(self) -> Any:
        """``before_request`` handler installed by ``init_app(guard_all=True)``.

        Returning a response short-circuits the view, which is how Flask lets a
        ``before_request`` hook block; returning ``None`` lets it run.
        """
        request = flask.request
        if request.url_rule is None:
            return None  # unrouted request (404) — nothing to guard
        try:
            result = self._client.evaluate(self._build_input(request))
        except Exception as err:  # noqa: BLE001 - host-side error
            return self._error_handler(err, request)
        setattr(flask.g, RESULT_ATTR, result)
        return self._result_handler(result, request)

    def guard(
        self,
        *,
        result_handler: FlaskResultHandler | None = None,
        error_handler: FlaskErrorHandler | None = None,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        """Return a decorator that wraps a view function with KLSwitch evaluation.

        Per-view ``result_handler`` / ``error_handler`` override the
        extension-level defaults if supplied.
        """
        if self._guard_all:
            raise RuntimeError(_DOUBLE_GUARD_MESSAGE)
        self._decorated = True

        rh = result_handler or self._result_handler
        eh = error_handler or self._error_handler

        def decorator(view: Callable[..., Any]) -> Callable[..., Any]:
            @functools.wraps(view)
            def wrapped(*args: Any, **kwargs: Any) -> Any:
                request = flask.request
                try:
                    ev_input = self._build_input(request)
                    result = self._client.evaluate(ev_input)
                except Exception as err:  # noqa: BLE001 - host-side error
                    return eh(err, request)
                # Attach for the view to read regardless of result_handler choice.
                setattr(flask.g, RESULT_ATTR, result)
                response = rh(result, request)
                if response is not None:
                    return response
                return view(*args, **kwargs)

            return wrapped

        return decorator

    # ───── internals ────────────────────────────────────────────────────────

    def _build_input(self, request: "flask.Request") -> EvaluationInput:
        template = request.path
        if request.url_rule is not None and request.url_rule.rule:
            template = flask_template_to_engine_path(request.url_rule.rule)
        return build_evaluation_input(
            method=request.method,
            path=template,
            headers=dict(request.headers),
            query=request.args.to_dict(flat=True),
            params=dict(request.view_args or {}),
            body=self._read_json_body(request),
        )

    def _read_json_body(self, request: "flask.Request") -> dict[str, Any]:
        # Bound the read: refuse oversized bodies before they reach json.loads,
        # so the middleware can't be DoS'd by a 10 GB JSON payload.
        content_length = request.content_length or 0
        if content_length > self._max_body_bytes:
            return {}
        content_type = (request.content_type or "").lower()
        if "json" not in content_type:
            return {}
        raw = request.get_data(cache=True, as_text=False)
        if not raw:
            return {}
        # Defensive: even if Content-Length lied, the actual bytes are bounded.
        if len(raw) > self._max_body_bytes:
            return {}
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return {}
        if isinstance(parsed, dict):
            return parsed
        return {}


def _write_block(m: MatchedRule) -> "flask.Response":
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
    return flask.make_response(flask.jsonify(body), status)
