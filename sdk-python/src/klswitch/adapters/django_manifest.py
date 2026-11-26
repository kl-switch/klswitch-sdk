"""Django route discovery.

Django exposes its registered URL patterns through :func:`django.urls.get_resolver`
— a tree of :class:`django.urls.URLResolver` (nested ``include()`` calls)
and :class:`django.urls.URLPattern` (leaf views). We walk that tree
recursively, building the templated path as we descend.

Path-template canonicalisation: Django's ``path()`` syntax uses
``<int:id>`` / ``<str:slug>`` / ``<path:filename>`` which canonicalise
to ``:id`` / ``:slug`` / ``:filename`` — same target shape as the
Flask discoverer (and the same helper).

Regex patterns (``re_path()``) and bare regex patterns aren't reversible
to a stable template; we surface them with ``method="ANY"`` and the
raw regex source as the path so they at least appear in the console
list, but operators authoring rules against them is best-effort. v1
ships the path-based discovery as the primary path; ``re_path`` is
opt-in via the ``include_regex_patterns`` flag.

HTTP-method detection:

* **Class-based views** — the callback is the output of ``as_view()`` and
  carries a ``view_class`` attribute. We intersect ``view_class.http_method_names``
  with the methods actually defined on the class (``get`` / ``post`` /
  ``put`` / ``delete`` / ``patch`` / ``head`` / ``options``).
* **Function-based views** — the framework can't tell which verbs the
  view accepts (the operator gates via ``request.method`` inside the
  body). We emit a single ``"ANY"`` row; the console treats it as
  "matches every method" when picking rule selectors.

If Django isn't installed the import side has no cost — we only import
the resolver lazily inside :func:`discover_django_routes`.
"""

from __future__ import annotations

from typing import Any

from .._manifest import ManifestRoute, ManifestSource
from .._request_context import flask_template_to_engine_path

_HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}


def django_manifest_source(
    urlconf: str | None = None,
    *,
    include_regex_patterns: bool = False,
) -> ManifestSource:
    """Return a :class:`ManifestSource` that walks the Django URL conf.

    ::

        from klswitch import Client
        from klswitch.adapters.django_manifest import django_manifest_source

        ks = Client(
            token=os.environ["KS_TOKEN"],
            manifest=django_manifest_source(),
        )

    ``urlconf`` mirrors Django's :func:`get_resolver` argument — pass the
    dotted module path of your root URL conf (e.g. ``"myproject.urls"``)
    if it isn't the one in ``settings.ROOT_URLCONF``. ``None`` (the
    default) uses Django's configured root.

    ``include_regex_patterns`` enables surfacing :func:`re_path` entries
    too. Their path is the regex source which doesn't round-trip cleanly
    to the ``:id`` form used by rule selectors, so the console treats
    them as "best-effort". Off by default.
    """
    return ManifestSource(
        framework="django",
        collect=lambda: discover_django_routes(
            urlconf, include_regex_patterns=include_regex_patterns,
        ),
    )


def discover_django_routes(
    urlconf: str | None = None,
    *,
    include_regex_patterns: bool = False,
) -> list[ManifestRoute]:
    """Exposed for tests. Walks the resolver returned by
    :func:`django.urls.get_resolver` and emits one
    :class:`ManifestRoute` per ``(method, templated-path)`` pair.

    Returns an empty list if Django isn't installed or the resolver
    can't be obtained — the manifest reporter logs the empty push
    and the console falls back to free-text route entry. No regression.
    """
    try:
        from django.urls import get_resolver
    except ImportError:
        return []
    try:
        resolver = get_resolver(urlconf)
    except Exception:  # noqa: BLE001  – defensive; partial settings, etc.
        return []
    out: list[ManifestRoute] = []
    try:
        _walk(resolver, "", out, include_regex_patterns)
    except Exception:  # noqa: BLE001  – url_patterns is lazy: a bad urlconf
        # raises ImportError on first access, after get_resolver succeeded.
        return []
    return out


def _walk(
    resolver: Any,
    prefix: str,
    out: list[ManifestRoute],
    include_regex_patterns: bool,
) -> None:
    """Recursive descent over the URLResolver tree."""
    patterns = getattr(resolver, "url_patterns", None)
    if not patterns:
        return
    for entry in patterns:
        nested = getattr(entry, "url_patterns", None)
        if nested is not None:
            # URLResolver — include()'d sub-conf. Combine prefixes and recurse.
            segment = _pattern_template(entry.pattern, include_regex_patterns)
            if segment is None:
                continue   # unreadable regex include — skip its whole subtree
            _walk(entry, _join(prefix, segment), out, include_regex_patterns)
            continue
        # URLPattern (leaf)
        segment = _pattern_template(entry.pattern, include_regex_patterns)
        if segment is None:
            continue
        raw_path = _join(prefix, segment)
        path = _canonicalise(raw_path)
        callback = getattr(entry, "callback", None)
        methods = _detect_methods(callback)
        handler = _handler_name(callback)
        for method in methods:
            out.append(ManifestRoute(method=method, path=path, handler=handler))


def _pattern_template(pattern: Any, include_regex_patterns: bool) -> str | None:
    """Pull the original template string out of a URL pattern. Returns
    ``None`` when the pattern shape is unreadable.

    Django's :class:`RoutePattern` (the modern ``path()``) exposes the
    template via the private ``_route`` attribute — stable for years.
    :class:`RegexPattern` (``re_path()``) only exposes the compiled
    regex; we surface its source string when ``include_regex_patterns``
    is on, and skip it otherwise.
    """
    # Modern path() — the common case.
    route = getattr(pattern, "_route", None)
    if isinstance(route, str):
        return route
    if not include_regex_patterns:
        return None
    # Legacy re_path() / url() — best-effort.
    regex = getattr(pattern, "_regex", None)
    if isinstance(regex, str):
        return regex
    compiled = getattr(pattern, "regex", None)
    if compiled is not None and hasattr(compiled, "pattern"):
        return str(compiled.pattern)
    return None


def _detect_methods(callback: Any) -> list[str]:
    """Return the uppercase HTTP verbs a callback accepts.

    * Class-based views (output of ``View.as_view()``) carry a
      ``view_class`` attribute. We intersect its ``http_method_names``
      with the methods actually defined on the class.
    * Function-based views can't be introspected — emit ``["ANY"]`` so
      the route still surfaces in the console.
    """
    if callback is None:
        return []
    view_class = getattr(callback, "view_class", None) or getattr(callback, "cls", None)
    if view_class is not None:
        declared = getattr(view_class, "http_method_names", None)
        if declared:
            verbs: list[str] = []
            for v in declared:
                upper = v.upper()
                if upper not in _HTTP_METHODS:
                    continue
                # Filter to methods actually defined on the class — Django's
                # base View.http_method_names lists every verb whether or
                # not the subclass implements it.
                if hasattr(view_class, v):
                    verbs.append(upper)
            return sorted(set(verbs)) if verbs else ["ANY"]
        return ["ANY"]
    return ["ANY"]


def _handler_name(callback: Any) -> str | None:
    if callback is None:
        return None
    view_class = getattr(callback, "view_class", None) or getattr(callback, "cls", None)
    if view_class is not None:
        return view_class.__name__
    name = getattr(callback, "__qualname__", None) or getattr(callback, "__name__", None)
    return name if isinstance(name, str) else None


def _join(prefix: str, segment: str) -> str:
    """Concatenate a resolver prefix and a leaf segment, handling
    leading / trailing slashes.
    """
    a = prefix.rstrip("/")
    b = segment.lstrip("/")
    if not a:
        return b
    if not b:
        return a
    return a + "/" + b


def _canonicalise(raw_path: str) -> str:
    """Convert Django's ``<id>`` / ``<int:id>`` / ``<path:p>`` to
    ``:id``-style and ensure exactly one leading slash.
    """
    converted = flask_template_to_engine_path(raw_path)
    return "/" + converted.lstrip("/")
