# SDK Wrapper Contract

## Purpose

This document defines what every language-specific SDK wrapper is expected to do.

---

## 1. Responsibilities

Every SDK wrapper must provide:

- WASM loading
- engine lifecycle management
- rules polling
- logging
- analytics batching/sending
- public SDK API
- framework adapters where relevant

---

## 2. Public API shape

Each wrapper exposes equivalent capabilities; only the spelling follows the
language (`instanceId` / `instance_id` / `InstanceID()`). Accessors may be
getters, properties, or methods as the language prefers.

### 2.1 Core surface (frozen — all four wrappers)

| Capability | Returns | Notes |
|---|---|---|
| `init(config)` | client | static/package-level constructor |
| `evaluate(input)` | matched rules | Go takes a `ctx`; Python async is awaited |
| `refresh()` | — | force a rules fetch |
| `getStatus()` | engine + wrapper status | |
| `close()` | — | Python async spells it `aclose()` |
| `analyticsStatus()` | analytics counters | |
| `manifestStatus()` | manifest push state | |
| `pollingStatus()` | poll counters | |
| `reportManifest()` | `bool` — pushed or not | |
| `instanceId` | `string` | resolved per `instance-id` rules |
| `urls` | resolved endpoints | |
| `sdkVersion` | `string` | |

A wrapper that lacks one of these is non-compliant. Adding to this table is a
cross-wrapper change: all four, or none.

### 2.2 Known divergences (NOT yet agreed — open decisions)

These exist in some wrappers only. They are recorded here as drift, not as
contract; each needs a decision to either spread to all four or be removed.

| Member | Present in | Missing from |
|---|---|---|
| `activeRulesVersion` | Java, Python | Node, Go |
| `registerGuardMount(description)` | Java | Node, Go, Python |
| `log` | Node | Java, Go, Python |

Until these are resolved, do not write user-facing docs or examples that depend
on them, and do not add a fifth one — new surface goes in §2.1 for all four.

### 2.3 Config

`validateConfig` validates; it does not carry migration handling. Rejection of
the pre-A1 config shape is a separate unit (`config-legacy` in Node) so that
retiring the migration aid is a deletion rather than an edit to the validator.
Adding a config field must not require touching migration code.

---

## 3. Initialization

Wrappers must support:
- inline rules
- remote rules URL
- optional refresh interval
- optional headers for rules API
- timeout configuration

Expected startup behavior:
- if inline rules are provided, engine can initialize from inline rules
- if remote rules URL is provided, wrapper should fetch rules during init unless explicitly designed otherwise
- if initial rules loading fails and no fallback rules exist, init should fail clearly by default

---

## 4. Refresh behavior

When refresh is enabled:
- wrapper polls the rules API
- wrapper passes the full ruleset into WASM
- wrapper logs success or failure
- wrapper keeps last known good rules if refresh fails

Refresh must not block evaluation path.

---

## 5. Logging

Wrappers own logging.

Recommended log points:
- wasm load success/failure
- engine create success/failure
- rules load success/failure
- refresh success/failure
- analytics flush failure

Wrappers should not log every evaluation by default.

---

## 6. Analytics

Wrappers own analytics.

Recommended event categories:
- evaluation summary
- matched rule ids
- skipped count
- rules version
- latency bucket
- refresh errors

Analytics must be asynchronous and must not block evaluate calls.

---

## 7. Framework adapters

Framework adapters belong in wrappers, not in WASM.

Examples (v1: Node, Java, Go, Python):
- Express middleware (Node)
- Fastify hook (Node)
- JAX-RS `ContainerRequestFilter` / servlet filter (Java)
- `net/http` middleware (Go) — works with stdlib mux, chi, gorilla/mux via a
  pluggable `WithPathFunc` option
- Flask `KLSwitchExtension` (Python sync, WSGI)
- FastAPI / Starlette `klswitch_depends` (Python async, ASGI)

Adapters translate framework-specific requests into the stable evaluation input model.

### 7.1 Request-translation policy (frozen)

Every adapter, before handing facts to the engine, **must** apply the
following normalisations so a rule written once behaves identically across
Node, Java, Go, and Python:

| Bucket | Rule | Why |
|---|---|---|
| `selector.method` | Upper-case (`POST`, not `post`) | The engine matches method by exact string |
| `selector.path` | Templated route (e.g. `/v1/payments/:id`), not the concrete URL | Per-id rules would otherwise never match repeated path segments |
| `headers.*` | Lower-case keys (`headers.content-type`, not `headers.Content-Type`) | HTTP headers are case-insensitive but the engine's fact lookup is case-sensitive — an auth middleware setting `req.headers["X-User-Id"]` must match a rule on `headers.x-user-id` regardless of which framework is in front |
| `query.*` | Repeated keys collapse to the **first** value (`?role=admin&role=user` → `query.role = "admin"`) | Express's `qs` parser produces arrays; Starlette's `QueryParams.items()` returns last value — both would silently disagree with Java / Go / Flask. First-value is the cross-language canonical choice |
| `params.*` | Pass-through (typed by the router; never repeated by construction) | Each framework's path-param extractor already returns a single value |
| `body.*` | Parsed JSON object when `Content-Type: */json`; bounded read (default 1 MiB); non-object bodies → `{}` | Bounded read protects against DoS via oversized payloads; non-object bodies have no facts to extract |
| `meta.*` | Always-present empty object; reserved for caller-injected facts (e.g. authenticated user id) | Currently every adapter passes `{}`; a `with_meta` hook may land in v1.1 |

Wrappers may expose pluggable handlers (e.g. Go's `WithPathFunc`) for
router-specific concerns, but the **facts** they produce must satisfy this
table. The Node + Python compliance test suites include explicit unit tests
on `toEvaluationInput` / `build_evaluation_input` asserting each row.