# @klswitch/sdk-node

Node.js / TypeScript wrapper for the KLSwitch WASM rule engine. One of the four
v1 wrappers, alongside Java, Go and Python — all four replay the same compliance
fixtures against the same engine.

## Responsibilities (outside the WASM boundary)

- load the bundled `.wasm` engine and manage its lifecycle
- bridge JSON in/out across the WASM memory boundary (alloc/copy/free)
- optional polling of a remote rules API (disabled unless `refresh_interval` is set)
- logging and analytics (analytics must never block `evaluate`)
- framework adapters (Express, Fastify, NestJS)
- the public `init` / `evaluate` / `refresh` / `getStatus` / `close` API

It does **not** parse, compile, store, or evaluate rules — that is the engine's
job. See [`../docs/sdk-wrapper-contract.md`](../docs/sdk-wrapper-contract.md).

## Status

**Phases 8–10 done — the Node wrapper is complete.** The full
`init` / `evaluate` / `refresh` / `getStatus` / `close` lifecycle works against
the real WASM engine, plus runtime concerns (all outside WASM): remote rules
fetch + optional polling (`refresh()` is live), opt-in logging, non-blocking
batched analytics, and Express/Fastify/NestJS adapters. See
[`../docs/implementation-plan.md`](../docs/implementation-plan.md).

### Framework adapters

```ts
import { klswitchMiddleware } from "@klswitch/sdk-node"; // Express
app.post("/v1/payments/:id", klswitchMiddleware(ks), (req, res) => {
  if (req.klswitch?.matched.some((m) => m.action.type === "block")) {
    return res.status(429).end();
  }
  // ... handle normally
});

import { klswitchHook } from "@klswitch/sdk-node"; // Fastify (preHandler for body facts)
fastify.addHook("preHandler", klswitchHook(ks, {
  onResult: (result, _req, reply) => {
    if (result.matched.some((m) => m.action.type === "block")) reply.code(429).send();
  },
}));

// NestJS — KlswitchModule.forRoot({ client }) plus a guard. The guard
// reads the templated path from controller + handler @Path metadata, then
// throws HttpException with action.payload.statusCode (default 403) on
// block matches. Pick `@UseGuards(KlswitchGuard)` per-controller or
// register globally via `APP_GUARD`.
import { KlswitchModule, KlswitchGuard } from "@klswitch/sdk-node/adapters/nestjs";
@Module({
  imports:   [KlswitchModule.forRoot({ client: ks })],
  providers: [{ provide: APP_GUARD, useClass: KlswitchGuard }],
})
export class AppModule {}
```

Adapters are thin: they map the request to the stable evaluation input
(uppercased method, templated route path, all fact buckets) and hand back the
`EvaluationResult` — they never interpret actions, and need no Express/Fastify
dependency.

### Polling, logging, analytics

```ts
import { KLSwitch, consoleLogger } from "@klswitch/sdk-node";

// The token is service-scoped + env-scoped at issue time; the backend
// derives the service identity and env from it, so neither needs to be
// repeated in the SDK config.
const ks = await KLSwitch.init({
  token:        process.env.KS_TOKEN!,
  // Optional. Falls back through KS_INSTANCE_ID, POD_NAME, then
  // `${hostname}-${pid}-${startMs}` derivation.
  instanceId:   process.env.POD_NAME,
  // Optional. Defaults to https://api.klswitch.dev. Override for self-hosted.
  baseUrl:      "https://api.klswitch.dev",
  refreshInterval: 30_000,    // default 30_000ms; pass 0 to disable polling
  logger:       consoleLogger,
  analytics:    { sink: (events) => myCollector.send(events) },
});
await ks.refresh(); // force an immediate re-fetch
```

The SDK adds these headers to every backend call:

- `Authorization: Bearer <token>`
- `X-Instance-Key: <instanceId>` — auto-derived if you don't pass one
- `X-Env-Hint: <KS_ENV | POD_NAMESPACE | K_SERVICE_REVISION>` — observational
- `User-Agent: klswitch-node/<version>`

A failed refresh keeps the last known good ruleset active.

### Server-driven polling (v1.1)

The polling manager is conservative about bandwidth: after the first
fetch it does a `HEAD /v1/ruleset` probe and only `GET`s when the
server's `ETag` has changed (sending `If-None-Match: "<lastEtag>"` on
the GET as race-safety — server replies `304` if the version flipped
back during the HEAD→GET window).

Every response's `Cache-Control: max-age=<seconds>` retunes the next
wake-up delay, so the backend controls cadence per service/tier
without an SDK config change. The wrapper clamps the server's value to
`[MIN_POLL_MS=5s, MAX_POLL_MS=3600s]` so a buggy server can't park or
self-DDoS the pods. When the server omits `ETag` / `Cache-Control`
(older self-hosted backend) the wrapper degrades gracefully to plain
`GET` on `refreshInterval`.

`client.pollingStatus()` returns `{ lastEtag, nextPollAt,
currentIntervalMs }` for operator visibility.

On consecutive fetch failures the next-wake delay grows exponentially
(`currentInterval × 2^n`) with ±25 % jitter, capped at
`max(MAX_BACKOFF_MS=5min, currentInterval)` — so a long server cadence
is never retried *more* aggressively on failure than on success.
First successful fetch resets the schedule; the active ruleset is
preserved across the failure window (engine keeps last-known-good).

### Instance liveness

There is no separate heartbeat channel. The ruleset poll *is* the liveness
signal: an instance that is polling is alive, and its `X-Instance-Key` header
identifies it, so the console can count healthy instances and surface version
drift after a publish without a second timer or a second endpoint.

### Decision telemetry

Per-evaluation analytics events are emitted to `/v1/decisions` when
`analytics` is configured. Each event matches the frozen console
contract — one event per `evaluate()` call (including no-match cases),
carrying the templated request shape, matched rule list with action
types, latency bucket, and a rolling error counter.

- **One event per `evaluate()`** — including `matched: []` for no-match.
- **Single bounded ring buffer** (default 10 000 events) — drop-oldest on
  overflow keeps the most-recent activity visible to the dashboard.
- **Single-flight gzipped POST** — at most one POST in flight at a time.
  Body is gzipped, payload contains an envelope plus up to 100 events.
- **Drop-batch on failure, no retry** — retry without an idempotency key
  would risk double-counting. Lost batches show up as `eventsDropped` /
  `batchesFailed` on `client.analyticsStatus()`.
- **Custom sink** — pass `analytics: { sink }` to bypass the HTTP POST
  entirely (events flow into the operator's own analytics pipeline).
- **Observability** — `client.analyticsStatus()` returns
  `{ bufferSize, eventsRecorded, eventsDropped, eventsSent,
     batchesFailed, lastFlushAt, lastFlushOk, lastFlushError,
     consecutiveFailures }`.
- **Schema-drift Layer 3 — missing fields.** After every `evaluate()`
  the wrapper walks each matched rule's condition tree against the
  request input and stamps a deduplicated, sorted `missingFields:
  string[]` on the event for fields the rule referenced but the
  request didn't carry. Console flags those rules as "drifted from
  the live schema". Walk is wrapper-side only — no engine change.

### Boot-time disk cache (v1.1)

Operator opts in by setting `config.cachePath` to a writable file path.
Every successful remote fetch persists the accepted ruleset there
(atomic via tmpfile + rename), and on init — if the very first fetch
fails AND the file exists — the SDK loads it and serves traffic against
last-known-good while polling keeps retrying the backend in the
background. So a customer pod booting during a KLSwitch outage no
longer means "no rules".

```ts
import { defaultCachePath, KLSwitch } from "@klswitch/sdk-node";

const ks = await KLSwitch.init({
  token: process.env.KS_TOKEN!,
  cachePath: defaultCachePath(process.env.KS_ENV ?? "prod"),
});
```

`defaultCachePath(env)` returns `${tmpdir}/ks-cache-<env>.json` —
operators with stronger durability needs can pass their own path
(e.g. a PVC mount). A corrupt or empty cache file is ignored
defensively — init only succeeds when EITHER a fresh fetch OR a valid
cached payload is available.

### API manifest reporting

Pass a framework-specific source (e.g. `fastifyManifestSource(app)`) and
the SDK pushes the discovered route list to `/v1/manifest` at init.
This powers the console's route picker on `/rules/new`, field
autocomplete in the condition editor, and Layer 4 of schema-drift
detection ("rule X targets a route no live instance has".)

```ts
import { fastifyManifestSource, KLSwitch } from "@klswitch/sdk-node";

const ks = await KLSwitch.init({
  token:    process.env.KS_TOKEN!,
  manifest: fastifyManifestSource(fastify),  // discovers all registered routes
});

// Optional: re-run discovery + push if the registry changed at runtime.
await ks.reportManifest();
```

For Express, pass the app via `expressManifestSource(app)`. The walker
handles nested routers (`app.use('/api', router)`) and `app.METHOD()`
shortcuts; parametric mounts (`app.use('/:tenant', router)`) are
skipped without affecting sibling routes.

```ts
import { expressManifestSource, KLSwitch } from "@klswitch/sdk-node";

const ks = await KLSwitch.init({
  token:    process.env.KS_TOKEN!,
  manifest: expressManifestSource(app),
});
```

For NestJS, pass `nestjsManifestSource(app)` — it delegates to the
underlying express/fastify adapter that Nest wraps. Boot ordering
matters: the Client usually has to exist before the Nest module graph,
so pass a thunk and call `client.reportManifest()` after `app.init()`:

```ts
import { nestjsManifestSource, KLSwitch } from "@klswitch/sdk-node";

let app: INestApplication;
const ks = await KLSwitch.init({
  token:    process.env.KS_TOKEN!,
  manifest: nestjsManifestSource(() => app),   // lazy ref
});
app = await NestFactory.create(AppModule);
await app.init();
await ks.reportManifest();                      // first push lands here
```

- **Push policy** — once at init; backend dedups by `hash` so a
  steady-state app stays at one POST per startup. Call
  `client.reportManifest()` after registering routes at runtime
  (dev hot-reload, dynamic plugins) — no-ops if the hash is unchanged.
- **Opt-out** — omit `manifest`, or set `reportManifest: false` to skip
  even when a source is supplied. Console falls back to free-text route
  entry — same UX as before, no regression.
- **Failure policy** — log + counter bump. A failed push never blocks
  `init()` or disturbs the customer's startup.
- **Observability** — `client.manifestStatus()` returns
  `{ framework, lastReportedAt, lastHash, lastPushOk, lastPushError,
     routeCount, pushAttempts, pushesSucceeded, pushesFailed }`.

## Quick start

```ts
import { KLSwitch } from "@klswitch/sdk-node";

const ks = await KLSwitch.init({
  rules: {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "block_large_payment",
        name: "Block large payment",
        selector: { kind: "api", keys: { method: "POST", path: "/v1/payments" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 50000 }] },
        action: { type: "block", payload: { statusCode: 429 } },
      },
    ],
  },
});

const result = ks.evaluate({
  selector: { kind: "api", keys: { method: "POST", path: "/v1/payments" } },
  facts: { headers: {}, query: {}, params: {}, body: { amount: 70000 }, meta: {} },
});
// result.matched -> [{ id: "block_large_payment", ... }]

ks.close();
```

## Development

```bash
npm install
npm run build:wasm   # rebuild + bundle the engine from ../rust-core (needs the Rust + wasm32 toolchain)
npm test             # node:test via tsx
npm run build        # emit dist/
```

The engine is bundled at `wasm/klswitch_core.wasm` (committed; regenerate with
`npm run build:wasm`).
