"""Django middleware adapter integration tests.

Spins up a tiny Django app in-process — no real server — and drives
requests through the test client. The middleware sees the URL pattern
via ``request.resolver_match.route`` (populated by Django's URL
resolver), evaluates against an inline ruleset, and applies the
canonical block-on-block policy + flag/notify pass-through.
"""

from __future__ import annotations

import json

import django
import pytest
from django.conf import settings as django_settings
from django.http import HttpResponse, JsonResponse
from django.test import Client as DjangoClient
from django.urls import path

from klswitch import (
    Action,
    Client,
    Rule,
    RuleSetPayload,
    Selector,
)


# ─── Django app setup (one-time, module-level) ───────────────────────────


def _ruleset() -> RuleSetPayload:
    return RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="block_large_payment",
                name="block large payment",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 50000}]},
                action=Action(type="block", payload={"statusCode": 429, "code": "PAYMENT_TOO_LARGE"}),
            ),
            Rule(
                id="flag_admin",
                name="flag admin",
                selector=Selector(kind="api", keys={"method": "GET", "path": "/v1/users/:id"}),
                enabled=True,
                priority=50,
                condition={"all": [{"field": "query.role", "operator": "eq", "value": "admin"}]},
                action=Action(type="flag"),
            ),
        ],
    )


# Single shared Client across all tests in this module — it owns one
# WASM engine instance, which is expensive to create. (Django settings
# can only be configured once anyway.)
_KS_CLIENT = Client(rules=_ruleset())


# Module-level views (Django imports settings via dotted path so views
# need to be defined where settings.ROOT_URLCONF can find them — easier
# to inline the urlconf via settings.configure).

def _payment_view(request, id):  # noqa: A002 - matches Django convention
    # Confirms the middleware attached the result before the view ran.
    result = getattr(request, "klswitch", None)
    if result is None:
        return HttpResponse("missing result attr", status=500)
    return JsonResponse({"paymentId": id, "matched": [m.id for m in result.matched]})


def _user_view(request, id):  # noqa: A002
    result = getattr(request, "klswitch", None)
    if result is None:
        return HttpResponse("missing result attr", status=500)
    return JsonResponse({"userId": id, "matched": [m.id for m in result.matched]})


urlpatterns = [
    path("v1/payments/<str:id>", _payment_view),
    path("v1/users/<str:id>", _user_view),
]


def _configure_django_once() -> None:
    # Django settings is one-shot; another test module (e.g.
    # test_django_manifest) may have configured first. Patch the
    # KLSWITCH key + ROOT_URLCONF on the configured-settings object
    # so the middleware tests still see our client + our urlpatterns
    # regardless of which module loaded first.
    if django_settings.configured:
        django_settings.ROOT_URLCONF = __name__
        django_settings.KLSWITCH = {"client": _KS_CLIENT}
        django_settings.MIDDLEWARE = [
            "klswitch.adapters.django.KlswitchMiddleware",
        ]
        # Force the resolver cache to drop the prior urlconf so the
        # new ROOT_URLCONF is picked up.
        from django.urls import clear_url_caches
        clear_url_caches()
        return
    django_settings.configure(
        DEBUG=False,
        ALLOWED_HOSTS=["*"],
        ROOT_URLCONF=__name__,  # this module's urlpatterns
        SECRET_KEY="x" * 50,
        DATABASES={},
        INSTALLED_APPS=[],
        MIDDLEWARE=[
            "klswitch.adapters.django.KlswitchMiddleware",
        ],
        KLSWITCH={"client": _KS_CLIENT},
    )
    django.setup()


@pytest.fixture(scope="module", autouse=True)
def _django_setup():
    _configure_django_once()


@pytest.fixture
def http() -> DjangoClient:
    return DjangoClient()


# ─── Tests ────────────────────────────────────────────────────────────────


def test_block_action_short_circuits_with_status_code(http: DjangoClient) -> None:
    resp = http.post(
        "/v1/payments/abc",
        data=json.dumps({"amount": 70000}),
        content_type="application/json",
    )
    assert resp.status_code == 429, (
        f"expected 429 from action.payload.statusCode; got {resp.status_code} body={resp.content!r}"
    )
    body = json.loads(resp.content)
    assert body["blocked"] is True
    assert body["ruleId"] == "block_large_payment"


def test_block_default_status_code_is_403() -> None:
    """When action.payload.statusCode is absent, the response defaults to 403."""
    custom_ks = Client(rules=RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="block_default",
                name="default",
                selector=Selector(kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}),
                enabled=True,
                priority=100,
                condition={"all": [{"field": "body.amount", "operator": "gt", "value": 0}]},
                action=Action(type="block"),  # no payload
            ),
        ],
    ))
    # Swap the global config for this test. Django doesn't support
    # per-request settings, so we patch in place.
    orig = django_settings.KLSWITCH
    django_settings.KLSWITCH = {"client": custom_ks}
    try:
        resp = DjangoClient().post(
            "/v1/payments/abc",
            data=json.dumps({"amount": 1}),
            content_type="application/json",
        )
        assert resp.status_code == 403
    finally:
        django_settings.KLSWITCH = orig
        custom_ks.close()


def test_small_payment_passes_through_to_view(http: DjangoClient) -> None:
    resp = http.post(
        "/v1/payments/abc",
        data=json.dumps({"amount": 5}),  # below threshold
        content_type="application/json",
    )
    assert resp.status_code == 200
    body = json.loads(resp.content)
    assert body == {"paymentId": "abc", "matched": []}


def test_flag_action_passes_through_with_result_attached(http: DjangoClient) -> None:
    resp = http.get("/v1/users/42?role=admin")
    assert resp.status_code == 200
    body = json.loads(resp.content)
    assert body == {"userId": "42", "matched": ["flag_admin"]}


def test_no_query_match_returns_empty_matched(http: DjangoClient) -> None:
    resp = http.get("/v1/users/42?role=user")
    assert resp.status_code == 200
    body = json.loads(resp.content)
    assert body == {"userId": "42", "matched": []}


def test_repeated_query_param_takes_first_value() -> None:
    """Cross-language parity gate: repeated query keys → first value
    (matches Flask / Java / Go / Node)."""
    from klswitch import Action, Rule, RuleSetPayload, Selector

    custom_ks = Client(rules=RuleSetPayload(
        schema_version="1.0",
        rules_version=1,
        rules=[
            Rule(
                id="role_is_admin",
                name="role is admin",
                selector=Selector(kind="api", keys={"method": "GET", "path": "/q"}),
                enabled=True, priority=10,
                condition={"all": [{"field": "query.role", "operator": "eq", "value": "admin"}]},
                action=Action(type="flag"),
            ),
        ],
    ))
    orig = django_settings.KLSWITCH
    django_settings.KLSWITCH = {"client": custom_ks}
    try:
        # Need a urlconf entry for /q. We register it dynamically by
        # extending the module-level urlpatterns + clearing Django's
        # URL cache so the new pattern picks up.
        from django.urls import clear_url_caches
        urlpatterns.append(path(
            "q",
            lambda req: JsonResponse({"matched": [m.id for m in req.klswitch.matched]}),
        ))
        clear_url_caches()
        try:
            # admin then user → first ("admin") wins → rule fires.
            r1 = DjangoClient().get("/q?role=admin&role=user")
            assert r1.status_code == 200
            assert json.loads(r1.content) == {"matched": ["role_is_admin"]}

            # user then admin → first ("user") wins → rule does not fire.
            r2 = DjangoClient().get("/q?role=user&role=admin")
            assert r2.status_code == 200
            assert json.loads(r2.content) == {"matched": []}
        finally:
            urlpatterns.pop()
            clear_url_caches()
    finally:
        django_settings.KLSWITCH = orig
        custom_ks.close()


def test_malformed_json_body_does_not_crash(http: DjangoClient) -> None:
    """A broken body must not 500 before any rule runs — engine then
    sees no body facts (missing field → leaf false)."""
    resp = http.post(
        "/v1/payments/abc",
        data="{not valid json",
        content_type="application/json",
    )
    # The middleware silently dropped the body for fact extraction; the
    # view still ran (matched: []) — no abort.
    assert resp.status_code == 200
    body = json.loads(resp.content)
    assert body["matched"] == []


def test_non_json_content_type_skips_body_extraction(http: DjangoClient) -> None:
    resp = http.post(
        "/v1/payments/abc",
        data="amount=70000",
        content_type="application/x-www-form-urlencoded",
    )
    # Non-JSON body → no body facts → no match → no abort.
    assert resp.status_code == 200


def test_missing_settings_raises_at_middleware_init() -> None:
    """If settings.KLSWITCH is missing or doesn't contain a Client,
    the middleware constructor must fail clearly rather than silently
    pass requests through."""
    from klswitch.adapters.django import KlswitchMiddleware

    orig = django_settings.KLSWITCH
    django_settings.KLSWITCH = {}
    try:
        with pytest.raises(RuntimeError, match="client.* must be a klswitch.Client"):
            KlswitchMiddleware(get_response=lambda r: None)
    finally:
        django_settings.KLSWITCH = orig
