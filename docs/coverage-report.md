# Coverage Report — KLSwitch SDK wrappers

Tooling set up in this pass:

| Wrapper | Tool                              | Command                       | Output                            |
|---------|-----------------------------------|-------------------------------|-----------------------------------|
| Node    | Node 22 native (`--experimental-test-coverage`) | `npm run test:coverage` | stdout + `coverage/lcov.info`     |
| Java    | JaCoCo 0.8.12 Maven plugin        | `mvn verify`                  | `target/site/jacoco/index.html`   |
| Go      | Built-in `go test -cover`         | `go test -cover ./...`        | stdout + `coverage.out`           |
| Python  | `pytest-cov` 5.x + `coverage` 7.x | `pytest --cov`                | stdout + `.coverage`              |

## Headline numbers

After the full coverage push (findings #1–#5 from the original report):

| Wrapper | Line cov  | Branch cov | Tests | Δ since first run |
|---------|-----------|------------|-------|-------------------|
| Node    | **98.75%** | **89.19%**  | 180   | +0.79pp branch, +7 tests |
| Java    | **92.18%** | **76.06%**  | 183   | +5.84pp line, +8.64pp branch, +27 tests |
| Go      | **82.9%**  | n/a*        | 170   | +9 tests, +0.6pp line (logger.go now 100%) |
| Python  | **85%**    | 82%         | 222   | +1pp line, +12 tests |

\* `go test -cover` reports line coverage only (statements). Branch coverage requires `gcov`-style tooling, not part of the Go toolchain.

**Aggregate across wrappers:** ~**89% lines**, **755 tests** green.

The five closures:

1. **Java `KLSwitchFilter`** — **0/56 → 100% line, 0/42 → 36/42 (85.7%) branch**. The single biggest gap in the project, now closed.
2. **HeartbeatReporter race paths × 4** — sendNow-during-inflight coalescing, close-during-inflight prompt return, queued-due-again-suppression-on-close. Java's `HeartbeatReporter` branch: **60% → 83.3%**. Node `heartbeat.ts` branch: **85% → 95%**. Python `_heartbeat.py` line: **79% → 89%**.
3. **Java `JaxRsManifestSource` extended verbs** — added PATCH/HEAD/OPTIONS + custom `@HttpMethod`-meta-annotated verb (e.g. WebDAV `PROPFIND`) + null-Application/null-collections defensive paths.
4. **No-op + console/stdlib logger smoke tests × 4** — closes the cosmetic "33–60% covered" gap on every wrapper's silent default logger. Python `logger.py` now **100%**; Node `logger.ts` funcs went **33% → 100%**; Go `logger.go` from 0% to ~100%; Java `LoggerAdapter` reaches both NOOP and CONSOLE.
5. **Python `AsyncClient.start()` race test** — fires five concurrent `start()` coroutines and asserts the asyncio.Lock + `_started` flag collapse them to exactly one initialisation fetch.

---

## Node — per-file

```
file                 | line % | branch % | funcs % | uncovered lines
-----------------------------------------------------------------------
src/
 adapters/
  express.ts         |  98.81 |    92.31 |  100.00 | 10
  fastify.ts         | 100.00 |    87.50 |  100.00 |
 analytics.ts        |  96.36 |    84.00 |   83.33 | 76 103-109 112-117
 client.ts           |  98.10 |    85.87 |   93.33 | 119 123-124 142-144 164 190-191 208
 config.ts           |  98.84 |    78.26 |  100.00 | 22 40
 heartbeat.ts        |  99.55 |    85.29 |   93.33 | 35
 instance-id.ts      | 100.00 |    90.48 |  100.00 |
 logger.ts           | 100.00 |   100.00 |   33.33 |
 manifest.ts         |  98.58 |    82.86 |   92.86 | 45 71-73
 manifest/
  express.ts         | 100.00 |    88.37 |  100.00 |
  fastify.ts         | 100.00 |    91.30 |  100.00 |
 polling-manager.ts  |  99.48 |    86.42 |   91.30 | 46-47
 request-context.ts  | 100.00 |   100.00 |  100.00 |
 request-headers.ts  | 100.00 |   100.00 |  100.00 |
 rules-cache.ts      |  96.30 |    88.89 |   88.89 | 13-15 42
 schema-drift.ts     | 100.00 |    94.44 |  100.00 |
 status.ts           | 100.00 |   100.00 |  100.00 |
 url-builder.ts      | 100.00 |   100.00 |  100.00 |
 wasm-bridge.ts      | 100.00 |   100.00 |  100.00 |
-----------------------------------------------------------------------
ALL                  |  98.75 |    88.40 |   91.57 |
```

**Highest-value gaps (Node)**

- `analytics.ts` lines 103–117 — analytics error-path branches (transport
  failures inside the single-flight POST). 5 branch misses out of 50.
  Worth testing: 500-response, network error, sink-throws.
- `client.ts` lines 119–144 — the heartbeat construction branch when
  heartbeat = null (operator runs without a token). Two paths inside the
  init sequence. Low risk because it's wired the same way as the Java/Go
  paths which are tested.
- `logger.ts` 33% funcs / 100% line — the no-op logger's methods compile
  to a single statement each; only one is ever called by tests. Cosmetic.

---

## Java — per-class

Sorted by line coverage ascending (worst → best):

```
   0.0%    0/56   0.0%    KLSwitchFilter            com.klswitch.jaxrs
  11.1%    1/9    —       LoggerAdapter.<anon>        com.klswitch  (no-op logger)
  25.0%    2/8    —       Logger                      com.klswitch  (interface stub)
  60.0%    3/5    —       LoggerAdapter.<anon>        com.klswitch
  76.2%   32/42   77.8%   RulesCache                  com.klswitch
  76.7%   23/30   68.2%   RequestHeaders              com.klswitch
  79.2%   38/48   —       Config.Builder              com.klswitch
  80.0%   24/30   83.3%   InstanceId                  com.klswitch
  83.3%   10/12   50.0%   ManifestSource              com.klswitch
  87.3%   62/71   59.5%   JaxRsManifestSource         com.klswitch.jaxrs
  87.5%  119/136  71.1%   AnalyticsReporter           com.klswitch
  87.8%  108/123  80.8%   ManifestReporter            com.klswitch
  89.5%   34/38   50.0%   WasmBridge                  com.klswitch
  92.0%  230/250  75.4%   Client                      com.klswitch
  92.5%   99/107  60.0%   HeartbeatReporter           com.klswitch
  94.6%   53/56   83.3%   Config                      com.klswitch
  95.1%   78/82   70.5%   HttpRulesFetcher            com.klswitch
  96.6%   56/58   69.6%   ServletManifestSource       com.klswitch.servlet
  96.6%   57/59   75.9%   SchemaDrift                 com.klswitch
  98.6%   70/71   76.9%   PollingManager              com.klswitch
 100.0%   …             (20+ records / data classes / inner builders)
```

**Highest-value gaps (Java)**

- 🔴 **`KLSwitchFilter` — 0/56 lines, 0/4 branches**. This is the JAX-RS
  request-filter (the runtime piece operators wire into their app). The
  test suite never instantiates it. **Biggest single gap in the
  project** — recommend a focused `KLSwitchFilterTest` (in-process
  Jersey, hit a guarded endpoint, assert block action triggers abort).
- `HeartbeatReporter` branch coverage 60% — backoff math is well-tested
  but the `coalesced extra send` race path (sendNow during in-flight)
  needs explicit tests.
- `JaxRsManifestSource` branch coverage 59.5% — the `httpVerb()` switch
  has 7 verb branches and only 3 are unit-tested (GET, POST, DELETE).
  Adding tests for PUT, PATCH, HEAD, OPTIONS would close it.
- `RulesCache` 76.2% line — the OS-level error paths (parent-dir
  creation failure, rename-on-Windows fallback) aren't exercised.

The `Logger`/`LoggerAdapter` no-op records each show as low-percentage
but represent ~6 statements total — they're the SDK's silent default
that real consumers replace.

---

## Go — per-file

Sorted by average line coverage ascending:

```
   0.0%  logger.go            (no-op logger; same story as Node/Java)
  50.0%  types.go             (NewFacts helper + MarshalJSON path)
  74.9%  muxmanifest.go
  75.2%  config.go            (option setters — many trivial one-liners)
  76.2%  rules_cache.go       (OS-error branches)
  77.3%  analytics.go
  78.1%  instance_id.go
  78.2%  heartbeat.go         (backoff race paths — see Java note)
  78.5%  client.go
  79.2%  request_headers.go
  84.3%  bridge.go
  85.1%  schema_drift.go
  89.4%  polling.go
  91.5%  chimanifest.go
  93.3%  manifest.go
  96.0%  request.go           (httpmiddleware/)
  97.1%  middleware.go        (httpmiddleware/)
 100.0%  options.go           (httpmiddleware/)
 100.0%  url_builder.go
```

Per-package totals:

```
ks-sdk/sdk-go                81.2%   (main package — biggest surface)
ks-sdk/sdk-go/chimanifest    88.9%
ks-sdk/sdk-go/muxmanifest    85.2%
ks-sdk/sdk-go/httpmiddleware 96.3%
```

**Highest-value gaps (Go)**

- `logger.go` 0% — the no-op default; same cosmetic gap as the other
  wrappers.
- `analytics.go` 77.3% — drop-oldest ring-buffer overflow path and the
  gzip-POST failure branches.
- `heartbeat.go` 78.2% — same race paths as Java's HeartbeatReporter.
- `client.go` 78.5% — error paths inside Init when WASM engine fails to
  create (would need a deliberately broken `customWASM` byte slice).

---

## Python — per-file

```
Name                                          Stmts  Miss  Branch BrPart   Cover
-----------------------------------------------------------------------------------
src/klswitch/__init__.py                       16     0      0      0   100%
src/klswitch/_analytics.py                    346    66     58     11    80%
src/klswitch/_bridge.py                        81     6     14      7    86%
src/klswitch/_heartbeat.py                    228    41     38     10    79%
src/klswitch/_instance_id.py                   50     3     14      1    94%
src/klswitch/_json.py                          50     2      8      2    93%
src/klswitch/_manifest.py                     148    20     14      1    87%
src/klswitch/_polling.py                      355    39     92     16    87%
src/klswitch/_request_context.py               12     0      0      0   100%
src/klswitch/_request_headers.py               37     4     20      2    82%
src/klswitch/_resources.py                      4     0      0      0   100%
src/klswitch/_rules_cache.py                   61    14     12      1    77%
src/klswitch/_schema_drift.py                  66     9     48      8    83%
src/klswitch/_url_builder.py                   12     0      0      0   100%
src/klswitch/adapters/fastapi.py               74    13     24      7    80%
src/klswitch/adapters/fastapi_manifest.py      38     7     16      2    76%
src/klswitch/adapters/flask.py                 63     7     24      9    82%
src/klswitch/adapters/flask_manifest.py        39     4     20      4    86%
src/klswitch/async_client.py                  321    45     90     18    82%
src/klswitch/client.py                        319    36     84     14    86%
src/klswitch/errors.py                         10     0      0      0   100%
src/klswitch/logger.py                         28    10      2      0    60%
src/klswitch/status.py                         10     0      0      0   100%
src/klswitch/types.py                          87     0      0      0   100%
-----------------------------------------------------------------------------------
TOTAL                                          2455   326    578    113    84%
```

**Highest-value gaps (Python)**

- `_rules_cache.py` 77% — OS-error branches (unreadable file, mkdir
  failure). Mirrors the Java/Go gap; same fix.
- `_heartbeat.py` 79% — same race paths as the other wrappers, plus
  the threading shutdown race when `close()` lands mid-flight.
- `adapters/fastapi.py` and `_manifest.py` 80% — error-handler
  branches (custom result-handler raising HTTPException).
- `async_client.py` 82% — the path where `start()` is called twice
  concurrently (the asyncio.Lock guard); needs an explicit racing
  coroutines test.
- `logger.py` 60% — the no-op logger's methods, same cosmetic gap
  the other three wrappers also show.

---

## Cross-cutting observations

**1. No-op logger stubs drag every wrapper down by ~1–2pp.** Four
   files (`logger.ts`, `logger.go`, `LoggerAdapter` anonymous classes
   in Java, `logger.py`) show 0–60% — all the same pattern: a silent
   default whose methods are pure pass-through. Adding a test that
   instantiates the default logger and calls every level would clean
   this up cosmetically; the lines themselves are non-functional.

**2. Race paths in HeartbeatReporter are consistently under-tested.**
   All four wrappers test the happy path + simple backoff but skip
   the SendNow-while-in-flight coalescing race and the close-while-
   in-flight cancellation race. These are concurrency tests, which
   are flaky to write — that's why they're missing. Worth a focused
   round if/when an operator reports a heartbeat oddity in
   production.

**3. RulesCache OS-failure branches.** All four wrappers test
   read/write happy paths + corrupt-cache defensive path but skip
   the "tmpfile rename fails because target is on a different
   filesystem" / "mkdir denied" branches. Hard to test portably;
   probably skip until an operator hits one.

**4. `KLSwitchFilter` (Java JAX-RS) at 0% is the single biggest
   gap.** That's the runtime request filter consumers wire into
   Jersey/Dropwizard — the path that actually decides whether a
   live HTTP request gets blocked. The B3 schema-drift work added
   tests for its sibling (`JaxRsManifestSource`) but the original
   filter still has no tests. **Strongly recommend adding before
   shipping v1.1.**

**5. Server-driven polling paths (B1) all >90% across wrappers** —
   solid. The B2/B3/B4 work added focused tests that close most
   gaps on those features (B4's `rules_cache.go` at 76% is the only
   B-batch outlier, and only on OS-error branches).

---

## All five recommended investments — done

✅ **#1 — Java `KLSwitchFilterTest`** (15 tests; filter 0% → 100% line).
   Added `jersey-common:3.1.10` test-scope dep for the `RuntimeDelegate`.

✅ **#2 — HeartbeatReporter race tests × 4** (22 tests total: 5 Node, 5 Java, 5 Go,
   7 Python sync/async). Deterministic via blocking HTTP gates released
   manually — no sleep-based polling inside the SUT.

✅ **#3 — Java `JaxRsManifestSource` extended verbs** (5 tests).
   PATCH/HEAD/OPTIONS + custom `@HttpMethod`-meta-annotated verb +
   null-Application/null-collections defensive paths.

✅ **#4 — No-op logger smoke tests × 4** (9 tests across Node 2 + Java 2 +
   Go 3 + Python 3). Closes the cosmetic gap on every wrapper's silent
   default logger.

✅ **#5 — Python `AsyncClient.start()` race test** (2 tests). Five
   concurrent `start()` coroutines collapse to one init fetch via the
   asyncio.Lock + `_started` flag.

Remaining gaps are mostly the OS-failure branches (RulesCache
mkdir/rename errors on platforms with permission denials) which are
hard to reproduce portably and low-value — leave them alone unless an
operator hits one.

---

## How to re-run

```bash
# Node
cd sdk-node && npm run test:coverage

# Java  (HTML at target/site/jacoco/index.html)
cd sdk-java && mvn verify

# Go    (HTML: go tool cover -html=coverage.out)
cd sdk-go && go test -coverprofile=coverage.out ./...

# Python (HTML: coverage html → htmlcov/index.html)
cd sdk-python && pytest --cov
```
