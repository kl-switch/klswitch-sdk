# Cross-language parity

KLSwitch v1 ships four SDK wrappers — **Node.js/TypeScript, Java/Kotlin, Go,
and Python** — around the same Rust→WASM rule engine. Behavioural equivalence
is enforced two ways:

1. **Shared compliance fixtures.** Every wrapper replays
   `spec/compliance/v1/cases/*.json` against the bundled WASM and asserts
   byte-identical matched rule ids. `scripts/run-compliance-tests.sh` runs all
   five suites (Rust core + four wrappers) sequentially against the same
   freshly-built engine binary.
2. **Aligned defaults.** Host-side knobs (timeouts, batch sizes, body caps)
   default to the same numeric value in every wrapper so cross-language
   behaviour stays comparable without per-wrapper tuning.

## Default values

| Knob | Node | Java | Go | Python | Notes |
|---|---|---|---|---|---|
| HTTP refresh timeout | 5 000 ms | 5 000 ms | 5 s | 5.0 s | Per-fetch timeout for the default HTTP rules fetcher |
| Analytics batch size | 50 | 50 | 50 | 50 | Flush triggers when the queue holds this many events |
| Analytics flush interval | 5 000 ms | 5 000 ms | 5 s | 5.0 s | Periodic flush when batch size isn't reached |
| Analytics max queue size | 10 000 | 10 000 | 10 000 | 10 000 | Overflow events are dropped (with a warn-once log) |
| Middleware body cap | n/a¹ | 1 MiB | 1 MiB | 1 MiB | Body bytes read for fact extraction |
| Poll backoff cap | 5 min² | 5 min² | 5 min² | 5 min² | Max sleep between failing polls; clamped to `max(5min, currentInterval)` so a long server cadence is never undercut by retries |
| Poll backoff jitter | ±25 % | ±25 % | ±25 % | ±25 % | Prevents thundering-herd across SDK instances |
| Error-snippet cap | 4 096 chars | 4 096 chars | 4 096 chars | 4 096 chars | Trimmed slice of a non-2xx body included in fetch errors |

¹ Node's Express/Fastify adapters defer body bounding to the framework's own
JSON parser limits (`express.json({limit})` etc.), so the SDK doesn't impose
its own cap.

² Closed by **B2** (see `sdk/todo.md`). All four wrappers now share the same
exponential schedule (`min(currentInterval × 2^n, max(5min, currentInterval))`
with ±25 % jitter, reset on first success).

## Request-translation policy

The four canonical normalisations every adapter must apply are frozen in
`docs/sdk-wrapper-contract.md §7.1`. Summary of the per-wrapper enforcement:

| Normalisation | Node | Java | Go | Python (Flask) | Python (FastAPI) |
|---|---|---|---|---|---|
| Header keys → lowercase | ✓ `normalizeHeaders` in `request-context.ts` | ✓ `firstValues(..., true)` | ✓ `strings.ToLower` | ✓ `_request_context.build_evaluation_input` | ✓ |
| Repeated query → first value | ✓ `normalizeQuery` in `request-context.ts` | ✓ `firstValues` | ✓ `vs[0]` | ✓ `to_dict(flat=True)` | ✓ `_first_query_values` |
| Number int ⇔ float equivalence | engine handles | engine handles | engine handles | engine handles | engine handles |

The number-equivalence row is pinned by
`spec/compliance/v1/cases/eval-number-int-float-equivalence.json` and
`eval-number-string-is-wrong-type.json`, replayed by every wrapper's
compliance suite.

## Architectural alignment

| Concern | Node | Java | Go | Python |
|---|---|---|---|---|
| WASM runtime | V8/Node | Chicory (pure-Java) | wazero (pure-Go) | wasmtime-py (native bindings) |
| Engine serialisation | single-threaded promise chain | `synchronized` block | `sync.Mutex` | `threading.Lock` |
| Init failure cleanup | catch + close before rethrow | try/finally + close | defer-close on error | try/finally + close |
| Atomic ruleset swap | yes (handle on engine) | yes (handle on engine) | yes (handle on engine) | yes (handle on engine) |
| `last_refresh_error` in Status | yes | yes | yes | yes |
| Coroutine/async API | n/a (sync-only in v1) | n/a (sync-only in v1) | context-aware | `AsyncClient` mirrors sync surface |

## Public surface

The contract is `docs/sdk-wrapper-contract.md` §2 — this table is the per-language
view of it. §2.1 freezes a 12-member core that all four wrappers carry; the three
rows below are the drift, pending a decision to spread or remove each.

| Member | Node | Java | Go | Python |
|---|---|---|---|---|
| core 12 (§2.1) | yes | yes | yes | yes |
| `activeRulesVersion` | — | yes | — | yes |
| `registerGuardMount` | — | yes | — | — |
| `log` | yes | — | — | — |

Naming follows each language (`instanceId` / `instance_id` / `InstanceID()`), and
an accessor may be a getter, property, or method. `AsyncClient` spells `close` as
`aclose` and adds `start`; Go takes a `ctx` on the calls that do I/O. Those are
idiomatic renderings of the same member, not divergences.

## Framework adapters

| Wrapper | Adapters shipped |
|---|---|
| Node | `klswitchMiddleware` for Express + Fastify (`./src/middleware-express.ts`, `./src/middleware-fastify.ts`) |
| Java | `KLSwitchFilter` for JAX-RS `ContainerRequestFilter` |
| Go | `httpmiddleware.New` for `net/http`; works with stdlib mux, chi, gorilla/mux via `WithPathFunc` |
| Python | `KLSwitchExtension` for Flask (sync, WSGI); `klswitch_depends` for FastAPI/Starlette (async, ASGI) |

All adapters produce the same canonical block response shape so curl outputs
across the example servers line up:

```json
{ "blocked": true, "ruleId": "...", "action": { "type": "block", "payload": {...} } }
```

(FastAPI's idiomatic default wraps this in `{"detail": ...}`; the FastAPI
example server registers a custom exception handler to match the canonical
shape — see `examples-python/README.md`.)

## Example servers

| Server | Reuses demo ruleset? | Test transport |
|---|---|---|
| `examples/express-server.ts` | yes | Node `http` + `supertest` |
| `examples/fastify-server.ts` | yes | Node `http` + `supertest` |
| `examples-java/dropwizard/.../App.java` | yes (`demo-rules.json`) | Dropwizard test rule |
| `examples-java/spring/.../SpringDemoApp.java` | yes (shares `demo-rules.json`) | Spring Boot random-port test |
| `examples-go/main.go` | yes (`rules.go`) | `httptest.NewServer` |
| `examples-python/flask_server.py` | yes (`demo_rules.py`) | `app.test_client()` |
| `examples-python/fastapi_server.py` | yes (`demo_rules.py`) | `httpx.ASGITransport` |

Run the same curl block from any of these README files against the
corresponding server — the status codes, `ruleId` values, and `matched`
shapes all line up byte-for-byte. That's the v1 parity claim.

## The compliance contract (v1.1 — what every wrapper guarantees)

The canonical set in `spec/compliance/v1/cases/` is the source of truth
for "what the engine does." Every wrapper's compliance suite replays
the **same 50 fixtures** (**41 eval + 9 scenarios / 18 steps**) against
the bundled WASM and asserts byte-identical output.

Fixture categories:

- **`eval-*.json` (41)** — single-shot: one `ruleset`, one
  `evaluation_input`, one `expected_evaluation_result`. Tests the
  ruleset compile + selector index + condition tree walk + every leaf
  operator (eq/neq/gt/gte/lt/lte/in/not_in/exists/not_exists/
  contains/not_contains) + missing-field semantics + nested
  all/any/not + priority ordering + selector miss + effective windows +
  `*` / `**` selector path patterns (rule-model §8.1).
- **Scenario fixtures (9 files, 18 steps total)** — multi-step
  sequences against ONE engine instance: load_rules → evaluate →
  get_status → load_rules (replace) → evaluate. Tests atomic
  replacement, last-known-good after a failed replace, status after
  failed loads, partial-invalid rule errors, and a malformed selector
  pattern skipping only its own rule.

Each wrapper's test asserts **exact counts** (not `>=`) so a silent
skip — e.g. a fixture that one wrapper accepts but another rejects —
fails the build. The audit lives in `scripts/check-compliance.sh`.

Per-wrapper test file:

| Wrapper | File                                                            |
|---------|------------------------------------------------------------------|
| Node    | `sdk-node/test/fixtures.test.ts`                                |
| Java    | `sdk-java/src/test/java/com/klswitch/ComplianceFixturesTest.java` |
| Go      | `sdk-go/compliance_test.go`                                     |
| Python  | `sdk-python/tests/test_compliance.py`                           |

### Adding a fixture

1. Drop the new `.json` under `spec/compliance/v1/cases/` and a sibling
   entry in `spec/compliance/v1/index.md`.
2. Bump the count in **every** wrapper's compliance test (eval count,
   scenario count, step count) — all four must move together or the
   suite fails.
3. Re-run `scripts/check-compliance.sh` and confirm all four wrappers
   green.

## Verifying parity locally

Two scripts, scoped differently:

```bash
# Fast — JUST the compliance fixtures across all four wrappers.
./scripts/check-compliance.sh
```

Runs in ~30 s; useful as a pre-commit gate or to confirm the canonical
fixture set still agrees across wrappers before tagging a release.

```bash
# Full — every wrapper's complete test suite + the Rust core suite.
./scripts/run-compliance-tests.sh
```

Runs (in order):

1. Rebuild the WASM engine (if `cargo` + `wasm32-unknown-unknown` are
   available) and copy it into every wrapper's resource path.
2. Rust core tests (`cargo test -p klswitch-core`).
3. Node wrapper (`npm test` — replays compliance fixtures + bridge + polling
   + mock-rules-API integration).
4. Java wrapper (`mvn -q test` — same coverage in JVM).
5. Go wrapper (`go test ./...` — same coverage including
   `httpmiddleware`).
6. Python wrapper (`pytest -q` — same coverage including async + adapters).

A green run of `run-compliance-tests.sh` is the v1 release gate. A green
run of `check-compliance.sh` is the v1.1 cross-language compliance gate.
