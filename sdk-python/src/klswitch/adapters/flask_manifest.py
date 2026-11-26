"""Flask route discovery.

Flask's URL map is exposed as ``app.url_map.iter_rules()`` — each
:class:`werkzeug.routing.Rule` carries:

  - ``rule``    : str  (templated, Werkzeug ``<id>`` / ``<int:id>`` syntax)
  - ``methods`` : set[str] | None
  - ``endpoint``: str  (the operator's endpoint name, e.g. ``"payments.create"``)

We convert ``<id>`` / ``<int:id>`` / ``<path:filename>`` → ``:id`` /
``:id`` / ``:filename`` via the same helper the request-context translator
uses, so the manifest matches the rule selector convention used across
wrappers.

Flask quirks the walker filters out:

  - The built-in ``static`` endpoint (``/static/<path:filename>``). Operators
    don't author rules against the static file server.
  - Auto-added ``HEAD`` (Werkzeug pairs it with every GET) and auto-added
    ``OPTIONS`` (Werkzeug adds it to every rule). Both rarely appear in
    rule authoring; surfacing them clutters the console's route picker.
    Explicit HEAD/OPTIONS-only routes still surface — only the auto-added
    pairing on a GET / non-HEAD-OPTIONS rule is stripped.

If Flask isn't installed the import side has no cost — we duck-type
``app.url_map.iter_rules()`` at discovery time.
"""

from __future__ import annotations

from typing import Any

from .._manifest import ManifestRoute, ManifestSource
from .._request_context import flask_template_to_engine_path

_HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}


def flask_manifest_source(app: Any) -> ManifestSource:
    """Returns a :class:`ManifestSource` that walks the Flask app's
    ``app.url_map.iter_rules()`` registry.

    ::

        from klswitch import Client
        from klswitch.adapters.flask_manifest import flask_manifest_source

        ks = Client(
            token=os.environ["KS_TOKEN"],
            manifest=flask_manifest_source(app),
        )
    """
    return ManifestSource(
        framework="flask",
        collect=lambda: discover_flask_routes(app),
    )


def discover_flask_routes(app: Any) -> list[ManifestRoute]:
    """Exposed for tests. Walks ``app.url_map.iter_rules()`` and emits
    one :class:`ManifestRoute` per surviving ``(method, path)`` pair.

    The built-in ``static`` endpoint and Flask's auto-paired
    HEAD / OPTIONS verbs are filtered — see the module docstring for the
    rationale.
    """
    url_map = getattr(app, "url_map", None)
    if url_map is None:
        return []
    iter_rules = getattr(url_map, "iter_rules", None)
    if not callable(iter_rules):
        return []

    out: list[ManifestRoute] = []
    for rule in iter_rules():
        endpoint = getattr(rule, "endpoint", None)
        if endpoint == "static":
            continue
        raw_path = getattr(rule, "rule", None)
        if not isinstance(raw_path, str):
            continue
        methods = getattr(rule, "methods", None)
        if not methods:
            continue
        canonical_path = flask_template_to_engine_path(raw_path)

        upper = {str(m).upper() for m in methods}
        for method in _filter_auto_added(upper):
            if method not in _HTTP_METHODS:
                continue
            out.append(ManifestRoute(
                method=method,
                path=canonical_path,
                handler=endpoint if isinstance(endpoint, str) else None,
            ))
    return out


def _filter_auto_added(methods: set[str]) -> list[str]:
    """Drop the methods Werkzeug auto-attaches:

    - ``OPTIONS`` always (Werkzeug adds it to every rule).
    - ``HEAD`` when ``GET`` is also present (Werkzeug pairs HEAD with GET).

    A rule registered with ``methods=["HEAD"]`` or ``methods=["OPTIONS"]``
    keeps that verb — the filter only fires when the auto-pair pattern is
    detected.
    """
    keep = set(methods)
    if "GET" in keep:
        keep.discard("HEAD")
    # Drop OPTIONS unless it's the only method (explicit OPTIONS-only route).
    if "OPTIONS" in keep and len(keep) > 1:
        keep.discard("OPTIONS")
    return sorted(keep)
