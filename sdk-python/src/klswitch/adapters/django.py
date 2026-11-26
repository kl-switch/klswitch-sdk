"""Django middleware adapter.

Wires a :class:`klswitch.Client` into Django's ``MIDDLEWARE`` chain so
every request gets evaluated against the active ruleset before the view
runs. A matched ``block`` action short-circuits with the action's
``statusCode`` (default 403) + the canonical JSON body shape; any other
action (``flag`` / ``notify``) attaches the :class:`EvaluationResult`
to ``request.klswitch`` (configurable) so the view can act on it.

Wire-up::

    # settings.py
    from klswitch import Client
    KS_CLIENT = Client(token=os.environ["KS_TOKEN"], refresh_interval=30)

    KLSWITCH = {"client": KS_CLIENT}

    MIDDLEWARE = [
        "django.middleware.common.CommonMiddleware",
        # ... your other middleware ...
        "klswitch.adapters.django.KlswitchMiddleware",
    ]

The middleware reads the templated route via ``request.resolver_match.route``
which is populated by Django's URL resolver between ``__call__`` and the
view — so we use the ``process_view`` hook to evaluate at exactly the
right moment.

Path templates use Django's ``<id>`` / ``<int:id>`` / ``<path:p>`` syntax
which we canonicalise to ``:id`` (same helper Flask uses) so rule
selectors match across wrappers.

**Production note — chunked uploads.** Body bounding is enforced via
``request.body`` after Django has already buffered the full request.
Pair with ``DATA_UPLOAD_MAX_MEMORY_SIZE`` (Django's own request-size
limit, default 2.5 MiB) so an oversized upload is rejected before
``request.body`` is materialised.
"""

from __future__ import annotations

import json
from typing import Any, Callable

from .._request_context import build_evaluation_input, flask_template_to_engine_path
from ..client import Client
from ..types import EvaluationInput, EvaluationResult, MatchedRule

# Default cap on the bytes read for fact extraction. Matches the
# Flask / FastAPI adapters + Java / Node middleware so behaviour is
# the same across wrappers.
DEFAULT_MAX_BODY_BYTES = 1 << 20  # 1 MiB

# Where the EvaluationResult lands on `request`. Operator can override
# via settings.KLSWITCH["result_attr"].
DEFAULT_RESULT_ATTR = "klswitch"


# ─── Hook types ────────────────────────────────────────────────────────────

# A result-handler returns either an HttpResponse (short-circuit) or None
# (continue to the view). Same shape as Django's own process_view().
ResultHandler = Callable[[Any, EvaluationResult], Any]
# An error-handler is called when client.evaluate itself raises. Returns
# an HttpResponse. Default: 500.
ErrorHandler = Callable[[Any, Exception], Any]


# ─── Middleware ────────────────────────────────────────────────────────────


def _reject_duplicate_middleware(django_settings: Any) -> None:
    """Fail at startup when this middleware is listed in MIDDLEWARE twice.

    Django would happily run both, evaluating every request twice: double the
    latency, and two analytics events per request so decision counts read
    double. That is silent — nothing about the responses looks wrong — so it is
    worth refusing to boot rather than serving quietly-wrong data.

    Reads ``settings.MIDDLEWARE`` rather than counting instantiations: Django
    builds the chain once per handler, so a process serving both WSGI and ASGI,
    or a test suite that re-runs setup, would otherwise trip a counter without
    being misconfigured at all.
    """
    entries = list(getattr(django_settings, "MIDDLEWARE", None) or [])
    ours = f"{__name__}.KlswitchMiddleware"
    # Match on the class name too: subclassing to preset config is a normal
    # Django idiom and would otherwise slip past a plain string compare.
    count = sum(1 for e in entries if e == ours or e.rsplit(".", 1)[-1] == "KlswitchMiddleware")
    if count > 1:
        from django.core.exceptions import ImproperlyConfigured

        raise ImproperlyConfigured(
            f"KlswitchMiddleware appears {count} times in settings.MIDDLEWARE.\n"
            "\n"
            "Every request would be evaluated once per entry — paying the latency "
            "each time and emitting one analytics event each, so decision counts "
            "read multiplied.\n"
            "\n"
            "Keep a single entry.",
        )


class KlswitchMiddleware:
    """Django middleware that evaluates every request against the
    KLSwitch engine before the view runs.

    Reads its configuration from ``settings.KLSWITCH``:

    * ``client`` (required) — a :class:`klswitch.Client` instance the
      operator has already constructed.
    * ``result_handler`` (optional) — overrides the default block-on-block
      policy. Signature: ``(request, result) -> HttpResponse | None``.
    * ``error_handler`` (optional) — overrides the default 500-on-
      evaluate-failure handler.
    * ``result_attr`` (optional) — attribute name to attach the result
      to on ``request``. Default ``"klswitch"``.
    * ``max_body_bytes`` (optional) — cap on body bytes read for fact
      extraction. Default 1 MiB.
    """

    def __init__(self, get_response: Callable[[Any], Any]) -> None:
        # Lazy import so the adapter module is importable even if Django
        # isn't installed (the SDK ships it as an optional extra).
        from django.conf import settings as django_settings

        _reject_duplicate_middleware(django_settings)

        cfg = getattr(django_settings, "KLSWITCH", None) or {}
        client = cfg.get("client")
        if not isinstance(client, Client):
            raise RuntimeError(
                "settings.KLSWITCH['client'] must be a klswitch.Client "
                "instance (got %r)" % type(client).__name__,
            )

        self._get_response = get_response
        self._client: Client = client
        self._result_handler: ResultHandler | None = cfg.get("result_handler")
        self._error_handler: ErrorHandler | None = cfg.get("error_handler")
        self._result_attr: str = cfg.get("result_attr", DEFAULT_RESULT_ATTR)
        self._max_body_bytes: int = int(cfg.get("max_body_bytes", DEFAULT_MAX_BODY_BYTES))

    # ── per-request entry point ──────────────────────────────────────────

    def __call__(self, request: Any) -> Any:
        # The interesting work happens in process_view (after URL resolution).
        # __call__ is required by Django but is a pass-through here.
        return self._get_response(request)

    def process_view(
        self, request: Any, view_func: Any, view_args: tuple, view_kwargs: dict,
    ) -> Any:
        """Fired AFTER Django's URL resolver runs but BEFORE the view —
        the sweet spot where ``request.resolver_match`` is populated
        AND we can short-circuit the view if a rule blocks.
        """
        # Path template: resolver_match.route is the Django pattern string
        # (e.g. "payments/<int:id>", no leading slash). Canonicalise to
        # ":id" form via the existing Flask helper — Django + Flask
        # happen to use the same `<id>` / `<int:id>` syntax.
        if getattr(request, "resolver_match", None) is not None:
            route = request.resolver_match.route or ""
            path = "/" + flask_template_to_engine_path(route).lstrip("/")
        else:
            # Resolver didn't run (test client without urlconf, etc.) —
            # fall back to the concrete URL so we don't break the view.
            path = request.path or "/"

        # Headers: Django exposes request.headers as a case-insensitive
        # mapping. We extract a plain dict; build_evaluation_input
        # lowercases the keys.
        headers = dict(request.headers)
        # Query: first-value-wins on repeats (matches Flask / Java / Go).
        qd = request.GET
        query = {k: qd.getlist(k)[0] for k in qd}

        body = self._read_json_body(request)

        ev_input: EvaluationInput = build_evaluation_input(
            method=request.method,
            path=path,
            headers=headers,
            query=query,
            params=view_kwargs or {},
            body=body,
        )

        try:
            result = self._client.evaluate(ev_input)
        except Exception as exc:  # noqa: BLE001 - defensive; handler decides
            return self._dispatch_error(request, exc)

        setattr(request, self._result_attr, result)

        if self._result_handler is not None:
            return self._result_handler(request, result)
        return self._default_result_policy(request, result)

    # ── helpers ──────────────────────────────────────────────────────────

    def _read_json_body(self, request: Any) -> dict[str, Any] | None:
        """Bounded JSON body read for fact extraction. Returns ``None``
        when the body isn't JSON, is empty, is too large, or fails to
        parse — engine then sees no body facts (missing field → leaf
        false).
        """
        content_type = request.content_type or ""
        if not content_type.startswith("application/json"):
            return None
        # request.body raises if the request stream was already consumed
        # (e.g. by a custom middleware that read POST). Treat as "no body".
        try:
            raw = request.body
        except Exception:  # noqa: BLE001
            return None
        if not raw:
            return None
        if len(raw) > self._max_body_bytes:
            return None
        try:
            parsed = json.loads(raw)
        except (ValueError, TypeError):
            return None
        # Only dict bodies map cleanly onto our `body.<field>` selector
        # convention. Lists / scalars at the root → no body facts.
        return parsed if isinstance(parsed, dict) else None

    def _default_result_policy(self, request: Any, result: EvaluationResult) -> Any:
        """Block-on-block — the same default policy every other adapter
        in the SDK ships. Operator overrides via ``result_handler``.
        """
        for m in result.matched:
            if m.action and m.action.type == "block":
                return _block_response(m)
        return None  # continue to the view

    def _dispatch_error(self, request: Any, exc: Exception) -> Any:
        if self._error_handler is not None:
            return self._error_handler(request, exc)
        return _default_error_response(exc)


# ─── Default responses ─────────────────────────────────────────────────────


def _block_response(rule: MatchedRule) -> Any:
    from django.http import JsonResponse

    status = 403
    if rule.action and rule.action.payload:
        sc = rule.action.payload.get("statusCode")
        if isinstance(sc, (int, float)):
            status = int(sc)
    return JsonResponse(
        {
            "blocked": True,
            "ruleId":  rule.id,
            "action":  {
                "type":    rule.action.type if rule.action else None,
                "payload": (rule.action.payload if rule.action else None),
            },
        },
        status=status,
    )


def _default_error_response(exc: Exception) -> Any:
    from django.http import JsonResponse

    return JsonResponse(
        {"error": "klswitch evaluation failed", "detail": str(exc)},
        status=500,
    )
