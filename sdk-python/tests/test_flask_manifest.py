"""Flask manifest-discovery tests.

Drives a real Flask app's ``app.url_map.iter_rules()`` registry — no
WSGI server — so the walker is exercised against the exact shape
Werkzeug produces, including its auto-paired ``HEAD`` / ``OPTIONS``
behaviour and the built-in ``static`` endpoint.
"""

from __future__ import annotations

import flask

from klswitch.adapters.flask_manifest import (
    discover_flask_routes,
    flask_manifest_source,
)


def _pairs(routes) -> set[str]:
    return {f"{r.method} {r.path}" for r in routes}


def test_discover_converts_werkzeug_param_syntax() -> None:
    app = flask.Flask(__name__)

    @app.route("/v1/payments/<id>", methods=["POST"])
    def create_payment(id):  # noqa: A002 (shadow built-in, intentional)
        return ""

    @app.route("/v1/items/<int:id>", methods=["GET"])
    def get_item(id):  # noqa: A002
        return ""

    routes = discover_flask_routes(app)
    pairs = _pairs(routes)
    assert "POST /v1/payments/:id" in pairs
    assert "GET /v1/items/:id" in pairs


def test_discover_strips_converter_spec_on_catch_all() -> None:
    app = flask.Flask(__name__)

    @app.route("/files/<path:filename>", methods=["GET"])
    def files(filename):
        return ""

    routes = discover_flask_routes(app)
    pairs = _pairs(routes)
    assert "GET /files/:filename" in pairs


def test_discover_filters_auto_added_head_when_get_present() -> None:
    """Werkzeug pairs HEAD with every GET. Operators don't author HEAD
    rules, so HEAD is dropped when GET is also on the rule.
    """
    app = flask.Flask(__name__)

    @app.route("/health")     # default methods → GET (+ auto HEAD, OPTIONS)
    def health():
        return "ok"

    routes = discover_flask_routes(app)
    methods_for_health = {r.method for r in routes if r.path == "/health"}
    assert methods_for_health == {"GET"}, methods_for_health


def test_discover_drops_options_auto_pair() -> None:
    """Werkzeug auto-adds OPTIONS to every rule. Drop it unless it's the
    only verb on the route.
    """
    app = flask.Flask(__name__)

    @app.route("/v1/echo", methods=["POST"])
    def echo():
        return ""

    routes = discover_flask_routes(app)
    methods_for_echo = {r.method for r in routes if r.path == "/v1/echo"}
    assert methods_for_echo == {"POST"}, methods_for_echo


def test_discover_keeps_explicit_options_only_route() -> None:
    """An operator-declared OPTIONS-only route still surfaces — only the
    auto-paired OPTIONS is dropped.
    """
    app = flask.Flask(__name__)

    @app.route("/cors", methods=["OPTIONS"])
    def cors():
        return ""

    routes = discover_flask_routes(app)
    methods_for_cors = {r.method for r in routes if r.path == "/cors"}
    assert methods_for_cors == {"OPTIONS"}


def test_discover_skips_static_endpoint() -> None:
    """Flask registers /static/<path:filename> automatically. The console
    doesn't want rules against the static server, so skip it.
    """
    app = flask.Flask(__name__)
    paths = {r.path for r in discover_flask_routes(app)}
    assert "/static/:filename" not in paths


def test_discover_emits_endpoint_as_handler() -> None:
    app = flask.Flask(__name__)

    @app.route("/v1/payments", methods=["POST"])
    def create_payment():
        return ""

    routes = discover_flask_routes(app)
    payment = next(r for r in routes if r.path == "/v1/payments" and r.method == "POST")
    assert payment.handler == "create_payment"


def test_discover_handles_multi_method_rule() -> None:
    app = flask.Flask(__name__)

    @app.route("/v1/items", methods=["GET", "POST", "DELETE"])
    def items():
        return ""

    routes = discover_flask_routes(app)
    methods = sorted(r.method for r in routes if r.path == "/v1/items")
    # OPTIONS auto-pair dropped, HEAD auto-pair dropped (GET present).
    assert methods == ["DELETE", "GET", "POST"]


def test_discover_returns_empty_for_missing_url_map() -> None:
    class NotAnApp:
        pass

    assert discover_flask_routes(NotAnApp()) == []


def test_flask_manifest_source_yields_walker() -> None:
    app = flask.Flask(__name__)

    @app.route("/v1/health", methods=["GET"])
    def health():
        return "ok"

    src = flask_manifest_source(app)
    assert src.framework == "flask"
    collected = src.collect()
    assert any(r.path == "/v1/health" and r.method == "GET" for r in collected)
