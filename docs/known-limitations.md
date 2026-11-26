# KLSwitch v1 — Known Limitations

This lists what v1 intentionally does **not** do, plus behaviors that surprise
people if undocumented. None of these are bugs — they are the frozen v1 scope
(`CLAUDE.md`, `docs/rule-model.md`). Post-v1 items are noted where relevant.

## Rule model

- **Selector kind is `api` only.** A rule targets a request by `method` + `path`.
  Other selector kinds are post-v1.
- **Path matching is exact** on the normalized/templated route (e.g.
  `/v1/payments/:id`). No wildcards, globs, or regex — those are post-v1. The
  caller must supply the templated path the rule was authored against (the
  framework adapters expose this; see `@KLSwitchBinding(path = …)` in Java).
- **No implicit type coercion.** A present-but-wrong-type field fails comparison
  operators (`5` ≠ `"5"`). Numeric equality is value-based (`5` == `5.0`).
- **Missing field → leaf `false`** for every operator except `exists` /
  `not_exists`.
- **Limits (PRD §22.2 / `rule-model.md` §9):** at most **1000 rules** per ruleset
  and **50 conditions** per rule. Exceeding them fails the load with
  `RULE_LIMIT_EXCEEDED` / `CONDITION_LIMIT_EXCEEDED`; the last known good ruleset
  stays active.
- **Actions are data, not behavior.** The engine returns the matched rule's
  action (`type` + `payload`); it never executes anything. Interpreting an action
  (e.g. "block" → HTTP 4xx) is the wrapper/host's job.

## Engine & boundary

- **One engine instance per SDK instance**, reused across evaluations.
- **JSON-only WASM boundary** — no custom binary protocol. Every call serializes
  JSON in and parses JSON out.
- **Atomic replacement only.** A new ruleset is fully parsed/validated/compiled/
  indexed before becoming active; on any failure the previous ruleset stays
  active and no partial state is visible.
- **Java engine access is serialized** (the bridge is `synchronized`) because a
  Chicory instance is single-threaded. Share one `Client`; throughput is bounded
  by serialized evaluate calls. Node is single-threaded by nature.

## Rules API / refresh

- **A mock rules API ships for offline development** (`backend/`). The real
  backend is **klswitch**, which serves the same response shape
  (`spec/schemas/ruleset.schema.json`) from `GET /v1/ruleset`; the mock exists so
  the wrappers' suites can run without it.
- **Conditional fetch is implemented.** Wrappers track the `ETag`, send
  `If-None-Match`, and treat `304` as "unchanged" without re-loading the engine
  (`polling-etag` tests in all four wrappers). A cheap `HEAD` probes the version
  before a body is fetched at all.
- **Keep-last-known-good** on any fetch or load failure; `getStatus()` reports
  `lastRefreshOk`. A failed refresh never disturbs the active ruleset.
- Polling is **disabled unless a refresh interval is set**; a `rulesUrl` alone is
  fetched once at init.

## Wrappers & adapters

- **v1 languages are Node.js/TypeScript, Java/Kotlin, Go, and Python** — all
  four wrappers ship and replay the same compliance fixtures byte-identically.
- **Adapters:** Express + Fastify (Node), JAX-RS `ContainerRequestFilter`
  (Java), `net/http` middleware (Go), Flask `KLSwitchExtension` + FastAPI
  `klswitch_depends` (Python). No Spring adapter in v1 (the JAX-RS filter
  matches the Dropwizard/Jersey demo stack).
- **`facts.meta` is reserved for caller-supplied context.** The shipped framework
  adapters always pass an empty `meta` object. (Node's lower-level
  `buildEvaluationInput` accepts a caller-supplied `meta`; the Java `RequestContext`
  does not yet.) Populate `meta` via a hand-written integration if a rule needs
  request metadata beyond headers/query/params/body.
- **Analytics has no built-in backend.** The wrapper batches events to a
  caller-provided sink and never blocks `evaluate`; shipping them somewhere is the
  host's responsibility.

## Tooling

- **Schema validation** in `scripts/validate-schemas.sh` checks JSON
  well-formedness and the draft declaration; full instance conformance is enforced
  by the Rust roundtrip test and the cross-language compliance fixtures, not a
  standalone JSON-Schema validator (no 2020-12 validator is vendored).
