# klswitch-sdk-java

Java / Kotlin wrapper for the KLSwitch WASM rule engine. One of the four v1
wrappers, alongside Node.js, Go and Python — all four replay the same compliance
fixtures against the same engine.

## Responsibilities (outside the WASM boundary)

- load the bundled `.wasm` engine via a JVM WASM runtime and manage its lifecycle
- bridge JSON in/out across the WASM memory boundary (alloc/copy/free)
- optional polling of a remote rules API (disabled unless `refresh_interval` is set)
- logging and analytics (analytics must never block `evaluate`)
- framework adapters (JAX-RS filter, servlet filter, Spring Boot starter)
- the public `init` / `evaluate` / `refresh` / `getStatus` / `close` API

It does **not** parse, compile, store, or evaluate rules — that is the engine's
job. The Java wrapper must pass the **same** compliance fixtures as Node to
prove cross-language behavioral consistency. See
[`../docs/sdk-wrapper-contract.md`](../docs/sdk-wrapper-contract.md).

## Status

**Phases 11–13 done — the Java wrapper is complete.** The full `init` /
`evaluate` / `refresh` / `getStatus` / `close` lifecycle works against the real
WASM engine via [Chicory](https://github.com/dylibso/chicory) (a pure-Java WASM
runtime — no native deps), plus runtime concerns (all outside WASM): remote
rules fetch + optional polling (`refresh()` is live), opt-in logging, and
non-blocking batched analytics. Cross-language parity is proven — the JUnit
suite replays the **same** compliance fixtures as Rust and Node and matches
byte-for-byte. Phase 13 adds framework integration: a framework-agnostic
`RequestContext` translator plus a JAX-RS `ContainerRequestFilter`.

### Polling, logging, analytics

```java
// The token is service-scoped + env-scoped at issue time; the backend
// derives the service identity and env from it, so neither needs to be
// repeated in the SDK config.
Client ks = Client.init(Config.builder()
    .token(System.getenv("KS_TOKEN"))
    // Optional. Falls back through KS_INSTANCE_ID, POD_NAME, then
    // hostname-pid-startMs derivation.
    .instanceId(System.getenv("POD_NAME"))
    // Optional. Defaults to https://api.klswitch.dev. Override for self-hosted.
    .baseUrl("https://api.klswitch.dev")
    .refreshIntervalMs(30_000)                   // default 30_000ms; pass 0 to disable polling
    .logger(LoggerAdapter.CONSOLE)
    .analytics(batch -> myCollector.send(batch))
    .build());

ks.refresh();   // force an immediate re-fetch; check getStatus().lastRefreshOk()
```

The SDK adds these headers to every backend call:

- `Authorization: Bearer <token>`
- `X-Instance-Key: <instanceId>` — auto-derived if you don't pass one
- `X-Env-Hint: <KS_ENV | POD_NAMESPACE | K_SERVICE_REVISION>` — observational
- `User-Agent: klswitch-java/<version>`

A failed refresh keeps the last known good ruleset active. `Client` is
thread-safe (engine access serialized internally), so share one instance.

### Server-driven polling (v1.1)

The polling manager is conservative about bandwidth: after the first
fetch it does a `HEAD /v1/ruleset` probe and only `GET`s when the
server's `ETag` has changed (sending `If-None-Match` on the GET as
race-safety — server replies `304` if the version flipped back during
the HEAD→GET window).

Every response's `Cache-Control: max-age=<seconds>` retunes the next
wake-up delay, so the backend controls cadence per service/tier
without an SDK config change. The wrapper clamps the server's value to
`[PollingManager.MIN_POLL_MS=5s, PollingManager.MAX_POLL_MS=3600s]`.
When the server omits `ETag` / `Cache-Control` (older self-hosted
backend) the wrapper degrades gracefully to plain `GET` on
`refreshIntervalMs`.

`client.pollingStatus()` returns a `PollingManager.PollingStatus` with
`lastEtag`, `nextPollAt`, and `currentIntervalMs` for operator
visibility.

On consecutive fetch failures the next-wake delay grows exponentially
(`currentInterval × 2^n`) with ±25 % jitter, capped at
`max(PollingManager.MAX_BACKOFF_MS=5min, currentIntervalMs)` — so a
long server cadence is never retried *more* aggressively on failure
than on success. First successful fetch resets the schedule; the
active ruleset is preserved across the failure window (engine keeps
last-known-good).

### Instance liveness

There is no separate heartbeat channel. The ruleset poll *is* the liveness
signal: an instance that is polling is alive, and its `X-Instance-Key` header
identifies it, so the console can count healthy instances and surface version
drift after a publish without a second timer or a second endpoint.

### Decision telemetry

Per-evaluation analytics events are emitted to `/v1/decisions` when
`analytics(...)` is configured. Each event matches the frozen console
contract — one event per `evaluate()` call (including no-match cases),
carrying the templated request shape, matched-rule list with action
types, latency bucket, and a rolling error counter.

- **One event per `evaluate()`** — including `matched: []` for no-match.
- **Single bounded ring buffer** (default 10 000 events) — drop-oldest
  on overflow keeps the most-recent activity visible to the dashboard.
- **Single-flight gzipped POST** — at most one POST in flight at a
  time. Body is gzipped; payload contains an envelope plus up to 100
  events.
- **Drop-batch on failure, no retry** — retry without an idempotency
  key would risk double-counting. Lost batches show up as
  `eventsDropped` / `batchesFailed` on `client.analyticsStatus()`.
- **Custom sink** — pass `.analytics(sink)` to bypass the HTTP POST
  entirely (events flow into the operator's own analytics pipeline).
- **Observability** — `client.analyticsStatus()` returns an
  `AnalyticsStatus` record with `bufferSize`, `eventsRecorded`,
  `eventsDropped`, `eventsSent`, `batchesFailed`, `lastFlushAt`,
  `lastFlushOk`, `lastFlushError`, `consecutiveFailures`.
- **Schema-drift Layer 3 — missing fields.** After every `evaluate()`
  the wrapper walks each matched rule's condition tree against the
  request input and stamps a deduplicated, sorted `missingFields:
  List<String>` on the `AnalyticsEvent` for fields the rule
  referenced but the request didn't carry. Console flags those rules
  as "drifted from the live schema". Walk is wrapper-side only — no
  engine change.

### Boot-time disk cache (v1.1)

Operator opts in via `Config.Builder.cachePath(...)`. Every successful
remote fetch persists the accepted ruleset JSON there (atomic move),
and on init — if the very first fetch fails AND the file exists — the
SDK loads it and serves traffic against last-known-good while polling
keeps retrying the backend in the background.

```java
String cachePath = RulesCache.defaultCachePath(System.getenv().getOrDefault("KS_ENV", "prod"));
Client ks = Client.init(Config.builder()
        .token(System.getenv("KS_TOKEN"))
        .cachePath(cachePath)
        .build());
```

`RulesCache.defaultCachePath(env)` returns
`${java.io.tmpdir}/ks-cache-<env>.json`. Operators with stronger
durability needs can pass their own path (e.g. a mounted PV). A
corrupt or empty cache file is ignored defensively — init only
succeeds when EITHER a fresh fetch OR a valid cached payload is
available.

### API manifest reporting

Pass a framework-specific `ManifestSource` and the SDK pushes the
discovered route list to `/v1/manifest` at init. This powers the
console's route picker on `/rules/new`, field autocomplete in the
condition editor, and Layer 4 of schema-drift detection ("rule X
targets a route no live instance has").

```java
import com.klswitch.jaxrs.JaxRsManifestSource;

Client ks = Client.init(Config.builder()
    .token(System.getenv("KS_TOKEN"))
    .manifest(JaxRsManifestSource.of(application))   // walks all JAX-RS resource classes
    .build());

// Optional: re-run discovery + push if the registry changed at runtime.
ks.reportManifest();
```

Servlet apps use `ServletManifestSource.of(servletContext)`. The
walker reflects on each registered servlet's `doXxx` overrides to
emit method-accurate routes; servlets that don't extend `HttpServlet`
surface as a single `ANY` mapping so the operator still sees them.

```java
import com.klswitch.servlet.ServletManifestSource;

Client ks = Client.init(Config.builder()
    .token(System.getenv("KS_TOKEN"))
    .manifest(ServletManifestSource.of(servletContext))
    .build());
```

Spring Boot apps use `SpringManifestSource.of(applicationContext)`
(from the `klswitch-sdk-java-spring-boot-starter` module). It walks
the `RequestMappingHandlerMapping` bean — the same registry that
backs every `@Controller` / `@RestController` — and emits one route
per `(method, path)` pair, converting `{id}` → `:id`. Handlers with
no declared HTTP method (e.g. bare `@RequestMapping("/x")`) surface
as a single `ANY` row. Trigger the first push from an
`ApplicationReadyEvent` listener so the handler map is fully
populated:

```java
import com.klswitch.spring.SpringManifestSource;

Client ks = Client.init(Config.builder()
    .token(System.getenv("KS_TOKEN"))
    .manifest(SpringManifestSource.of(applicationContext))
    .build());
```

- **Push policy** — once at init; backend dedups by `hash` so a
  steady-state app stays at one POST per startup. Call
  `client.reportManifest()` after registering routes at runtime
  (dev hot-reload, dynamic plugins) — no-ops if the hash is unchanged.
- **Opt-out** — omit `.manifest(...)`, or call
  `.reportManifest(false)` to skip even when a source is supplied.
  Console falls back to free-text route entry — no regression.
- **Failure policy** — log + counter bump. A failed push never blocks
  `Client.init()` or disturbs the customer's startup. Initial push
  runs on a daemon thread named `klswitch-manifest-init`.
- **Observability** — `client.manifestStatus()` returns a
  `ManifestStatus` record with `framework`, `lastReportedAt`,
  `lastHash`, `lastPushOk`, `lastPushError`, `routeCount`,
  `pushAttempts`, `pushesSucceeded`, `pushesFailed`.

### Framework integration (JAX-RS)

`RequestContext.toEvaluationInput(method, path, headers, query, params, body)`
turns request parts into the engine's input with no framework types involved —
use it directly in any stack. For JAX-RS / Jersey / Dropwizard there is a ready
filter (`jakarta.ws.rs-api` is a `provided` dep — the host brings the runtime):

```java
// once, at startup — share the client
environment.jersey().register(new KLSwitchFilter(ks));

@POST @Path("/{id}")
@KLSwitchBinding(path = "/v1/payments/:id")   // canonical path your rules target
public Response collect(@PathParam("id") String id,
                        @Context ContainerRequestContext ctx,
                        Map<String, Object> body) {
    // blocked requests never reach here; the filter aborts with the rule's statusCode.
    EvaluationResult result = (EvaluationResult) ctx.getProperty(KLSwitchFilter.RESULT_PROPERTY);
    ...
}
```

The filter only acts on `@KLSwitchBinding`-annotated methods. `path()` lets you
declare the templated path (rules match it exactly — rule-model §8) independent
of JAX-RS's `{id}` syntax. The JSON body is read and reset, so the resource can
still consume it. A `block` action aborts the request; any other action attaches
the `EvaluationResult` under `KLSwitchFilter.RESULT_PROPERTY` for the resource
to act on. See [`../examples-java`](../examples-java) for a running demo.

### Framework integration (Servlet API)

For any Servlet 5+ app (Tomcat, Jetty, Undertow) that isn't on JAX-RS,
`com.klswitch.servlet.KLSwitchFilter` is a stock
`jakarta.servlet.Filter`. Wire it up in `web.xml` or a `WebApplicationInitializer`,
supplying a path-resolver that maps the concrete URL to the templated
path your rules target:

```java
ks.servlet().addFilter("klswitch",
    new KLSwitchFilter(client, req -> "/v1/payments/:id"))
  .addMappingForUrlPatterns(null, false, "/v1/payments/*");
```

The filter wraps the request to allow body re-reads (capped at 1 MiB —
oversized bodies stream through unbuffered, no body facts captured).
`block` actions short-circuit with the action's `statusCode`; other
actions attach the `EvaluationResult` to the request under
`KLSwitchFilter.RESULT_ATTR`.

### Framework integration (Spring Boot starter)

Add the starter to your `pom.xml`:

```xml
<dependency>
  <groupId>com.klswitch</groupId>
  <artifactId>klswitch-sdk-java-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Then set a single property:

```yaml
klswitch:
  token: ${KS_TOKEN}
  # optional — every Config field is mirrored on KLSwitchProperties
  refresh-interval-ms: 30000
```

The auto-config builds the `Client` bean, the `KLSwitchInterceptor`,
and registers the interceptor against all paths. The interceptor
reads the matched `@RequestMapping` template (`{id}` → `:id`),
evaluates, blocks on block actions, and attaches the
`EvaluationResult` to the request as `com.klswitch.result` for
downstream handlers.

Every bean is `@ConditionalOnMissingBean` — supply your own `Client`,
interceptor, or `WebMvcConfigurer` to customise any layer.
`klswitch.enabled=false` skips the auto-config entirely.

## Build & test

```bash
mvn test
```

Requires a JDK (built/tested on 21, targets release 17) and Maven. The engine is
bundled at `src/main/resources/klswitch_core.wasm` (copied from the built
`rust-core` wasm).

## Quick start

```java
import com.klswitch.*;

String rulesJson = "{ \"schema_version\":\"1.0\", \"rules_version\":1, \"rules\":[ ... ] }";

try (Client ks = Client.init(Config.builder().rules(rulesJson).build())) {
    EvaluationResult result = ks.evaluate(
        "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/payments/:id\"}},"
            + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":70000},\"meta\":{}}}");
    boolean blocked = result.matched().stream().anyMatch(m -> m.action().type().equals("block"));
}
```

See [`../docs/sdk-wrapper-contract.md`](../docs/sdk-wrapper-contract.md) and
[`../docs/implementation-plan.md`](../docs/implementation-plan.md).
