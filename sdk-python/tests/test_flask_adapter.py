"""Flask adapter integration tests.

Drives a real Flask app via ``app.test_client()`` (no socket boot needed)
through the demo ruleset — proving the same status codes / matched rule ids
the Node, Java, and Go example servers produce.
"""

from __future__ import annotations

from typing import Iterator

import flask
import pytest

from klswitch import Client
from klswitch.adapters.flask import KLSwitchExtension

from .conftest import make_demo_rules


@pytest.fixture()
def app() -> Iterator[flask.Flask]:
    ks = Client(rules=make_demo_rules())
    flask_app = flask.Flask(__name__)
    ext = KLSwitchExtension(flask_app, client=ks)

    @flask_app.route("/v1/payments/<id>", methods=["POST"])
    @ext.guard()
    def payment(id: str) -> flask.Response:  # type: ignore[unused-ignore]
        result = getattr(flask.g, "klswitch")
        return flask.jsonify(
            {"paymentId": id, "matched": [m.id for m in result.matched]}
        )

    @flask_app.route("/v1/users/<id>", methods=["GET"])
    @ext.guard()
    def user(id: str) -> flask.Response:  # type: ignore[unused-ignore]
        result = getattr(flask.g, "klswitch")
        return flask.jsonify(
            {"userId": id, "matched": [m.id for m in result.matched]}
        )

    try:
        yield flask_app
    finally:
        ks.close()


def test_small_payment_passes_through(app: flask.Flask) -> None:
    res = app.test_client().post("/v1/payments/abc", json={"amount": 100})
    assert res.status_code == 200
    assert res.get_json() == {"paymentId": "abc", "matched": []}


def test_large_payment_blocks_with_429(app: flask.Flask) -> None:
    res = app.test_client().post("/v1/payments/abc", json={"amount": 70000})
    assert res.status_code == 429
    body = res.get_json()
    assert body["blocked"] is True
    assert body["ruleId"] == "block_large_payment"


def test_restricted_region_blocks_with_451(app: flask.Flask) -> None:
    res = app.test_client().post(
        "/v1/payments/abc", json={"amount": 10, "country": "KP"}
    )
    assert res.status_code == 451
    assert res.get_json()["ruleId"] == "block_restricted_region"


def test_admin_user_flagged_not_blocked(app: flask.Flask) -> None:
    res = app.test_client().get("/v1/users/42?role=admin")
    assert res.status_code == 200
    assert res.get_json() == {"userId": "42", "matched": ["flag_admin_access"]}


def test_impersonate_header_triggers_flag(app: flask.Flask) -> None:
    res = app.test_client().get(
        "/v1/users/42", headers={"X-Impersonate": "ops-user"}
    )
    assert res.status_code == 200
    assert res.get_json()["matched"] == ["flag_admin_access"]


def test_custom_result_handler_overrides_default() -> None:
    ks = Client(rules=make_demo_rules())
    app = flask.Flask(__name__)

    def custom_handler(result, request):
        for m in result.matched:
            if m.action.type == "block":
                return flask.make_response(
                    flask.jsonify({"custom_block": True, "rule": m.id}),
                    418,  # I'm a teapot — clearly distinguishable from the default 429
                )
        return None

    ext = KLSwitchExtension(app, client=ks, result_handler=custom_handler)

    @app.route("/v1/payments/<id>", methods=["POST"])
    @ext.guard()
    def payment(id: str) -> flask.Response:  # type: ignore[unused-ignore]
        return flask.jsonify({"unreachable": True})

    try:
        res = app.test_client().post("/v1/payments/abc", json={"amount": 70000})
        assert res.status_code == 418
        assert res.get_json() == {"custom_block": True, "rule": "block_large_payment"}
    finally:
        ks.close()


def test_default_error_handler_returns_500_when_client_closed() -> None:
    ks = Client(rules=make_demo_rules())
    ks.close()  # evaluate now raises ClosedClientError

    app = flask.Flask(__name__)
    ext = KLSwitchExtension(app, client=ks)

    @app.route("/v1/payments/<id>", methods=["POST"])
    @ext.guard()
    def payment(id: str) -> flask.Response:  # type: ignore[unused-ignore]
        return flask.jsonify({"unreachable": True})

    res = app.test_client().post("/v1/payments/abc", json={"amount": 100})
    assert res.status_code == 500
    assert res.get_json()["error"] == "klswitch evaluation failed"


def test_view_function_sees_full_request_body(app: flask.Flask) -> None:
    """The middleware reads the body for fact extraction but must not consume
    it — Flask's ``get_data(cache=True)`` lets the view read the same bytes.
    """
    body_round_trip: list[bytes] = []

    @app.route("/v1/echo", methods=["POST"])
    def echo() -> flask.Response:
        body_round_trip.append(flask.request.get_data())
        return flask.jsonify({"ok": True})

    payload = b'{"hello":"world"}'
    res = app.test_client().post(
        "/v1/echo", data=payload, content_type="application/json"
    )
    assert res.status_code == 200
    assert body_round_trip == [payload]


# ── app-wide guarding (init_app(guard_all=True)) ────────────────────────────


def _guard_all_app() -> flask.Flask:
    """A Flask app guarded by one call, with no decorator on any view."""
    ks = Client(rules=make_demo_rules())
    app = flask.Flask(__name__)
    KLSwitchExtension(app, client=ks, guard_all=True)

    @app.route("/v1/payments/<id>", methods=["POST"])
    def payment(id: str):  # type: ignore[unused-ignore]
        return flask.jsonify({"paymentId": id})

    return app


def test_guard_all_blocks_without_any_decorator() -> None:
    app = _guard_all_app()
    res = app.test_client().post("/v1/payments/42", json={"amount": 70000})
    assert res.status_code == 429
    assert res.get_json()["blocked"] is True


def test_guard_all_covers_views_added_later() -> None:
    """The point of app-wide registration: a new view needs no wiring."""
    app = _guard_all_app()

    @app.route("/v1/payments/<id>/refund", methods=["POST"])
    def refund(id: str):  # type: ignore[unused-ignore]
        return flask.jsonify({"ok": True})

    res = app.test_client().post("/v1/payments/9/refund", json={"amount": 70000})
    assert res.status_code == 200, "no rule targets this route, so it passes"

    res = app.test_client().post("/v1/payments/9", json={"amount": 70000})
    assert res.status_code == 429, "a view registered after init_app is still guarded"


def test_guard_all_attaches_the_result_for_the_view() -> None:
    """`before_request` runs after routing, so it has what the decorator has."""
    app = _guard_all_app()
    seen: dict[str, object] = {}

    @app.route("/v1/echo/<thing>", methods=["POST"])
    def echo(thing: str):  # type: ignore[unused-ignore]
        seen["result"] = getattr(flask.g, "klswitch", None)
        return flask.jsonify({"ok": True})

    app.test_client().post("/v1/echo/abc", json={"amount": 1})
    assert seen["result"] is not None, "result on flask.g for the view to read"


def test_guard_all_skips_unrouted_requests() -> None:
    """A 404 has no url_rule; evaluating the concrete path would mislead."""
    app = _guard_all_app()
    res = app.test_client().post("/nope", json={"amount": 70000})
    assert res.status_code == 404, "not blocked — there was no route to guard"


# ── double-guarding is refused at configuration time ────────────────────────


def test_guard_all_plus_decorator_fails_at_startup() -> None:
    """Both registrations would evaluate every request twice: double latency,
    and two analytics events so decision counts read double. Nothing about the
    responses looks wrong, which is exactly why this is refused up front."""
    app = flask.Flask(__name__)
    ext = KLSwitchExtension(app, client=Client(rules=make_demo_rules()), guard_all=True)

    with pytest.raises(RuntimeError, match="guarding this app twice"):

        @app.route("/v1/payments/<id>", methods=["POST"])
        @ext.guard()
        def payment(id: str):  # type: ignore[unused-ignore]
            return flask.jsonify({"ok": True})


def test_decorator_first_then_guard_all_also_fails() -> None:
    """The app-factory order: decorators run at import, init_app comes later."""
    ext = KLSwitchExtension(client=Client(rules=make_demo_rules()))

    @ext.guard()
    def view():  # type: ignore[unused-ignore]
        return {}

    with pytest.raises(RuntimeError, match="guarding this app twice"):
        ext.init_app(flask.Flask(__name__), guard_all=True)


def test_each_style_alone_still_works() -> None:
    app_a = flask.Flask("a")
    KLSwitchExtension(app_a, client=Client(rules=make_demo_rules()), guard_all=True)

    app_b = flask.Flask("b")
    ext_b = KLSwitchExtension(app_b, client=Client(rules=make_demo_rules()))

    @app_b.route("/x")
    @ext_b.guard()
    def view():  # type: ignore[unused-ignore]
        return {}

