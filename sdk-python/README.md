# klswitch (Python SDK)

Python wrapper around the same WASM rule engine as the Node, Java, and Go
SDKs. JSON-only boundary; host-side concerns (polling, analytics, logging,
framework integration) live entirely outside WASM.

```bash
pip install klswitch-sdk
```

## Sync quickstart

```python
from klswitch import Client, RuleSetPayload, EvaluationInput, Selector, Facts

rules = RuleSetPayload(...)  # construct or JSON-decode your ruleset

with Client(rules=rules) as ks:
    result = ks.evaluate(EvaluationInput(
        selector=Selector(kind="api", keys={"method": "POST", "path": "/v1/transfers"}),
        facts=Facts(body={"amount": 250000}),
    ))
    for m in result.matched:
        print(m.id, m.action.type)
```

With remote rules + polling:

```python
import os

# The token is service-scoped + env-scoped at issue time; the backend
# derives the service identity and env from it, so neither needs to be
# repeated in the SDK config.
with Client(
    token=os.environ["KS_TOKEN"],
    # Optional. Falls back through KS_INSTANCE_ID, POD_NAME, then
    # hostname-pid-startMs derivation.
    instance_id=os.environ.get("POD_NAME"),
    # Optional. Defaults to https://api.klswitch.dev. Override for self-hosted.
    base_url="https://api.klswitch.dev",
    refresh_interval=30,           # default 30s; pass 0 to disable polling
    timeout=5,                     # per-request seconds
) as ks:
    ks.evaluate(...)
    ks.refresh()                   # force immediate fetch
    print(ks.get_status().last_refresh_error)
```

The SDK adds these headers to every backend call:

- `Authorization: Bearer <token>`
- `X-Instance-Key: <instance_id>` — auto-derived if you don't pass one
- `X-Env-Hint: <KS_ENV | POD_NAMESPACE | K_SERVICE_REVISION>` — observational
- `User-Agent: klswitch-python/<version>`

### Server-driven polling (v1.1)

The polling manager is conservative about bandwidth: after the first
fetch it does a `HEAD /v1/ruleset` probe and only `GET`s when the
server's `ETag` has changed (sending `If-None-Match` on the GET as
race-safety — server replies `304` if the version flipped back during
the HEAD→GET window).

Every response's `Cache-Control: max-age=<seconds>` retunes the next
wake-up delay, so the backend controls cadence per service/tier without
an SDK config change. The wrapper clamps the server's value to
`[MIN_POLL_INTERVAL_SECONDS=5, MAX_POLL_INTERVAL_SECONDS=3600]`. When
the server omits `ETag` / `Cache-Control` (older self-hosted backend)
the wrapper degrades gracefully to plain `GET` on the configured
`refresh_interval`.

`client.polling_status()` (sync + async) returns a `PollingStatus`
dataclass with `last_etag`, `next_poll_at` (ISO 8601 UTC), and
`current_interval` (seconds).

On consecutive fetch failures the next-wake delay grows exponentially
(`current_interval × 2^n`) with ±25 % jitter, capped at
`max(MAX_BACKOFF_INTERVAL_SECONDS=300, current_interval)` — so a long
server cadence is never retried *more* aggressively on failure than on
success. First successful fetch resets the schedule; the active
ruleset is preserved across the failure window (engine keeps
last-known-good).

### Instance liveness

There is no separate heartbeat channel. The ruleset poll *is* the liveness
signal: an instance that is polling is alive, and its `X-Instance-Key` header
identifies it, so the console can count healthy instances and surface version
drift after a publish without a second timer or a second endpoint.

### Decision telemetry

Per-evaluation analytics events are emitted to `/v1/decisions` when
`analytics=AnalyticsOptions(...)` is configured. Each event matches the
frozen console contract — one event per `evaluate()` call (including
no-match cases), carrying the templated request shape, matched-rule
list with action types, latency bucket, and a rolling error counter.

- **One event per `evaluate()`** — including `matched=[]` for no-match.
- **Single bounded ring buffer** (default 10 000 events) — drop-oldest
  on overflow keeps the most-recent activity visible to the dashboard.
- **Single-flight gzipped POST** — at most one POST in flight at a
  time. Body is gzipped; payload contains an envelope plus up to 100
  events.
- **Drop-batch on failure, no retry** — retry without an idempotency
  key would risk double-counting. Lost batches show up as
  `events_dropped` / `batches_failed` on `client.analytics_status()`.
- **Custom sink** — pass `AnalyticsOptions(sink=...)` to bypass the
  HTTP POST entirely (events flow into the operator's own analytics
  pipeline).
- **Observability** — `client.analytics_status()` /
  `await async_client.analytics_status()` return an `AnalyticsStatus`
  dataclass with `buffer_size`, `events_recorded`, `events_dropped`,
  `events_sent`, `batches_failed`, `last_flush_at`, `last_flush_ok`,
  `last_flush_error`, `consecutive_failures`.
- **Schema-drift Layer 3 — missing fields.** After every `evaluate()`
  the wrapper walks each matched rule's condition tree against the
  request input and stamps a deduplicated, sorted `missing_fields:
  list[str]` on the `AnalyticsEvent` for fields the rule referenced
  but the request didn't carry. Console flags those rules as
  "drifted from the live schema". Walk is wrapper-side only — no
  engine change.

### Boot-time disk cache (v1.1)

Operator opts in via `cache_path=` (sync `Client` + `AsyncClient`).
Every successful remote fetch persists the accepted ruleset JSON
there (atomic via `Path.replace`), and on init — if the first fetch
fails AND the file exists — the SDK loads it and serves traffic
against last-known-good while polling keeps retrying the backend in
the background.

```python
from klswitch import Client
from klswitch._rules_cache import default_cache_path

ks = Client(
    token=os.environ["KS_TOKEN"],
    cache_path=default_cache_path(os.environ.get("KS_ENV", "prod")),
)
```

`default_cache_path(env)` returns `${tmpdir}/ks-cache-<env>.json`.
Operators with stronger durability needs can pass their own path
(e.g. a mounted volume). A corrupt or empty cache file is ignored
defensively — init only succeeds when EITHER a fresh fetch OR a
valid cached payload is available.

### API manifest reporting

Pass a framework-specific source (e.g. `fastapi_manifest_source(app)`)
and the SDK pushes the discovered route list to `/v1/manifest` at init.
This powers the console's route picker on `/rules/new`, field
autocomplete in the condition editor, and Layer 4 of schema-drift
detection ("rule X targets a route no live instance has").

```python
from klswitch import AsyncClient
from klswitch.adapters.fastapi_manifest import fastapi_manifest_source

ks = AsyncClient(
    token=os.environ["KS_TOKEN"],
    manifest=fastapi_manifest_source(app),  # discovers all registered routes
)
await ks.start()

# Optional: re-run discovery + push if the registry changed at runtime.
await ks.report_manifest()
```

Flask apps use `flask_manifest_source(app)`. The walker reads
`app.url_map.iter_rules()`, drops Flask's built-in `static` endpoint,
and filters Werkzeug's auto-paired `HEAD` / `OPTIONS` verbs so the
console route picker stays uncluttered (explicit HEAD- or OPTIONS-only
routes still surface).

```python
from klswitch import Client
from klswitch.adapters.flask_manifest import flask_manifest_source

ks = Client(
    token=os.environ["KS_TOKEN"],
    manifest=flask_manifest_source(app),
)
```

Django apps use `django_manifest_source()` — the discoverer pulls
`django.urls.get_resolver(urlconf).url_patterns` and walks recursively
through nested `include()`s. Function-based views surface as `ANY`
(Django can't tell which verbs they accept); class-based views emit
one row per declared verb. `re_path` entries are skipped by default
(their regex source doesn't round-trip to the `:id` form rules use) —
pass `include_regex_patterns=True` to surface them.

```python
from klswitch import Client
from klswitch.adapters.django_manifest import django_manifest_source

ks = Client(
    token=os.environ["KS_TOKEN"],
    manifest=django_manifest_source(),     # uses settings.ROOT_URLCONF
)
```

The sync `Client` exposes the same `manifest=` kwarg and a sync
`client.report_manifest()`.

- **Push policy** — once at init; backend dedups by `hash` so a
  steady-state app stays at one POST per startup. Call
  `client.report_manifest()` after registering routes at runtime (dev
  hot-reload, dynamic plugins) — no-ops if the hash is unchanged.
- **Opt-out** — omit `manifest=`, or pass `report_manifest=False` to
  skip even when a source is supplied. Console falls back to free-text
  route entry — same UX as before, no regression.
- **Failure policy** — log + counter bump. A failed push never blocks
  `Client(...)` or `AsyncClient.start()` and never disturbs the
  customer's startup.
- **Observability** — `client.manifest_status()` /
  `async_client.manifest_status()` return a `ManifestStatus` dataclass
  with `framework`, `last_reported_at`, `last_hash`, `last_push_ok`,
  `last_push_error`, `route_count`, `push_attempts`,
  `pushes_succeeded`, `pushes_failed`.

## Async quickstart

`AsyncClient` is the asyncio counterpart. `__init__` does sync setup; the
initial fetch + background polling task launch from `async with` (or call
`await ks.start()` explicitly).

```python
from klswitch import AsyncClient

async def main():
    async with AsyncClient(
        token=os.environ["KS_TOKEN"],
        refresh_interval=30,
    ) as ks:
        result = await ks.evaluate(EvaluationInput(...))
        for m in result.matched:
            print(m.id, m.action.type)

asyncio.run(main())
```

Sinks and fetchers can be **either sync or async**. The SDK detects and
dispatches automatically; sync ones are wrapped with `asyncio.to_thread` so
they don't block the event loop:

```python
async def my_async_sink(events):
    await my_api.post("/analytics", json=events)

ks = AsyncClient(
    token=os.environ["KS_TOKEN"],
    analytics=AnalyticsOptions(sink=my_async_sink),
)
```

The sync `Client` rejects coroutine sinks at construction with `ValueError`
(use `AsyncClient` for those).

## Flask adapter

```bash
pip install klswitch-sdk[flask]
```

```python
import flask
from klswitch import Client
from klswitch.adapters.flask import KLSwitchExtension

ks = Client(token=os.environ["KS_TOKEN"], refresh_interval=30)
app = flask.Flask(__name__)
ks_ext = KLSwitchExtension(app, client=ks)

@app.route("/v1/payments/<id>", methods=["POST"])
@ks_ext.guard()
def payment(id):
    # Default policy: a matched `block` rule already short-circuited with
    # 4xx + JSON before we get here. The matched rules are on flask.g.
    result = flask.g.klswitch
    return {"paymentId": id, "matched": [m.id for m in result.matched]}
```

Provide a `result_handler` to override the default block policy or a
`error_handler` to render evaluation failures your own way; both are also
overridable per route via `@ks_ext.guard(result_handler=..., error_handler=...)`.

## FastAPI adapter

```bash
pip install klswitch-sdk[fastapi]
```

```python
from fastapi import Depends, FastAPI, Request
from klswitch import AsyncClient
from klswitch.adapters.fastapi import klswitch_depends

app = FastAPI()
ks = AsyncClient(token=os.environ["KS_TOKEN"], refresh_interval=30)
ks_dep = klswitch_depends(ks)

@app.on_event("startup")
async def _startup(): await ks.start()
@app.on_event("shutdown")
async def _shutdown(): await ks.aclose()

@app.post("/v1/payments/{id}")
async def payment(id: str, request: Request, _=Depends(ks_dep)):
    result = request.state.klswitch
    return {"paymentId": id, "matched": [m.id for m in result.matched]}
```

Same override hooks as Flask:

```python
async def my_handler(result, request):
    for m in result.matched:
        if m.action.type == "block":
            raise HTTPException(429, detail={"rule": m.id, "code": "RATE_LIMITED"})
    return result

ks_dep = klswitch_depends(ks, result_handler=my_handler)
```

## Django adapter

```bash
pip install klswitch-sdk[django]
```

```python
# settings.py
import os
from klswitch import Client

KS_CLIENT = Client(token=os.environ["KS_TOKEN"], refresh_interval=30)

KLSWITCH = {"client": KS_CLIENT}

MIDDLEWARE = [
    "django.middleware.common.CommonMiddleware",
    # ... your other middleware ...
    "klswitch.adapters.django.KlswitchMiddleware",
]
```

```python
# views.py
def payment(request, id):
    # Default policy: a matched `block` rule already short-circuited with
    # the action's statusCode + JSON before we get here. The matched
    # rules are on request.klswitch.
    result = request.klswitch
    return JsonResponse({"paymentId": id, "matched": [m.id for m in result.matched]})
```

The middleware fires in `process_view`, AFTER Django's URL resolver
has populated `request.resolver_match.route` — so the templated path
the engine sees lines up with `<int:id>` → `:id`. Override the default
block-on-block policy via `KLSWITCH["result_handler"]`, or the
evaluation-error handler via `KLSWITCH["error_handler"]`. Body bytes
are capped at 1 MiB by default (pair with Django's
`DATA_UPLOAD_MAX_MEMORY_SIZE` so oversized uploads are rejected
before the middleware sees them).

> **Production note — chunked uploads.** Both adapters cap body reads at 1 MiB
> by inspecting `Content-Length`. Requests using `Transfer-Encoding: chunked`
> have no Content-Length, so the SDK reads the body before its post-read
> length check applies. Bound chunked uploads at the framework layer:
> Flask via `app.config["MAX_CONTENT_LENGTH"]`, FastAPI via your ASGI
> server's body-size flag (uvicorn `--limit-request-body`).

## Status

- **Phase P1** — foundation: types, wasmtime bridge, basic `Client` lifecycle, compliance fixture tests ✅
- **Phase P2** — polling, logging, analytics (sync) ✅
- **Phase P3** — `AsyncClient` (async polling + analytics + fetcher) ✅
- **Phase P4** — Web framework adapters (Flask + FastAPI) ✅
- **Phase P5** — Example server + integration recipes ✅ *(in `../examples-python/`)*
- **Phase P6** — Polish + cross-language parity ✅ *(see `../docs/cross-language-parity.md`)*

## Architecture notes

- WASM runtime: **wasmtime-py** (Bytecode Alliance). Prebuilt wheels for
  Linux/macOS/Windows × x86_64/arm64. Python doesn't have a credible
  pure-language WASM runtime; this is the tradeoff.
- Concurrency:
  - Sync `Client` uses a single `threading.Lock` around the engine, matching
    the Java `synchronized` and Go `sync.Mutex` patterns.
  - `AsyncClient` keeps the bridge call synchronous inside `async def evaluate`
    — engine work is sub-millisecond and brief loop-blocking is acceptable for
    typical inputs. Override with `asyncio.to_thread` at the call site if a
    benchmark says you need to.
- Defaults aligned with the Java + Go wrappers (5 s refresh timeout, 1 MiB
  body cap, 60 s backoff cap, ±25 % jitter, 50-event analytics batch, 10 000
  queue cap) so cross-language behaviour stays comparable.
- Compliance: replays the shared `spec/compliance/v1/cases/*.json` fixtures
  the Rust engine, Node wrapper, Java wrapper, and Go wrapper all match.
- Coverage: `pytest --cov=klswitch` reports **91 %** line coverage
  (1181 statements, 103 uncovered — most of the uncovered lines are stdlib
  logging-adapter passthroughs and FastAPI dependency error paths).
- Wheel matrix: the SDK wheel itself is `py3-none-any` (pure Python — the
  `.wasm` artefact is platform-agnostic). The native side comes from
  `wasmtime-py`, which publishes prebuilt wheels for Linux x86_64/aarch64
  (manylinux + musllinux), macOS x86_64/arm64, Windows x86_64/arm64, and
  Android — covering every realistic v1 server target without a build step.
