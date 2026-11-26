"""Django manifest discovery tests.

Walks a hand-crafted ``urlpatterns`` list (function-based + class-based
views, nested ``include()``, ``re_path``) and asserts the discoverer
emits the right ``(method, templated-path)`` rows.

Settings note: Django allows ``settings.configure(...)`` exactly once
per process. ``test_django_adapter.py`` configures its own ROOT_URLCONF
at module load. This file uses the same configure-once guard and passes
``urlconf=__name__`` to ``discover_django_routes`` so its own
``urlpatterns`` get walked regardless of which test module loaded first.
"""

from __future__ import annotations

import django
from django.conf import settings as django_settings
from django.http import HttpResponse
from django.urls import include, path, re_path
from django.views import View

from klswitch.adapters.django_manifest import (
    discover_django_routes,
    django_manifest_source,
)


# ─── Django bootstrap (idempotent) ───────────────────────────────────────


def _configure_django_once() -> None:
    if django_settings.configured:
        return
    django_settings.configure(
        DEBUG=False,
        ALLOWED_HOSTS=["*"],
        ROOT_URLCONF=__name__,
        SECRET_KEY="x" * 50,
        DATABASES={},
        INSTALLED_APPS=[],
        MIDDLEWARE=[],
    )
    django.setup()


_configure_django_once()


# ─── Views ───────────────────────────────────────────────────────────────


def _fbv(request):       # function-based view → emits a single "ANY"
    return HttpResponse("ok")


class PaymentView(View):
    def get(self, request, id):  # noqa: A002
        return HttpResponse(id)

    def post(self, request, id):  # noqa: A002
        return HttpResponse(id)


class HealthView(View):
    # Default http_method_names lists every verb; we only define get/head/options.
    def get(self, request):
        return HttpResponse("ok")


# Nested sub-conf used through include().
def _sub_view(request, slug):
    return HttpResponse(slug)


_sub_patterns = [
    path("articles/<slug:slug>", _sub_view),
]


# Root urlpatterns — referenced via ROOT_URLCONF=__name__.
urlpatterns = [
    path("v1/users/<str:id>", _fbv),
    path("v1/payments/<str:id>", PaymentView.as_view()),
    path("healthz", HealthView.as_view()),
    path("v1/blog/", include(_sub_patterns)),
    re_path(r"^v1/legacy/(?P<x>\d+)$", _fbv),
]


# ─── Helpers ─────────────────────────────────────────────────────────────


def _pairs(routes) -> set[str]:
    return {f"{r.method} {r.path}" for r in routes}


# ─── Tests ───────────────────────────────────────────────────────────────


def test_discover_walks_function_based_view_with_any_verb() -> None:
    routes = discover_django_routes(__name__)
    assert "ANY /v1/users/:id" in _pairs(routes)


def test_discover_walks_class_based_view_with_declared_verbs() -> None:
    routes = discover_django_routes(__name__)
    pairs = _pairs(routes)
    # PaymentView defines get + post — those should surface.
    assert "GET /v1/payments/:id" in pairs
    assert "POST /v1/payments/:id" in pairs
    # PaymentView does NOT define delete/put — those must not appear.
    assert "DELETE /v1/payments/:id" not in pairs
    assert "PUT /v1/payments/:id" not in pairs


def test_discover_filters_to_methods_actually_defined_on_view_class() -> None:
    """``View.http_method_names`` lists every verb whether the subclass
    implements it or not. The discoverer must only emit verbs the
    subclass actually has on its MRO.

    HealthView defines only ``get``. Django's base ``View`` ships an
    ``options`` implementation (it returns the Allow header), so
    HealthView inherits an OPTIONS handler — the manifest accurately
    reflects that. ``head`` is NOT defined on the base View (Django
    routes HEAD to GET at dispatch time), so the discoverer doesn't
    emit it.
    """
    routes = discover_django_routes(__name__)
    health_methods = {r.method for r in routes if r.path == "/healthz"}
    assert health_methods == {"GET", "OPTIONS"}, health_methods


def test_discover_descends_include_sub_conf() -> None:
    routes = discover_django_routes(__name__)
    # /v1/blog/ + /articles/<slug:slug> → /v1/blog/articles/:slug
    assert "ANY /v1/blog/articles/:slug" in _pairs(routes)


def test_discover_skips_regex_patterns_by_default() -> None:
    routes = discover_django_routes(__name__)
    # The re_path entry must not surface unless opt-in.
    for r in routes:
        assert "legacy" not in r.path, (
            f"re_path should be excluded by default; got {r.path}"
        )


def test_discover_includes_regex_patterns_when_opted_in() -> None:
    routes = discover_django_routes(__name__, include_regex_patterns=True)
    paths = {r.path for r in routes}
    # The regex source is surfaced verbatim — we don't pretend to round-trip
    # it to template form. Operator at least sees it in the route picker.
    assert any("legacy" in p for p in paths), paths


def test_discover_emits_handler_name() -> None:
    routes = discover_django_routes(__name__)
    payment_post = next(
        r for r in routes if r.path == "/v1/payments/:id" and r.method == "POST"
    )
    assert payment_post.handler == "PaymentView"
    # Function-based view → fully-qualified name.
    fbv_get = next(r for r in routes if r.path == "/v1/users/:id")
    assert fbv_get.handler is not None and "_fbv" in fbv_get.handler


def test_manifest_source_framework_is_django() -> None:
    src = django_manifest_source(urlconf=__name__)
    assert src.framework == "django"
    routes = src.collect()
    assert len(routes) > 0


def test_discover_with_bad_urlconf_returns_empty() -> None:
    """Non-existent urlconf module — get_resolver raises, we swallow."""
    assert discover_django_routes("nonexistent.module.nope") == []


def test_paths_are_canonicalised_with_leading_slash() -> None:
    routes = discover_django_routes(__name__)
    for r in routes:
        assert r.path.startswith("/"), (
            f"every emitted path must have a leading slash; got {r.path!r}"
        )
