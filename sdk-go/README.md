# klswitch (Go SDK)

Go wrapper around the KLSwitch WASM rule engine. Pure Go: no native deps, no
install-time WASM fetch — the engine binary is `//go:embed`-ed into the module.

```bash
go get github.com/kl-switch/klswitch-sdk/sdk-go
```

## Quickstart

```go
import "github.com/kl-switch/klswitch-sdk/sdk-go"

ks, err := klswitch.Init(ctx, klswitch.WithRules(payload))
if err != nil { /* ... */ }
defer ks.Close()

result, err := ks.Evaluate(ctx, klswitch.EvaluationInput{
    Selector: klswitch.Selector{Kind: "api", Keys: map[string]string{"method": "POST", "path": "/v1/transfers"}},
    Facts:    klswitch.NewFacts(),
})
```

## Status

- **Phase G1** — foundation: types, wazero bridge, basic client lifecycle, compliance fixture tests ✅
- **Phase G2** — polling, logging, analytics ✅
- **Phase G3** — `net/http` middleware + request mapping helper ✅ *(in `./httpmiddleware/`)*
- **Phase G4** — example server + integration recipes ✅ *(in `../examples-go/`)*

### Using the middleware

```go
import (
    ks "github.com/kl-switch/klswitch-sdk/sdk-go"
    "github.com/kl-switch/klswitch-sdk/sdk-go/httpmiddleware"
)

client, _ := ks.Init(ctx, ks.WithRules(rs))

// Per-route mount: pass the templated path because Go's ServeMux doesn't
// expose the matched pattern back to handlers.
guard := httpmiddleware.New(client,
    httpmiddleware.WithPathTemplate("/v1/payments/:id"),
)
mux.Handle("POST /v1/payments/{id}", guard(http.HandlerFunc(paymentsHandler)))

// For chi / gorilla/mux that DO track templated routes:
guard := httpmiddleware.New(client,
    httpmiddleware.WithPathFunc(func(r *http.Request) string {
        return chi.RouteContext(r.Context()).RoutePattern()
    }),
)
```

For first-class `chi` and `gorilla/mux` integration there are
dedicated packages that auto-resolve the templated route — no
`WithPathFunc` plumbing required:

```go
// chi — pass the router itself so the middleware can pre-resolve the
// pattern (chi populates RouteContext.RoutePattern AFTER middleware runs,
// so a naive r.Use(httpmiddleware.New(...)) would see an empty pattern).
import "github.com/kl-switch/klswitch-sdk/sdk-go/chimware"
r := chi.NewRouter()
r.Use(chimware.New(r, client))
r.Post("/v1/payments/{id}", paymentsHandler)

// gorilla/mux — middleware runs after mux.CurrentRoute is populated,
// so the wiring is even simpler.
import "github.com/kl-switch/klswitch-sdk/sdk-go/muxware"
m := mux.NewRouter()
m.Use(muxware.New(client))
m.HandleFunc("/v1/payments/{id}", paymentsHandler).Methods("POST")
```

Both packages translate the router's templated path to the canonical
`:id` form so it lines up with the rule selector. Block-action matches
short-circuit the request with the action's `statusCode`; the
`EvaluationResult` is attached to the request context for handlers
that want to inspect non-block matches.

Inside your handler, read the evaluation result:

```go
if result, ok := httpmiddleware.ResultFromContext(r.Context()); ok {
    for _, m := range result.Matched { /* ... */ }
}
```

### Initialize against the backend

```go
// The token is service-scoped + env-scoped at issue time; the backend
// derives the service identity and env from it, so neither needs to be
// repeated in the SDK config.
ks, err := klswitch.Init(ctx,
    klswitch.WithToken(os.Getenv("KS_TOKEN")),
    // Optional. Falls back through KS_INSTANCE_ID, POD_NAME, then
    // hostname-pid-startMs derivation.
    klswitch.WithInstanceID(os.Getenv("POD_NAME")),
    // Optional. Defaults to https://api.klswitch.dev. Override for self-hosted.
    klswitch.WithBaseURL("https://api.klswitch.dev"),
    klswitch.WithRefreshInterval(30*time.Second),     // pass 0 to disable
)
```

The SDK adds these headers to every backend call:

- `Authorization: Bearer <token>`
- `X-Instance-Key: <instanceID>` — auto-derived if you don't pass one
- `X-Env-Hint: <KS_ENV | POD_NAMESPACE | K_SERVICE_REVISION>` — observational
- `User-Agent: klswitch-go/<version>`

### Server-driven polling (v1.1)

The polling manager is conservative about bandwidth: after the first
fetch it does a `HEAD /v1/ruleset` probe and only `GET`s when the
server's `ETag` has changed (sending `If-None-Match` on the GET as
race-safety — server replies `304` if the version flipped back during
the HEAD→GET window).

Every response's `Cache-Control: max-age=<seconds>` retunes the next
wake-up delay, so the backend controls cadence per service/tier without
an SDK config change. The wrapper clamps the server's value to
`[MinPollInterval=5s, MaxPollInterval=1h]`. When the server omits
`ETag` / `Cache-Control` (older self-hosted backend) the wrapper
degrades gracefully to plain `GET` on the configured refresh interval.

`client.PollingStatus()` returns `PollingStatus{LastEtag, NextPollAt,
CurrentInterval}` for operator visibility.

On consecutive fetch failures the next-wake delay grows exponentially
(`currentInterval × 2^n`) with ±25 % jitter, capped at
`max(MaxBackoffInterval=5m, currentInterval)` — so a long server
cadence is never retried *more* aggressively on failure than on
success. First successful fetch resets the schedule; the active
ruleset is preserved across the failure window (engine keeps
last-known-good).

### Instance liveness

There is no separate heartbeat channel. The ruleset poll *is* the liveness
signal: an instance that is polling is alive, and its `X-Instance-Key` header
identifies it, so the console can count healthy instances and surface version
drift after a publish without a second timer or a second endpoint.

### Decision telemetry

Per-evaluation events are emitted to `/v1/decisions` when a token is set.
Each event matches the frozen console contract — one event per
`Evaluate()` call (including no-match cases), carrying the templated
request shape, matched-rule list with action types, latency bucket, and
a rolling error counter.

- **One event per `Evaluate()`** — including `Matched: []` for no-match.
- **Single bounded ring buffer** (default 10 000 events) — drop-oldest on
  overflow keeps the most-recent activity visible to the dashboard.
- **Single-flight gzipped POST** — at most one POST in flight at a time.
- **Drop-batch on failure, no retry** — retry without an idempotency key
  would risk double-counting. Lost batches show up as `EventsDropped` /
  `BatchesFailed` on `Client.AnalyticsStatus()`.
- **Custom sink** — `WithAnalytics(AnalyticsOptions{Sink: ...})` bypasses
  the HTTP POST entirely (events flow into the operator's own analytics
  pipeline).
- **Observability** — `Client.AnalyticsStatus()` returns
  `AnalyticsStatus{BufferSize, EventsRecorded, EventsDropped, EventsSent,
  BatchesFailed, LastFlushAt, LastFlushOk, LastFlushError,
  ConsecutiveFailures}`.
- **Schema-drift Layer 3 — missing fields.** After every `Evaluate()`
  the wrapper walks each matched rule's condition tree against the
  request input and stamps a deduplicated, sorted `MissingFields
  []string` on the `AnalyticsEvent` for fields the rule referenced
  but the request didn't carry. Console flags those rules as
  "drifted from the live schema". Walk is wrapper-side only — no
  engine change.

### Boot-time disk cache (v1.1)

Operator opts in via `WithCachePath`. Every successful remote fetch
persists the accepted ruleset JSON there (atomic via `os.Rename`),
and on Init — if the first fetch fails AND the file exists — the SDK
loads it and serves traffic against last-known-good while polling
keeps retrying the backend in the background.

```go
ks, err := klswitch.Init(ctx,
    klswitch.WithToken(os.Getenv("KS_TOKEN")),
    klswitch.WithCachePath(klswitch.DefaultCachePath(os.Getenv("KS_ENV"))),
)
```

`DefaultCachePath(env)` returns `${tmpdir}/ks-cache-<env>.json`.
Operators with stronger durability needs can pass their own path
(e.g. a mounted volume). A corrupt or empty cache file is ignored
defensively — Init only succeeds when EITHER a fresh fetch OR a valid
cached payload is available.

### API manifest reporting

Pass a framework-specific `ManifestSource` and the SDK pushes the
discovered route list to `/v1/manifest` at init. This powers the
console's route picker on `/rules/new`, field autocomplete in the
condition editor, and Layer 4 of schema-drift detection ("rule X
targets a route no live instance has").

```go
import (
    "github.com/go-chi/chi/v5"
    ks "github.com/kl-switch/klswitch-sdk/sdk-go"
    "github.com/kl-switch/klswitch-sdk/sdk-go/chimanifest"
)

r := chi.NewRouter()
// ... mount routes ...

client, err := ks.Init(ctx,
    ks.WithToken(os.Getenv("KS_TOKEN")),
    ks.WithManifest(chimanifest.Source(r)),
)

// Optional: re-run discovery + push if the registry changed at runtime.
client.ReportManifest(ctx)
```

gorilla/mux apps use `muxmanifest.Source(router)`. Both adapters
convert their native `{id}` / `{id:regex}` path syntax to the
canonical `:id` form so manifest paths match rule selectors.

```go
import "github.com/kl-switch/klswitch-sdk/sdk-go/muxmanifest"

client, _ := ks.Init(ctx,
    ks.WithToken(os.Getenv("KS_TOKEN")),
    ks.WithManifest(muxmanifest.Source(r)),
)
```

- **Push policy** — once at init; backend dedups by `hash` so a
  steady-state app stays at one POST per startup. Call
  `Client.ReportManifest(ctx)` after registering routes at runtime
  (dev hot-reload, dynamic plugins) — no-ops if the hash is unchanged.
- **Opt-out** — omit `WithManifest`, or pass
  `WithReportManifest(false)` to skip even when a source is supplied.
  Console falls back to free-text route entry — no regression.
- **Failure policy** — log + counter bump. A failed push never blocks
  `Init` or disturbs the customer's startup. Initial push runs in a
  background goroutine.
- **Observability** — `Client.ManifestStatus()` returns
  `ManifestStatus{Framework, LastReportedAt, LastHash, LastPushOk,
  LastPushError, RouteCount, PushAttempts, PushesSucceeded,
  PushesFailed}`.
- **net/http** has no route registry — operators using bare
  `http.ServeMux` either build a manual `ManifestSource` (`Collect`
  returns a hand-authored list) or omit `WithManifest` entirely.

### What landed in G2

- `WithToken` / `WithInstanceID` / `WithBaseURL` / `WithRulesURL` / `WithExtraHeaders` / `WithRefreshInterval` / `WithTimeout` / `WithFetcher` options
- `HTTPFetcher` (default 5 s timeout, error-body snippet on non-2xx, bounded read)
- Background polling goroutine with **exponential backoff** on consecutive failures (cap 60 s)
- `Client.Refresh(ctx)` for user-triggered fetches
- `WithAnalytics(AnalyticsOptions)` — single-flight flushing, bounded queue, non-blocking `Record`
- Combined `Status` (engine + wrapper) with `LastRefreshOk` **and** `LastRefreshError` so refresh failures are visible without grepping logs

## Architecture

- WASM runtime: [wazero](https://github.com/tetratelabs/wazero) (pure Go)
- Concurrency: a single `sync.Mutex` serializes engine round-trips, matching the
  Java wrapper. Safe for concurrent `Evaluate` from any number of goroutines.
- JSON-only boundary: nothing about rule semantics lives in this wrapper.
- Compliance: replays the shared `spec/compliance/v1/cases/*` fixtures the Rust
  engine, Node, and Java wrappers all match.
