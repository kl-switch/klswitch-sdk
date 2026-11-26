# KLSwitch — Product Requirements Document (PRD) + Technical Requirements Document (TRD)

**Working Name:** KLSwitch
**Document Version:** 1.1
**Status:** Frozen for v1
**Prepared For:** Internal product and engineering planning
**Date:** April 2026 (frozen); revised May 2026
**v1 language scope:** Node.js/TypeScript, Java/Kotlin, Go, and Python wrappers all ship in v1. Each wrapper replays the same `spec/compliance/v1/cases/*.json` fixtures byte-identically against the shared WASM engine, so cross-language behaviour is provably equivalent.

---

# 1. Document Purpose

This document defines the product scope, functional requirements, technical design boundaries, operational expectations, and implementation constraints for the KLSwitch project.

The goal of this document is to freeze the v1 direction before coding begins, so that product decisions, SDK boundaries, and WASM architecture remain stable during development.

This document combines:

* a **Product Requirements Document (PRD)**
* a **Technical Requirements Document (TRD)**

It is intended for:

* product definition
* architecture alignment
* implementation planning
* future SDK and backend consistency
* preventing scope creep

---

# 2. Product Overview

## 2.1 What is KLSwitch?

KLSwitch is a **multi-language embeddable rule engine SDK** designed to evaluate rules **locally inside an application process**, with no network call required at evaluation time.

The engine is implemented once in **Rust**, compiled to **WebAssembly (WASM)**, and embedded inside SDK wrappers for multiple server-side languages.

Applications use the SDK to:

* load rules from inline configuration or a remote rules API
* keep rules updated in memory
* evaluate rules synchronously against runtime input
* deactivate problematic rules quickly using the `enabled` field without redeploying application code

## 2.2 What problem does it solve?

Modern teams often need rule-based decisioning for:

* API request control
* fraud checks
* pricing logic
* compliance eligibility
* policy enforcement
* runtime feature gating

Current approaches force painful tradeoffs:

* **Hardcoded logic** is fast but redeploy-heavy.
* **Centralized remote rule engines** are flexible but add latency and operational dependency.
* **Language-specific rule libraries** lead to logic drift across services.

KLSwitch aims to solve this by providing:

* local, in-process evaluation
* identical behavior across languages
* hot-swappable rule updates
* first-class per-rule kill-switch behavior

## 2.3 Product vision

Build a rule engine platform that starts with **API-focused runtime control**, but is designed from day one to expand into broader use cases without rewriting the core model.

V1 focus:

* API-centric rule targeting
* multi-language SDK wrappers
* WASM shared engine
* remote rule loading and in-memory rule refresh

Long-term direction:

* non-HTTP event targeting
* data pipeline rule evaluation
* workflow and eligibility rule execution
* hosted management and analytics platform

---

# 3. Product Goals

## 3.1 Primary goals

1. Provide a **single rule engine core** that behaves identically across supported languages.
2. Support **in-process rule evaluation** with low latency.
3. Allow **remote rule updates** without redeploy.
4. Allow **per-rule instant deactivation** through `enabled: false`.
5. Keep the **engine generic by design**, while making v1 strongly optimized for API use cases.
6. Keep the **developer integration experience simple**.
7. Clearly separate **WASM responsibilities** from **SDK wrapper responsibilities**.

## 3.2 Secondary goals

1. Support structured rule metadata for future platform growth.
2. Provide stable versioning across schema, WASM core, and SDK wrappers.
3. Allow analytics and observability without putting network concerns inside WASM.
4. Ensure future extensibility for additional target selector kinds beyond APIs.

---

# 4. Non-Goals for V1

The following are explicitly out of scope for v1 unless separately approved:

1. Visual drag-and-drop rule builder UI
2. Hosted rule management SaaS platform
3. Rule authoring UI
4. Rule rollback/history platform
5. Browser/frontend SDK support
6. Custom DSL for rules
7. Stateful rule sessions across evaluations
8. Rete-style forward chaining engine
9. Rule actions that directly execute host code inside WASM
10. Rule mutation from the SDK back to the API
11. Streaming push updates to SDKs
12. Differential rule patch sync
13. Multi-region hosted control plane
14. Full enterprise access control model
15. On-prem enterprise deployment packaging

---

# 5. Target Users

## 5.1 Primary user: Backend Developer

A backend developer working in one or more server-side languages who needs:

* local evaluation
* no extra service dependency
* consistent rule behavior across services
* quick integration through normal package managers

## 5.2 Secondary user: Platform / Infra Engineer

An engineer responsible for operational reliability who needs:

* no per-request rule network call
* quick rule shutdown capability
* deterministic, auditable evaluation behavior
* minimal infrastructure burden

## 5.3 Tertiary user: Architect / Technical Decision Maker

A decision-maker evaluating build-vs-buy who cares about:

* consistency across languages
* reduced operational dependency
* open, inspectable core logic
* safe extension path

---

# 6. V1 Product Positioning

KLSwitch v1 is a **generic rule engine internally**, but the **official v1 product focus is APIs**.

This means:

* the core data model is generic enough for future expansion
* the selector model is extensible
* but the supported selector kind for v1 is focused on API matching

The v1 mental model is:

> Select rules relevant to a request, then evaluate request facts locally and synchronously.

---

# 7. Scope Model: Generic by Design, API-First in V1

## 7.1 Design principle

The rule engine must not be tightly coupled to HTTP as a permanent core assumption.

Instead, rules will be split into:

1. **selector** — used to shortlist candidate rules
2. **condition** — used to evaluate facts
3. **action** — describes the result of a match

## 7.2 V1 supported selector kind

V1 officially supports:

* `selector.kind = "api"`

V1 selector keys for API mode:

* `method`
* `path`

Future selector kinds may include:

* `event`
* `entity`
* `workflow`
* `job`
* `pipeline`

These are not implemented in v1.

---

# 8. Functional Scope Summary

V1 includes:

* rule schema definition
* selector-based candidate rule lookup
* nested condition evaluation
* structured action return model
* rule enable/disable klswitch
* inline rules or remote rules loading
* polling-based refresh in SDK wrapper
* in-memory active compiled rules inside WASM engine
* atomic ruleset replacement
* multi-language SDK wrapper architecture
* analytics event emission from wrappers
* logging from wrappers
* status reporting from wrapper + engine

---

# 9. High-Level Architecture

## 9.1 Major layers

### A. WASM Core Layer

Responsible for:

* rule schema validation
* rule compilation
* selector indexing
* in-memory active rules cache
* atomic rule replacement
* synchronous evaluation
* engine status reporting

### B. SDK Wrapper Layer

Responsible for:

* loading the WASM module
* bridging JSON input/output to WASM
* polling remote rules API
* logging
* analytics batching and sending
* public SDK API
* framework adapters (Express, Fastify, Spring, etc.)
* host lifecycle management

### C. Backend Rules API Layer

Responsible for:

* serving rules payloads
* versioning rulesets
* future auth, billing, analytics collection

V1 backend is not the moat. The moat is the engine behavior and SDK experience.

---

# 10. Boundary Definition (Critical)

## 10.1 What MUST live inside WASM

* rule parsing
* rule validation
* selector indexing
* rule compilation
* active compiled rules storage
* atomic active rules replacement
* evaluation logic
* status of the in-engine ruleset

## 10.2 What MUST NOT live inside WASM

* HTTP calls
* polling timers
* logging
* analytics sending
* file system access
* environment variable access
* direct host framework integration

## 10.3 What MUST live inside SDK wrapper

* WASM loading
* polling and refresh timers
* network access to rules API
* logging integration
* analytics batching and sending
* SDK config validation
* public `init/evaluate/refresh/status/close` API
* framework adapters

This boundary is frozen for v1.

---

# 11. Core Data Model

## 11.1 RuleSetPayload

The full ruleset sent from SDK wrapper to WASM.

```json
{
  "schema_version": "1.0",
  "rules_version": 12,
  "rules": []
}
```

### Fields

* `schema_version: string` — required
* `rules_version: integer` — required
* `rules: Rule[]` — required

### Notes

* Full ruleset only in v1, no delta patches
* `rules_version` is wrapper/backend-provided, not internally generated by WASM

---

## 11.2 Rule

```json
{
  "id": "block_large_payment",
  "name": "Block large payment",
  "selector": {
    "kind": "api",
    "keys": {
      "method": "POST",
      "path": "/v1/payments/collect"
    }
  },
  "enabled": true,
  "priority": 100,
  "condition": {
    "all": [
      {
        "field": "body.amount",
        "operator": "gt",
        "value": 50000
      }
    ]
  },
  "action": {
    "type": "block",
    "payload": {
      "statusCode": 429,
      "code": "RULE_BLOCKED"
    }
  },
  "tags": ["payments", "risk"],
  "metadata": {
    "team": "payments"
  }
}
```

### Fields

* `id: string` — required, unique inside a ruleset
* `name: string` — required
* `selector: Selector` — required
* `enabled: boolean` — required
* `priority: integer` — optional, default `0`
* `condition: ConditionNode` — required
* `action: Action` — required
* `tags: string[]` — optional
* `metadata: object` — optional, not used by evaluation

---

## 11.3 Selector

V1 generic structure:

```json
{
  "kind": "api",
  "keys": {
    "method": "POST",
    "path": "/v1/payments/collect"
  }
}
```

### Fields

* `kind: string` — required
* `keys: object<string, string>` — required

### V1 validation rules

* only `kind = "api"` supported
* `keys.method` required
* `keys.path` required

### V1 path rules

* v1 matches `method` and `path` by **exact string** (see `rule-model.md` §8)
* the wrapper normalizes before evaluate: uppercase the method, use the templated route path
* exact raw URL instance paths should be avoided where the framework exposes a normalized route
* wildcard / pattern / path-parameter matching is **out of scope for v1** (future selector capability, not a model change)

Examples:

* preferred: `/v1/payments/:id`
* avoid if possible: `/v1/payments/123`

---

## 11.4 ConditionNode

Nested boolean tree model.

Allowed forms:

### all node

```json
{
  "all": [
    { "field": "body.amount", "operator": "gt", "value": 50000 },
    { "field": "headers.x-client-id", "operator": "eq", "value": "partner_a" }
  ]
}
```

### any node

```json
{
  "any": [
    { "field": "body.country", "operator": "eq", "value": "IN" },
    { "field": "body.country", "operator": "eq", "value": "US" }
  ]
}
```

### not node

```json
{
  "not": {
    "field": "body.status",
    "operator": "eq",
    "value": "blocked"
  }
}
```

### leaf node

```json
{
  "field": "body.amount",
  "operator": "gt",
  "value": 50000
}
```

### Supported operators in v1

* `eq`
* `neq`
* `gt`
* `gte`
* `lt`
* `lte`
* `contains`
* `not_contains`
* `in`
* `not_in`
* `exists`
* `not_exists`

### Evaluation rules

* missing field => leaf evaluates to false for every operator **except** `exists` / `not_exists` (see `rule-model.md` §7.2)
* no implicit type coercion: a present field of the wrong type => leaf is false for comparison operators (`rule-model.md` §5.1)
* invalid operator, or `in` / `not_in` with a non-array value => rule invalid during load/compile phase (skipped, reported in load result)
* no exceptions are raised for missing or mistyped input data at evaluate time

Operator type semantics and field-path resolution are frozen in
`rule-model.md` §5.1, §7, and §8.

---

## 11.5 Action

Structured object, not plain string.

```json
{
  "type": "block",
  "payload": {
    "statusCode": 429,
    "code": "RULE_BLOCKED"
  }
}
```

### Fields

* `type: string` — required
* `payload: object` — optional

### Notes

The engine returns action data. The engine does not execute host-side business logic.

---

## 11.6 EvaluationInput

```json
{
  "selector": {
    "kind": "api",
    "keys": {
      "method": "POST",
      "path": "/v1/payments/collect"
    }
  },
  "facts": {
    "headers": {
      "x-client-id": "partner_a"
    },
    "query": {},
    "params": {},
    "body": {
      "amount": 70000
    },
    "meta": {}
  }
}
```

### Fields

* `selector: Selector` — required
* `facts: object` — required

### V1 usage

* `selector` is used for rule lookup
* `facts` are used for condition evaluation

---

## 11.7 MatchedRule

```json
{
  "id": "block_large_payment",
  "name": "Block large payment",
  "priority": 100,
  "action": {
    "type": "block",
    "payload": {
      "statusCode": 429,
      "code": "RULE_BLOCKED"
    }
  }
}
```

### Fields

* `id: string`
* `name: string`
* `priority: integer`
* `action: Action`

---

## 11.8 ErrorInfo

```json
{
  "code": "INVALID_OPERATOR",
  "message": "operator xyz is not supported",
  "rule_id": "bad_rule_1",
  "field": "condition.operator"
}
```

### Fields

* `code: string`
* `message: string`
* `rule_id: string?`
* `field: string?`

---

## 11.9 LoadRulesResult

```json
{
  "ok": true,
  "rules_version": 12,
  "rule_count": 10,
  "compiled_rule_count": 9,
  "skipped_rule_count": 1,
  "errors": []
}
```

### Fields

* `ok: boolean`
* `rules_version: integer`
* `rule_count: integer`
* `compiled_rule_count: integer`
* `skipped_rule_count: integer`
* `errors: ErrorInfo[]`

---

## 11.10 EvaluationResult

```json
{
  "ok": true,
  "matched": [
    {
      "id": "block_large_payment",
      "name": "Block large payment",
      "priority": 100,
      "action": {
        "type": "block",
        "payload": {
          "statusCode": 429,
          "code": "RULE_BLOCKED"
        }
      }
    }
  ],
  "evaluated": 5,
  "skipped": 1,
  "errors": []
}
```

### Fields

* `ok: boolean`
* `matched: MatchedRule[]`
* `evaluated: integer`
* `skipped: integer`
* `errors: ErrorInfo[]`

### Frozen behavior

* return **all matched rules** in priority-descending order
* ties (equal `priority`) are broken by ascending declaration order in the `rules` array; implementations use a stable sort (see `rule-model.md` §7.4)
* disabled rules are counted in `skipped`
* invalid rules skipped during compile are reflected through load result, not per-evaluation runtime churn

A wrapper MAY offer a convenience accessor for the top match, but the engine
contract always returns the full ordered `matched` array.

---

## 11.11 EngineStatus

```json
{
  "initialized": true,
  "schema_version": "1.0",
  "active_rules_version": 12,
  "rule_count": 9,
  "last_load_ok": true,
  "last_error": null
}
```

### Fields

* `initialized: boolean`
* `schema_version: string`
* `active_rules_version: integer | null`
* `rule_count: integer`
* `last_load_ok: boolean`
* `last_error: ErrorInfo | null`

---

# 12. Rule Selection and Lookup Model

## 12.1 Why lookup is needed

The engine must not evaluate every rule for every request once rule counts grow.

The engine will:

1. shortlist candidate rules using selector
2. evaluate conditions only on those candidate rules

## 12.2 V1 selector lookup behavior

For `selector.kind = "api"`, the engine indexes rules by:

* method
* path

Conceptual key:

```text
api|POST|/v1/payments/collect
```

During `load_rules`, the engine builds selector indexes for active compiled rules.

During `evaluate`, the engine:

1. reads evaluation selector
2. finds candidate bucket
3. sorts by priority descending if needed
4. evaluates condition tree on candidate subset

## 12.3 Why selector is generic instead of hardcoding HTTP forever

The product is intentionally designed to expand later. A generic selector avoids future breaking schema redesign.

---

# 13. Klswitch Behavior

## 13.1 V1 klswitch definition

Every rule contains:

* `enabled: boolean`

If `enabled = false`, the rule must never match.

## 13.2 Klswitch evaluation order

The engine checks `enabled` before any selector or condition evaluation for that rule.

## 13.3 Refresh behavior

If SDK wrapper polling fetches a new ruleset where a rule has `enabled: false`, that rule stops participating after the next successful ruleset load.

The maximum delay between backend change and local effect is bounded by:

* polling interval
* network success
* successful compile and swap

---

# 14. WASM Interface Contract

## 14.1 Design choice

V1 uses:

* **JSON input / JSON output**
* explicit memory allocation ownership rules
* stateful engine handle model

## 14.2 Exported conceptual functions

The exact naming may vary by binding layer, but v1 conceptually requires:

* `create_engine()`
* `destroy_engine(engine_handle)`
* `load_rules(engine_handle, rules_json)`
* `evaluate(engine_handle, evaluation_input_json)`
* `get_status(engine_handle)`
* `alloc(size)`
* `free(ptr, size)`
* `free_result(ptr, len)`

## 14.3 Memory model

### Frozen rules

* wrapper writes input bytes into WASM memory
* wrapper calls WASM exported function
* WASM returns pointer + length for response buffer
* wrapper copies output bytes out
* wrapper must free returned buffer using agreed function
* wrapper must not keep raw pointers beyond bridge call lifecycle

### Design principle

Ownership of every allocated buffer must be explicit.

---

# 15. Engine State Model

## 15.1 Stateful engine decision

V1 uses a **stateful engine instance inside WASM**.

Each engine instance owns:

* active compiled ruleset
* current rules version
* selector index
* load status
* last load error

## 15.2 Engine instance lifecycle

* one engine instance per SDK client instance
* long-lived
* reused across many evaluations
* explicitly destroyed on shutdown where applicable

## 15.3 Why not stateless-only WASM?

Because storing compiled rules and indexes inside WASM:

* avoids duplication across wrappers
* centralizes compile and swap behavior
* keeps shared behavior truly shared

---

# 16. Atomic Rule Replacement

## 16.1 What compile means

Compile does **not** mean machine code generation.

It means transforming raw rule JSON into validated, runtime-efficient internal data structures, such as:

* normalized operator enums
* resolved selector keys
* condition tree structures
* selector index buckets
* sorted rule lists

## 16.2 Replacement flow

When new rules are loaded:

1. parse full payload
2. validate full payload
3. normalize/compile rules into new internal structures
4. if successful, atomically replace active ruleset
5. if unsuccessful, keep previous active ruleset untouched

## 16.3 Why atomic replacement is required

Evaluations must see either:

* old full ruleset
* or new full ruleset

They must never see:

* partially updated rules
* partially compiled rules
* mixed old/new state

This is a frozen requirement.

---

# 17. SDK Wrapper Public API

## 17.1 Public wrapper interface

Every language wrapper should expose an equivalent API shape:

* `init(config)`
* `evaluate(input)`
* `refresh()`
* `getStatus()`
* `close()`

## 17.2 Initialization sources

SDK wrapper must allow:

* inline rules payload
* remote `rules_url`
* optional polling refresh interval

## 17.3 Initialization expectations

If:

* inline rules are provided => wrapper may initialize from inline rules only
* `rules_url` is provided => wrapper fetches rules during init unless explicitly configured otherwise

V1 default recommendation:

* fail initialization clearly if `rules_url` is configured but initial fetch fails and no inline rules are available

---

# 18. SDK Wrapper Responsibilities in Detail

## 18.1 Polling

Wrapper must support optional polling of `rules_url`.

### V1 behavior

* polling is **disabled** unless `refresh_interval` is explicitly configured (setting `rules_url` alone does not start polling)
* suggested minimum / typical interval when enabled: 30 seconds
* on refresh success: send full ruleset to WASM `load_rules`
* on refresh failure: keep last known good ruleset active

## 18.2 Logging

Logging is wrapper-owned.

Recommended log events:

* wasm load success/failure
* engine create success/failure
* initial rules load success/failure
* refresh success/failure
* rule compile/load warning
* analytics flush failure

The wrapper must not log every evaluation by default.

## 18.3 Analytics

Wrapper owns analytics batching and sending.

Suggested events:

* evaluation count
* matched rule ids
* skipped count
* current rules version
* latency bucket

Rules:

* analytics must not block evaluation path
* raw facts should not be sent by default
* batching is preferred over per-eval network calls

## 18.4 Framework adapters

Each wrapper may expose helper integrations, such as:

* Express middleware (Node)
* Fastify hook (Node)
* Spring interceptor / servlet filter (Java)

These are wrapper-specific conveniences and must not change core engine behavior.

---

# 19. Error Handling Requirements

## 19.1 General rule

Errors must be explicit, stable, and structured.

## 19.2 Initial load failures

If initial rules loading fails and no existing active rules are available:

* wrapper initialization should fail clearly by default

## 19.3 Refresh failures

If polling refresh fails after the engine is already initialized:

* keep last known good rules active
* record/log warning
* do not break evaluation path

## 19.4 Malformed rules

During rules load:

* malformed rules may be skipped individually when safe
* catastrophic ruleset parse failure must reject replacement and preserve old rules

## 19.5 Missing fact fields

Missing fields must evaluate as false, not runtime error.

## 19.6 WASM load failure

If the WASM module fails to load:

* wrapper init must fail clearly
* no deferred surprise errors at first evaluate call

---

# 20. Concurrency and Safety Requirements

## 20.1 Evaluation path

`evaluate()` must be read-only against active state.

## 20.2 Refresh path

`load_rules()` must build replacement state separately before swap.

## 20.3 Expected behavior during simultaneous refresh + evaluate

Evaluation must proceed using a valid active ruleset.

Allowed outcomes during a refresh boundary:

* request evaluates against old ruleset
* request evaluates against new ruleset

Not allowed:

* mixed partial ruleset
* invalid transient state

## 20.4 Thread-safety

Each SDK wrapper must ensure safe host-side interaction with the engine according to host runtime rules.

---

# 21. Performance Requirements

## 21.1 V1 targets

These are target goals, not contractual guarantees for every environment:

* typical evaluation latency: sub-millisecond for modest rulesets
* evaluation latency target: < 1ms for <= 500 candidate rules on modern server hardware
* cold WASM initialization target: < 200ms typical
* evaluation must be synchronous from caller perspective

## 21.2 Performance design choices

To support targets:

* selector-based lookup must shortlist candidate rules
* active compiled rules must be held in memory
* evaluation must not perform network I/O
* evaluation must not reparse rules JSON

---

# 22. Security Requirements

## 22.1 WASM sandbox rule

WASM core must not directly access:

* network
* filesystem
* host environment variables

## 22.2 Input limits

V1 caps (enforced by the engine at load time — see `rule-model.md` §9):

* max 1000 rules per ruleset — exceeding rejects the whole load (`RULE_LIMIT_EXCEEDED`); active ruleset preserved
* max 50 conditions (leaf comparisons) per rule — exceeding skips that rule (`CONDITION_LIMIT_EXCEEDED`)
* max JSON payload byte size — configurable in the wrapper (not engine-enforced)

## 22.3 Transport recommendation

Remote rules fetch should use HTTPS in production.
Wrapper may warn if plain HTTP is used.

---

# 23. Packaging and Distribution Requirements

## 23.1 WASM distribution model

* `.wasm` artifact is bundled inside each SDK package
* no separate runtime download required for v1
* wrapper is responsible for loading the bundled `.wasm`

## 23.2 Package manager targets

V1 wrapper packages:

* npm (Node.js / TypeScript)
* Maven Central (Java / Kotlin)

Post-v1 (architecture supports, not shipped in v1): PyPI, Go module, and a
crates.io Rust-native convenience wrapper. Adding them must not require schema
or WASM-boundary changes.

## 23.3 Why WASM was chosen

The project intentionally takes the WASM route to maximize:

* cross-language behavioral consistency
* single shared engine logic
* architecture portability

---

# 24. Versioning Strategy

Three version lines must exist:

## 24.1 Schema version

Controls data model compatibility.

## 24.2 WASM core version

Controls engine behavior version.

## 24.3 SDK wrapper version

Controls host package lifecycle.

## 24.4 Compatibility rule

The wrapper must verify compatibility with the bundled WASM version and expected schema version during initialization.

---

# 25. Observability and Status

## 25.1 Wrapper-owned status

Wrapper should expose host/runtime status such as:

* last refresh attempt time
* last refresh network result
* analytics queue state
* wasm load result

## 25.2 WASM-owned status

WASM should expose:

* initialized
* active rules version
* compiled rule count
* last load success/failure
* last engine error

## 25.3 Combined SDK status

Wrapper may merge wrapper-owned and wasm-owned status into one public status response.

---

# 26. Pricing and Commercial Boundaries (Product-Level Freeze)

The commercial strategy is not fully implemented in v1 code, but product scope assumes:

* SDK is the product core
* remote rule refresh and klswitch are core value drivers
* ongoing pricing will likely be subscription-based, not one-time license

Commercial features such as trial enforcement, entitlements, or usage limits are backend/platform concerns and not part of v1 engine core.

---

# 27. V1 Language Scope Recommendation

This document defines architecture for multi-language support, but actual execution phases releases. **v1 ships two wrappers** so cross-language behavioral consistency is actually proven (not just claimed) on a JVM language and a Node/V8 language.

v1 rollout order:

1. Node.js / TypeScript wrapper
2. Java / Kotlin wrapper

Post-v1 (architecture-ready, not in v1 scope):

3. Go wrapper
4. Python wrapper

This is implementation sequencing, not a change to the core design.

---

# 28. Testing Requirements

## 28.1 Compliance tests

Shared compliance test suite must validate:

* selector lookup correctness
* operator correctness
* nested condition correctness
* missing field behavior
* enabled=false skip behavior
* action return structure

## 28.2 WASM bridge tests

Must validate:

* alloc/free correctness
* JSON roundtrip correctness
* repeated evaluate stability
* repeated rules replacement stability
* cleanup behavior

## 28.3 Refresh lifecycle tests

Must validate:

* successful refresh replaces active rules
* failed refresh keeps previous rules
* concurrent evaluate during refresh remains safe

## 28.4 Cross-language wrapper tests

Each wrapper must pass the same compliance fixtures.

---

# 29. Operational Scenarios

## 29.1 Normal startup

1. wrapper loads bundled WASM
2. wrapper creates engine instance
3. wrapper loads inline rules or fetches rules from rules API
4. wrapper calls `load_rules`
5. engine becomes active

## 29.2 Evaluate request

1. wrapper builds `EvaluationInput`
2. wrapper calls `evaluate`
3. engine selects candidate rules via selector
4. engine evaluates condition trees
5. wrapper receives `EvaluationResult`
6. wrapper uses result in host logic

## 29.3 Refresh cycle

1. wrapper polls rules API
2. wrapper receives full ruleset payload
3. wrapper calls `load_rules`
4. engine parses + validates + compiles new rules
5. engine atomically swaps active rules if successful
6. wrapper logs success/failure

## 29.4 Disable bad rule in production

1. backend rules API updates rule with `enabled=false`
2. wrapper polls and fetches latest rules
3. engine loads latest ruleset
4. disabled rule stops being considered after successful refresh

---

# 30. Key Frozen Decisions

The following decisions are frozen by this document for v1 unless explicitly revised:

1. Core engine is implemented in Rust and compiled to WASM.
2. WASM is stateful and holds the active compiled rules cache.
3. Wrapper owns polling, logging, analytics, and framework integration.
4. JSON in / JSON out is the v1 WASM boundary.
5. Generic selector model is used, but v1 supports only `selector.kind = "api"`.
6. Rule lookup is based on selector first, condition evaluation second.
7. Nested condition trees are supported in v1.
8. Action is a structured object, not plain string.
9. Engine returns all matched rules in priority-descending order; ties break by declaration order (stable sort).
10. Missing fact fields evaluate to false (except `exists` / `not_exists`); no implicit type coercion.
11. `enabled=false` is the first-class per-rule kill switch.
12. Rule replacement must be atomic.
13. Failed replacement must preserve last known good active rules.
14. One engine instance exists per SDK instance.
15. WASM must not perform network I/O or logging.

---

# 31. Resolved Decisions (previously open)

These were the open questions before freeze. They are now **resolved** for v1:

1. **Path matching** — exact string match on the normalized/templated route
   path (`/v1/payments/:id`). Wildcard / pattern matching is out of scope for
   v1 (future selector capability). See §11.3 and `rule-model.md` §8.
2. **Invalid rules during `load_rules`** — individual invalid rules are skipped
   and reported (`skipped_rule_count` + `errors[]`) with `ok: true`. A
   catastrophic parse / envelope failure rejects the whole load (`ok: false`)
   and preserves the previous active ruleset. See §19.4.
3. **`evaluate()` return shape** — the engine always returns all matches
   (priority-descending, declaration-order tie-break). A wrapper MAY add a
   top-match convenience accessor; the engine contract is unchanged. See
   §11.10.
4. **Polling default** — polling is **disabled** unless `refresh_interval` is
   explicitly configured. There is no implicit polling just because `rules_url`
   is set. See §18.1.

## Still open (does not block engine/wrapper coding)

1. The exact standardized **analytics event schema** wrappers emit in v1
   (field names, latency bucket boundaries). Tracked for §18.3.

---

# 32. Current State and Next Step

The technical sub-documents derived from this master document are **frozen and
in the repo**:

* **JSON Schema Spec** — `spec/schemas/*.schema.json` (ruleset, evaluation
  input/result, engine status, error-info, load-rules-result) with example
  payloads under `spec/examples/`.
* **WASM Host Contract Spec** — `docs/wasm-host-contract.md` (exported
  functions, pointer/length ownership, error and status envelopes).
* **Rule Model & engine semantics** — `docs/rule-model.md` (operators, type
  handling, missing-field rule, tie-break, exact selector matching).
* **Compliance behavior** — `spec/compliance/v1/cases/` (eval + scenario
  fixtures) and `rust-core/IMPLEMENTATION-NOTES.md`.

Execution status and the canonical phase checklist live in
`docs/implementation-plan.md`. As of this revision: Phases 0–3 complete (specs,
fixtures, Rust domain models); **next is Phase 4 — Rule Parsing, Validation &
Compilation**. The wrapper specs (Node, then Java) are produced in their
respective wrapper phases without changing the frozen decisions above.

---

# 33. Final Summary

KLSwitch v1 is a **WASM-powered, multi-language, embeddable rule engine SDK** with a **generic selector-based data model**, a **stateful in-memory compiled engine**, and a **strong API-first initial product focus**.

The engine is generic in architecture, but v1 is intentionally shaped around API request control. It is designed so that future use cases can be added through new selector kinds without replacing the core model.

This document freezes the architecture around the following philosophy:

> Shared computation belongs in WASM.
> Host-specific runtime behavior belongs in the SDK wrapper.
> Rule selection, condition evaluation, and rule replacement must remain deterministic, safe, and consistent across languages.
