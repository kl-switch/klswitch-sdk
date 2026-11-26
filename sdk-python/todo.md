# KLSwitch Python SDK — Development Plan

A Python wrapper around the same WASM rule engine as `sdk-node`, `sdk-java`,
and `sdk-go`. Same JSON contract on the boundary; host-side concerns (polling,
analytics, logging, framework integration) live entirely outside WASM. This
file freezes design decisions, breaks the work into phases, and tracks progress
the same way `sdk-go/` did.

**Completion format:** `[ ]` not started · `[~]` in progress · `[x]` done · `[-]` skipped/deferred.

---

## 1. Stack decisions (lock in, then build to them)

The Python ecosystem is genuinely different from Java/Go on three axes — call
out the trade-offs explicitly so they're not surprises mid-build.

| Decision | Choice | Why this, not the alternative |
|---|---|---|
| WASM runtime | **`wasmtime-py`** (Bytecode Alliance) | Mature, well-maintained, ships prebuilt wheels for Linux/macOS/Windows × x86_64/arm64. The catch: it's native-bindings, so the SDK is **not** "pure Python" the way Chicory makes Java "pure Java" and wazero makes Go "pure Go". `wasmer-python` is less maintained; `pywasm` is too slow. The portability cost is the wheel matrix, not the user's install experience. |
| Minimum Python | **3.10** | Lets us use PEP 604 `X \| Y` unions, PEP 585 generics, structural pattern matching. Older Pythons are EOL or close to it. |
| Type hints | **Pervasive + `py.typed` marker** | Modern Python users expect it; helps the IDE; mypy-clean is a release gate. |
| JSON | **stdlib `json`** | No dep; allow override (e.g. `orjson`) via a hook for hot paths. |
| HTTP client | **`urllib.request` (stdlib) default** | Zero extra deps; injectable `RulesFetcher` for users who want `httpx` / `requests`. |
| Sync vs async | **Both, as two separate clients: `Client` (sync) and `AsyncClient` (async)** | Python's async/sync split is real; a single hybrid client gets ugly. Mirror that decision in the API rather than papering over it. |
| Packaging | **`pyproject.toml` + `hatchling`** | Modern, PEP 621, no `setup.py`. |
| WASM artifact | **Bundled in the wheel, loaded via `importlib.resources`** | Single `pip install`, no separate fetch step, mirrors what we do in Node/Java/Go. |
| Framework adapters in v1 | **Flask (sync) + FastAPI (async)** | The two clearly-dominant frameworks in each world. Mirrors Node's Express + Fastify pair. Spring-of-Python = Flask, Fastify-of-Python = FastAPI. |

**Apply Phase 1 review lessons proactively** (don't re-litigate them in
Python's own review later):

- Default refresh timeout (5 s) — close the gap Node had.
- Body snippet (capped) in non-2xx fetch errors.
- Exponential backoff with ±25 % jitter on poll failures (cap 60 s).
- Analytics: bounded queue, single-flight flush, **never block evaluate**.
- `Status` surfaces `last_refresh_error` (not just `last_refresh_ok`).
- `Client.__init__` cleans up the engine if a later step fails (the
  catch-and-close pattern from Node/Java post-fixes).
- Bounded body read in any middleware (DoS protection mirrors Java
  `KLSwitchFilter` + Go `httpmiddleware`).
- Default for any "host-side knob" matches the Java/Go default so
  cross-language behaviour stays comparable.

---

## 2. Master phase plan

- `[x]` Phase P1 — Foundation (types, bridge, basic client, compliance fixtures)
- `[x]` Phase P2 — Polling, logging, analytics (sync)
- `[x]` Phase P3 — `AsyncClient` (async polling + async analytics + async fetcher)
- `[x]` Phase P4 — Web framework adapters (Flask + FastAPI)
- `[x]` Phase P5 — Example server + integration recipes
- `[x]` Phase P6 — Polish, packaging, cross-language parity check

> P1 / P2 / P3 sub-task lists below are kept for context. Detailed status of
> individual files and tests is in the per-phase commit messages; treat any
> `[ ]` markers under P1–P3 as "this list captured the original intent" rather
> than "this is still pending".

---

## 3. Phase P1 — Foundation ✅

**Goal:** A working sync `Client` that loads the bundled WASM, evaluates real
inputs, and replays every `spec/compliance/v1/cases/*.json` fixture
byte-identically to the other wrappers.

### Tasks

- `[ ]` `sdk-python/pyproject.toml` — hatchling, PEP 621 metadata, `wasmtime` dep, src layout
- `[ ]` `sdk-python/src/klswitch/__init__.py` — package entry + public re-exports
- `[ ]` Copy `klswitch_core.wasm` into `sdk-python/src/klswitch/` so it ships in the wheel
- `[ ]` `_resources.py` — `importlib.resources` helper to read the bundled `.wasm`
- `[ ]` `types.py` — `Operator` enum, `Selector`, `Action`, `ConditionNode`, `Rule`, `RuleSetPayload`, `Facts`, `EvaluationInput`, `EvaluationResult`, `MatchedRule`, `LoadRulesResult`, `EngineStatus`, `ErrorInfo` (all as `@dataclass`)
- `[ ]` JSON encode/decode helpers — type-aware (handle `Optional`, omit `None`, `Facts` defensive empty-bucket fill for parity with the Go `MarshalJSON` fix)
- `[ ]` `_bridge.py` — `wasmtime`-based engine bridge with a `threading.Lock` around the alloc → write → call → read → free sequence (parity with the Java `synchronized` + Go `sync.Mutex` pattern)
- `[ ]` `client.py` — `Client` class with `init` (classmethod), `evaluate`, `get_status`, `close`
- `[ ]` Context manager protocol (`__enter__`/`__exit__`) so `with Client(...) as ks:` Just Works
- `[ ]` `errors.py` — exception hierarchy (`KLSwitchError` base, `ClosedClientError`, `LoadRulesError`)
- `[ ]` Compliance fixture tests — replay the same 23 eval + 8 scenario JSON files Rust/Node/Java/Go already match
- `[ ]` Bridge tests (lifecycle, invalid handle, malformed input, repeated calls)
- `[ ]` Concurrency stress test (8 threads × 250 evals against a shared `Client`)

### Done when

- `pip install -e .` (or hatch-equivalent) builds a usable wheel.
- Every compliance fixture passes byte-identical to the other wrappers.
- `mypy --strict src/` clean.
- All Phase P1 tests pass under `pytest -x` and a thread-stress equivalent.

---

## 4. Phase P2 — Polling, logging, analytics (sync) ✅

**Goal:** Reach feature parity with the sync surface of `sdk-go`'s G2.

### Tasks

- `[ ]` `Logger` protocol + silent default + `StdlibLoggerAdapter` for `logging.Logger`
- `[ ]` `RulesFetcher` protocol + `HTTPFetcher` (default `urllib.request`)
  - Default 5 s timeout
  - Capped (4096-char) response-body snippet on non-2xx
  - Bounded read so a malicious upstream can't OOM the SDK
- `[ ]` `_polling.py` — `PollingManager` with a daemon background thread
  - `threading.Lock` around `poll_once`; callbacks invoked **without** the lock held (Phase G3 lesson — avoid latent deadlocks)
  - Exponential backoff with ±25 % jitter, cap 60 s
  - Stops cleanly via a `threading.Event`
- `[ ]` `Client.refresh()` (sync)
- `[ ]` `_analytics.py` — `AnalyticsReporter`
  - `Sink = Callable[[list[AnalyticsEvent]], None]`
  - Bounded `queue.Queue` (default 10 000) — drops the new event on overflow with a warn-once log
  - Single-flight flush thread; never blocks `record`
  - `close()` drains best-effort
- `[ ]` `Status` includes `last_refresh_ok`, `last_refresh_at`, `last_refresh_error`
- `[ ]` `Client.__init__` defer/finally cleanup: if any step after engine creation raises, `close()` runs before re-raising (the engine-leak fix from Node/Java Phase 1)
- `[ ]` Polling + analytics tests + a real-`http.server`-backed integration test

### Done when

- Init from URL, refresh, recurring polling, refresh-failure-keeps-LKG, body-snippet-in-error, backoff-with-jitter, analytics drain on close — all covered by tests against a real local HTTP server.

---

## 5. Phase P3 — `AsyncClient` ✅

**Goal:** First-class asyncio support. Sync and async share the same WASM
bridge model but differ in how they manage the polling task, the fetch, and
the analytics drain.

### Tasks

- `[ ]` `AsyncClient` mirroring `Client`'s surface: `async def evaluate`, `async def refresh`, `async def get_status`, `async def aclose`
- `[ ]` `AsyncRulesFetcher` protocol; default async fetcher (whatever httpx-or-stdlib path we settle on — `httpx` is the natural choice but adds a dep; `aiohttp` is heavier)
- `[ ]` Async polling task launched on `asyncio.create_task`, cancelled cleanly on `aclose`
- `[ ]` Async analytics: support sync callable **or** awaitable sink (introspect at construction time)
- `[ ]` Async context manager (`async with AsyncClient(...) as ks:`)
- `[ ]` Decision: does the underlying WASM bridge stay synchronous (with `asyncio.to_thread` wrapping each `evaluate`)? Or do we expose async at the bridge layer? **Default decision:** keep the bridge sync (engine work is sub-ms), wrap with `to_thread` only if a benchmark says we need to. Document.
- `[ ]` Mirror every sync test under `asyncio`

### Done when

- Both clients pass the same compliance fixture suite (the async one via `asyncio.run`).
- An `asyncio` polling task survives task cancellation cleanly (no leaked tasks, no warnings).

---

## 6. Phase P4 — Web framework adapters ✅

**Goal:** One-import integration for the two dominant Python frameworks.

### Tasks

- `[ ]` `adapters/flask.py` — `KLSwitchExtension` with `init_app(app)` plus a per-blueprint or per-route guard helper; reads request method/path/query/headers/JSON body
- `[ ]` `adapters/fastapi.py` — ASGI middleware **and** a `Depends(...)` dependency for per-route mounting (both patterns are idiomatic in FastAPI; ship both)
- `[ ]` Shared `request_context.py` that builds an `EvaluationInput` from the common pieces (method, path template, headers, query, params, body)
- `[ ]` Pluggable result handler / error handler / max-body-bytes — exactly the same shape Go's `httpmiddleware` ships (block on `block` by default; user owns `flag`/`notify` policy)
- `[ ]` Bounded body read (default 1 MiB) with streaming pass-through so the route handler still sees the full body
- `[ ]` Tests via Flask's test client and `httpx.AsyncClient` against a FastAPI app

### Done when

- A single line of code wires either framework to a `Client`/`AsyncClient`.
- Same demo rules → same status codes as Node/Java/Go for the same curl invocations.

---

## 7. Phase P5 — Example server ✅

**Goal:** A runnable demo at parity with `examples/`, `examples-java/`, `examples-go/`.

### Tasks

- `[ ]` `examples-python/` at the repo root (sibling of `examples/`)
- `[ ]` Two demo servers: `flask_server.py` and `fastapi_server.py`, both using the same three demo rules (block_large_payment, block_restricted_region, flag_admin_access) — byte-identical to the other example servers
- `[ ]` `demo_rules.py` (or json file) shared between both servers
- `[ ]` Integration tests via Flask's `app.test_client()` and FastAPI's `TestClient`
- `[ ]` `README.md` with the same curl block the Node/Java/Go READMEs use; running either server should produce identical responses

### Done when

- The same `B=localhost:<port>` curl block from the other README files produces the same outputs against both Python servers.

---

## 8. Phase P6 — Polish + cross-language parity ✅

### Tasks

- `[ ]` `README.md` with quickstart, integration snippets, status, design notes
- `[ ]` `mypy --strict src/` clean as a CI gate
- `[ ]` `pytest -x --cov` reporting in CI (aim for ≥90 % on the wrapper code; engine call paths are covered by compliance fixtures)
- `[ ]` Build wheels on Linux/macOS/Windows × x86_64/arm64 (the wasmtime-py matrix covers this; just verify ours)
- `[ ]` Add to `scripts/run-compliance-tests.sh` so the existing Rust + Node + Java + Go compliance runner now also invokes the Python suite
- `[ ]` Update `docs/architecture.md` and `docs/prd-trd.md` to move Python from "post-v1" to "shipped"
- `[ ]` Update `docs/sdk-wrapper-contract.md:98-101` adapter list to include Flask + FastAPI

### Done when

- The Python SDK passes the same compliance suite as the Rust engine, Node, Java, and Go in the shared CI runner.
- Both `Client` and `AsyncClient` are documented with copy-paste examples.

---

## 9. Out of scope (defer; not v1)

- `pydantic` integration — pure stdlib dataclasses keep the dep tree tight; users wanting Pydantic can adapt via the JSON boundary
- Multi-process safety (engine handle sharing across forks/processes) — single-process, threaded-only in v1
- `WSGI` middleware as a separate adapter — Flask covers the dominant WSGI use; raw WSGI is rare in 2026
- gRPC interceptor — different protocol; out of v1's API focus
- Synchronous and async sharing one `Client` instance — keep them separate; tests would otherwise mushroom
- Auto-magic Django integration — defer to a community contribution

---

## 10. Cross-cutting guardrails (apply every phase)

- `[ ]` Behaviour matches the frozen compliance fixtures byte-for-byte
- `[ ]` Engine never has wrapper concerns leak into it (polling, logging, analytics are all on the Python side)
- `[ ]` Every public type has type hints + a `py.typed` marker ships in the wheel
- `[ ]` Every long-lived background thread/task is a daemon (or cancellable) so it never blocks `Client.close()` or process exit
- `[ ]` Engine handle is released on every failure path (mirrors Node/Java/Go post-Phase 1)
- `[ ]` Defaults across SDKs stay aligned (5 s refresh timeout, 1 MiB body cap, 4096-char error snippet, 60 s backoff cap, 10 000 analytics queue cap, 50-event batch size)
