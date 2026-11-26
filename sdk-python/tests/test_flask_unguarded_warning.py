"""
The regression: `KLSwitchExtension(app, client=client)` registers the extension but guards
nothing unless a view is decorated or `guard_all=True` is passed. The app started cleanly, logged
nothing, and allowed every request — it failed 8 of 8 block cases while looking healthy.
"""
import logging

import flask
import pytest

from klswitch import Client

from klswitch.adapters.flask import KLSwitchExtension

from .conftest import make_ruleset


def _app():
    app = flask.Flask(__name__)

    @app.get("/items")
    def items():
        return {"ok": True}

    return app


def test_warns_when_nothing_is_guarded(caplog):
    app = _app()
    KLSwitchExtension(client=Client(rules=make_ruleset())).init_app(app)  # no guard_all, no decorator
    with caplog.at_level(logging.WARNING, logger="klswitch.flask"):
        app.test_client().get("/items")
    assert any("no route is guarded" in r.message for r in caplog.records), \
        "an unguarded install must announce itself rather than silently allowing everything"


def test_quiet_when_guard_all_is_on(caplog):
    app = _app()
    KLSwitchExtension(client=Client(rules=make_ruleset())).init_app(app, guard_all=True)
    with caplog.at_level(logging.WARNING, logger="klswitch.flask"):
        app.test_client().get("/items")
    assert not any("no route is guarded" in r.message for r in caplog.records)


def test_warns_only_once(caplog):
    app = _app()
    KLSwitchExtension(client=Client(rules=make_ruleset())).init_app(app)
    with caplog.at_level(logging.WARNING, logger="klswitch.flask"):
        for _ in range(5):
            app.test_client().get("/items")
    hits = [r for r in caplog.records if "no route is guarded" in r.message]
    assert len(hits) == 1, f"expected one warning, got {len(hits)}"
