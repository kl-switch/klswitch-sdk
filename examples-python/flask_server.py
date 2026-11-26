"""Flask demo server — Python sync counterpart to ``examples/express-server.ts``,
``examples-java`` and ``examples-go``.

Wires :class:`klswitch.Client` to a Flask app via the SDK's
:class:`KLSwitchExtension`. The default ``result_handler`` matches the Node /
Java / Go demos' block-on-block policy, so the same curl block in
``README.md`` produces the same status codes + matched ids across all five
servers.

Run::

    python flask_server.py            # listens on :8087
    PORT=9000 python flask_server.py  # custom port
"""

from __future__ import annotations

import os
from typing import Any

import flask

from klswitch import AnalyticsOptions, Client
from klswitch.adapters.flask import KLSwitchExtension

from demo_rules import DEMO_RULES


def build_app(client: Client) -> flask.Flask:
    """Construct the Flask app. Split out so tests can wire their own client."""
    app = flask.Flask(__name__)
    # Bound the read at the framework layer — covers Transfer-Encoding: chunked
    # uploads where the SDK's Content-Length check can't see the size.
    app.config["MAX_CONTENT_LENGTH"] = 1 << 20  # 1 MiB

    ks_ext = KLSwitchExtension(app, client=client)

    @app.post("/v1/payments/<id>")
    @ks_ext.guard()
    def payment(id: str) -> flask.Response:
        return flask.jsonify(_ok({"paymentId": id}))

    @app.get("/v1/users/<id>")
    @ks_ext.guard()
    def user(id: str) -> flask.Response:
        return flask.jsonify(_ok({"userId": id}))

    @app.get("/health")
    def health() -> flask.Response:
        return flask.jsonify({"ok": True, "status": _status(client)})

    return app


# ────────────────────────────────────────────────────────────────────────────
# response shape helpers — match the Node / Java / Go demos byte-for-byte
# ────────────────────────────────────────────────────────────────────────────


def _ok(extra: dict[str, Any]) -> dict[str, Any]:
    result = getattr(flask.g, "klswitch", None)
    matched = (
        [{"id": m.id, "action": m.action.type} for m in result.matched]
        if result is not None
        else []
    )
    return {"ok": True, "matched": matched, **extra}


def _status(client: Client) -> dict[str, Any]:
    s = client.get_status()
    return {
        "initialized": s.initialized,
        "activeRulesVersion": s.active_rules_version,
        "ruleCount": s.rule_count,
        "lastLoadOk": s.last_load_ok,
        "lastRefreshOk": s.last_refresh_ok,
        "lastRefreshError": s.last_refresh_error,
    }


def main() -> None:
    port = int(os.environ.get("PORT", "8087"))
    # When KS_TOKEN is set, point at a real backend (klswitch) and emit analytics; the
    # per-(service,env) API key lets the backend derive service + env. Otherwise run
    # fully local off DEMO_RULES. Mirrors the Node / Go / Java / FastAPI demos.
    token = os.environ.get("KS_TOKEN")
    if token:
        client = Client(
            rules=DEMO_RULES,
            token=token,
            base_url=os.environ.get("KS_BASE_URL", "http://localhost:40010"),
            analytics=AnalyticsOptions(batch_size=1, flush_interval=0.5),
        )
    else:
        client = Client(rules=DEMO_RULES)
    app = build_app(client)
    try:
        # Werkzeug dev server — production deployments should put gunicorn /
        # waitress / uwsgi in front. The example just needs to be runnable.
        app.run(host="0.0.0.0", port=port, debug=False, use_reloader=False)
    finally:
        client.close()


if __name__ == "__main__":
    main()
