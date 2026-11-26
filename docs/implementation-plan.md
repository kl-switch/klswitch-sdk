# KLSwitch — Implementation Plan & Development Todo

**File names:** `docs/implementation-plan.md` (canonical checklist); repo root [`todo.md`](../todo.md) links here.  
**Project:** KLSwitch  
**Status:** Active Working Plan  
**Scope:** End-to-end development roadmap for v1  
**Note:** Backend API provider is **out of scope for now**. We will **mock the rules API** in v1 and build the real backend later.

---

# 1. Purpose

This document is the complete development roadmap for KLSwitch v1.

It is meant to:

- guide development from start to finish
- give the coding agent a clear, step-by-step execution path
- ensure architecture stays frozen
- define validation gates before moving forward
- let tasks be marked as completed progressively

---

# 2. Core Product Direction

KLSwitch is a **WASM-first multi-language rule engine SDK**.

## Frozen principles

- Rust core compiled to WASM
- WASM owns:
  - rule parsing
  - rule validation
  - rule compilation
  - selector indexing
  - active compiled rules in memory
  - atomic rule replacement
  - evaluation
  - engine status
- SDK wrappers own:
  - WASM loading
  - polling
  - logging
  - analytics batching/sending
  - framework adapters
  - public SDK APIs
  - lifecycle management

## v1 scope

- generic engine by design
- API-focused in v1
- selector kind supported in v1: `api`
- selector keys in v1:
  - `method`
  - `path`
- wrapper languages in v1: **Node.js/TypeScript, Java/Kotlin, Go, and Python**

## Out of scope for now

- real backend API provider
- hosted management platform
- auth/billing backend
- browser SDK
- non-API selector kinds
- enterprise deployment model
- (no remaining wrapper languages out of scope — Go and Python both shipped in v1)

For v1, remote rules fetching will use a **mock rules API provider**.

---

# 3. How to Use This File

## Rules

- `[ ]` Work on **one task at a time**
- `[ ]` Do not jump phases unless dependencies are done
- `[ ]` Before marking a task complete:
  - validate expected behavior
  - confirm docs/spec/code are aligned
  - confirm no boundary violations
- `[ ]` The coding agent should update this file by marking tasks done as work progresses

## Completion format

Task lines wrap the marker in **backticks** (dash, space, then `` `[ ]` ``, `` `[x]` ``, etc.) so Markdown preview shows the characters instead of only an empty checkbox widget.

Use:

- `[ ]` = not started
- `[~]` = in progress
- `[x]` = completed
- `[-]` = intentionally skipped / deferred

---

# 4. Master Phase Plan

- `[x]` Phase 0 — Project Setup & Agent Foundation
- `[x]` Phase 1 — Contracts, Schemas & Example Payloads
- `[x]` Phase 2 — Compliance Fixtures & Behavior Freeze
- `[x]` Phase 3 — Rust Core Domain Models
- `[x]` Phase 4 — Rule Parsing, Validation & Compilation
- `[x]` Phase 5 — Selector Lookup & Evaluation Engine
- `[x]` Phase 6 — Active State & Atomic Rule Replacement
- `[x]` Phase 7 — WASM Export Layer & Memory Boundary
- `[x]` Phase 8 — Node SDK Wrapper Foundation
- `[x]` Phase 9 — Node Polling, Logging & Analytics
- `[x]` Phase 10 — Node Framework Adapters
- `[x]` Phase 11 — Java SDK Wrapper Foundation
- `[x]` Phase 12 — Java Polling, Logging & Analytics
- `[x]` Phase 13 — Java Framework Adapters
- `[x]` Phase 14 — Mock Rules API Provider
- `[x]` Phase 15 — End-to-End Validation & Hardening (both wrappers)
- `[x]` Phase 16 — Remove the Python sync/async duplication (SRP/DRY)
- `[x]` Phase 17 — Decouple analytics and manifest from evaluation (SRP/DIP)
- `[~]` Phase 18 — Config validation and cross-SDK surface parity (OCP/consistency) — code done; two decisions owed

> **v1 language scope: Node.js/TypeScript, Java/Kotlin, Go, and Python.** Node
> ships first (Phases 8–10), then Java (Phases 11–13), then Go and Python (each
> built against the same compliance fixtures), so cross-language behavioural
> consistency is proven on V8, the JVM, a pure-Go WASM runtime (wazero), and a
> native-binding Python runtime (wasmtime-py).

---

# 5. Phase 0 — Project Setup & Agent Foundation

## Goal

Prepare the repo, architecture docs, agent rules, and working structure.

## Deliverables

- `[x]` repo structure
- `[x]` architecture docs
- `[x]` agent (Claude Code) setup
- `[x]` working development discipline

## Tasks

- `[x]` Create base folder structure
- `[x]` Add `CLAUDE.md` (project agent rules)
- `[x]` Add `README.md`
- `[x]` Add `docs/architecture.md`
- `[x]` Add `docs/wasm-host-contract.md`
- `[x]` Add `docs/sdk-wrapper-contract.md`
- `[x]` Add `docs/rule-model.md`
- `[x]` Add `docs/review-checklist.md`
- `[x]` Add agent operating guidance (folded into `CLAUDE.md`)
- `[x]` Add `spec/` folder structure
- `[x]` Configure agent rules (`CLAUDE.md`)
- `[x]` Configure agent Skills (`.claude/skills/`)
- `[x]` Configure agent Subagents (`.claude/agents/`)
- `[x]` Configure agent Commands (`.claude/commands/`)
- `[x]` Add this implementation plan file to repo

## Validation before phase complete

- `[x]` The agent correctly summarizes frozen architecture
- `[x]` The agent correctly explains WASM vs wrapper boundary
- `[x]` The agent correctly explains that active rules live inside WASM
- `[x]` The agent correctly explains selector-first lookup
- `[x]` The agent correctly explains wrapper-owned polling/logging/analytics

## Phase completion checklist

- `[x]` All foundation docs exist
- `[x]` The agent understands architecture correctly
- `[x]` Repo structure is stable enough for implementation

---

# 6. Phase 1 — Contracts, Schemas & Example Payloads

## Goal

Freeze the JSON shapes before engine or wrapper implementation.

## Deliverables

- `[x]` stable schema files
- `[x]` stable example payloads

## Tasks — Schema files

- `[x]` Create `spec/schemas/ruleset.schema.json`
- `[x]` Create `spec/schemas/evaluation-input.schema.json`
- `[x]` Create `spec/schemas/evaluation-result.schema.json`
- `[x]` Create `spec/schemas/engine-status.schema.json`
- `[x]` Create `spec/schemas/error-info.schema.json`
- `[x]` Create `spec/schemas/load-rules-result.schema.json`

## Tasks — Freeze data models

- `[x]` Freeze `RuleSetPayload`
- `[x]` Freeze `Rule`
- `[x]` Freeze `Selector`
- `[x]` Freeze `ConditionNode`
- `[x]` Freeze `Action`
- `[x]` Freeze `EvaluationInput`
- `[x]` Freeze `EvaluationResult`
- `[x]` Freeze `EngineStatus`
- `[x]` Freeze `ErrorInfo`
- `[x]` Freeze `LoadRulesResult` (via `load-rules-result.schema.json`)

## Tasks — Example payloads

- `[x]` Create simple API ruleset example
- `[x]` Create nested condition ruleset example
- `[x]` Create evaluation input example
- `[x]` Create evaluation result example with one match
- `[x]` Create evaluation result example with multiple matches
- `[x]` Create load rules success result example
- `[x]` Create load rules partial-invalid-rules result example
- `[x]` Create load rules failure result example
- `[x]` Create engine status example
- `[x]` Create invalid rule error example

## Validation before phase complete

- `[x]` All schemas match frozen docs
- `[x]` All example payloads validate against schemas (manually verified; CI automation tracked in §21)
- `[x]` Required vs optional fields are clear
- `[x]` `selector.kind = "api"` is enforced for v1
- `[x]` API selector requires `method` and `path`
- `[x]` Action is a structured object, not plain string
- `[x]` Evaluation result supports all matches
- `[x]` Error format is stable and reusable

## Phase completion checklist

- `[x]` Schemas are frozen
- `[x]` Example payloads are frozen
- `[x]` No important ambiguity remains in JSON contracts (engine semantics frozen in `docs/rule-model.md` §5.1/§7/§8)

---

# 7. Phase 2 — Compliance Fixtures & Behavior Freeze

## Goal

Create the shared fixtures that define engine behavior.

## Deliverables

- `[x]` reusable compliance fixtures
- `[x]` behavior source of truth for all wrappers

## Tasks

- `[x]` Create fixture format for compliance tests
- `[x]` Add selector lookup fixtures
- `[x]` Add exact method/path fixtures
- `[x]` Add nested `all` fixtures
- `[x]` Add nested `any` fixtures
- `[x]` Add nested `not` fixtures
- `[x]` Add operator fixtures for each supported operator
- `[x]` Add missing-field fixtures
- `[x]` Add `enabled=false` fixtures
- `[x]` Add priority ordering fixtures
- `[x]` Add multiple-match fixtures
- `[x]` Add invalid-rule handling fixtures
- `[x]` Add atomic replacement fixtures
- `[x]` Add replacement-failure-keeps-old-rules fixtures
- `[x]` Add engine status fixtures

## Validation before phase complete

- `[x]` Every important rule behavior is captured
- `[x]` Fixtures are readable and reusable
- `[x]` Fixtures do not depend on wrapper-specific logic
- `[x]` There is no behavior left to “guess later” (operator type handling, missing-field rule, and tie-break frozen in `docs/rule-model.md`)

## Phase completion checklist

- `[x]` Shared behavior contract is frozen
- `[x]` Fixtures are ready to drive Rust and wrapper tests

---

# 8. Phase 3 — Rust Core Domain Models

## Goal

Implement Rust domain types matching frozen schemas.

## Deliverables

- `[x]` Rust structs/enums for all core models

## Tasks

- `[x]` Create `rust-core/Cargo.toml` and `rust-core/src/lib.rs`
- `[x]` Create `rust-core/src/rules.rs`
- `[x]` Create `rust-core/src/selector.rs`
- `[x]` Create `rust-core/src/condition.rs`
- `[x]` Create `rust-core/src/actions.rs`
- `[x]` Create `rust-core/src/evaluation.rs`
- `[x]` Create `rust-core/src/facts.rs`
- `[x]` Create `rust-core/src/load_result.rs`
- `[x]` Create `rust-core/src/status.rs`
- `[x]` Create `rust-core/src/errors.rs`
- `[x]` Create `rust-core/src/json.rs`
- `[x]` Define `RuleSetPayload` and `Rule` Rust types
- `[x]` Define `Selector` Rust type
- `[x]` Define `ConditionNode`, `ConditionLeaf`, and `Operator` Rust types
- `[x]` Define `Action` Rust type
- `[x]` Define `EvaluationInput`, `EvaluationResult`, and `MatchedRule` Rust types
- `[x]` Define `Facts` Rust type
- `[x]` Define `EngineStatus` Rust type
- `[x]` Define `ErrorInfo` Rust type
- `[x]` Define `LoadRulesResult` Rust type
- `[x]` Define `RulesVersion` Rust type (untagged integer|string for payload input)
- `[x]` Add serde (`Serialize` / `Deserialize`) and JSON roundtrip tests (`tests/roundtrip_examples.rs` for valid `spec/examples/*.json` only)

## Validation before phase complete

- `[x]` Rust types deserialize example payloads correctly
- `[x]` Serialization matches schema expectations
- `[x]` No wrapper concerns leak into Rust types

## Phase completion checklist

- `[x]` Rust models are stable
- `[x]` All example payloads roundtrip correctly

---

# 9. Phase 4 — Rule Parsing, Validation & Compilation

## Goal

Build the load pipeline in Rust.

## Deliverables

- `[x]` rule parsing
- `[x]` schema-level validation
- `[x]` runtime compilation

## Tasks

- `[x]` Create `rust-core/src/compiler.rs`
- `[x]` Parse full ruleset payload (lenient envelope so one bad rule doesn't fail the load)
- `[x]` Validate selector kind
- `[x]` Validate required selector keys
- `[x]` Validate supported operators
- `[x]` Validate condition tree shapes
- `[x]` Validate action structure
- `[x]` Apply default priority when missing
- `[x]` Normalize operators to internal form
- `[x]` Compile condition tree to runtime form
- `[x]` Prepare compiled rule structure (`CompiledRule` with resolved method/path + declaration `order`)
- `[x]` Return structured errors

## Validation before phase complete

- `[x]` Valid rules compile successfully
- `[x]` Invalid rules produce stable errors (`INVALID_OPERATOR` etc., matched to fixtures)
- `[x]` Compiler output is runtime-friendly
- `[x]` No active state mutation happens here (compiler returns a `CompileOutcome`; activation is Phase 6)

## Phase completion checklist

- `[x]` Compiler is stable for valid and invalid input
- `[x]` Error behavior is frozen enough for engine state integration

**Done:** `rust-core/src/compiler.rs` (`compile_ruleset` → `CompileOutcome`).
Verified by 8 compiler unit tests + a fixture-driven test that checks the
compiler against all 10 `load_rules` steps in `spec/compliance/v1/cases/`
(`tests/compiler_load_fixtures.rs`). `cargo test` and `cargo clippy -D warnings`
both clean.

---

# 10. Phase 5 — Selector Lookup & Evaluation Engine

## Goal

Implement candidate rule selection and condition evaluation.

## Deliverables

- `[x]` selector index
- `[x]` evaluation engine
- `[x]` correct match behavior

## Tasks

- `[x]` Create `rust-core/src/evaluator.rs`
- `[x]` Build API selector index key strategy (`SelectorIndex`: exact `(method, path)` buckets)
- `[x]` Implement candidate lookup by `method + path`
- `[x]` Implement `all`
- `[x]` Implement `any`
- `[x]` Implement `not`
- `[x]` Implement leaf evaluation
- `[x]` Implement all supported operators
- `[x]` Implement missing-field => false behavior (uniform except `exists`/`not_exists`)
- `[x]` Implement `enabled=false` skip behavior
- `[x]` Implement multiple-match collection
- `[x]` Implement priority-descending result ordering (with declaration-order tie-break)

## Validation before phase complete

- `[x]` Selector lookup works correctly
- `[x]` Nested conditions behave correctly
- `[x]` Disabled rules never match
- `[x]` Returned matches are ordered correctly
- `[x]` Compliance fixtures pass for evaluation behavior

## Phase completion checklist

- `[x]` Evaluation engine behavior is aligned with fixtures
- `[x]` No remaining ambiguity in evaluation semantics

**Done:** `rust-core/src/evaluator.rs` (`SelectorIndex` + `evaluate`), with the
boolean tree-walk added to `ConditionNode::evaluate`. Verified by 9 evaluator
unit tests (covering frozen edge cases: no type coercion, uniform missing-field,
`not_contains` on wrong type, nested/missing dotted paths, tie-break) plus a
fixture-driven test over all 23 `eval-*` fixtures (`tests/eval_fixtures.rs`).
`cargo test` (33 tests) and `cargo clippy -D warnings` both clean. Counts are
candidate-scoped: `skipped` = disabled candidates, `evaluated` = enabled
candidates whose condition ran.

---

# 11. Phase 6 — Active State & Atomic Rule Replacement

## Goal

Implement stateful engine ownership inside WASM core.

## Deliverables

- `[x]` active engine state
- `[x]` atomic replace behavior
- `[x]` stable status reporting

## Tasks

- `[x]` Create `rust-core/src/engine.rs`
- `[x]` Define engine state container (`Engine` with `Option<ActiveState>`)
- `[x]` Store active compiled ruleset
- `[x]` Store active rules version
- `[x]` Store selector indexes with active rules (`ActiveState { set, index }`)
- `[x]` Store last load result (`last_load_ok`)
- `[x]` Store last engine error (`last_error`)
- `[x]` Implement safe load-and-swap flow
- `[x]` Ensure failed loads do not replace active rules
- `[x]` Update status after successful load
- `[x]` Update status after failed load

## Validation before phase complete

- `[x]` Engine starts in predictable state
- `[x]` Successful load activates new rules
- `[x]` Failed load preserves old rules
- `[x]` Evaluate sees only valid active state
- `[x]` Status accurately reflects engine condition

## Phase completion checklist

- `[x]` Active state ownership is correct
- `[x]` Atomic replacement behavior is correct

**Done:** `rust-core/src/engine.rs` (`Engine` with `load_rules` / `evaluate` /
`status`). `load_rules` swaps the active ruleset only on success and preserves
last-known-good on failure; `schema_version` is the constant engine version
(`SCHEMA_VERSION = "1.0"`). Verified by 5 engine unit tests plus a capstone test
(`tests/scenario_fixtures.rs`) that replays **all 8 scenario fixtures (17 steps)**
— load/replace/status — through one engine. `cargo test` (40 tests) and
`cargo clippy -D warnings` clean.

---

# 12. Phase 7 — WASM Export Layer & Memory Boundary

## Goal

Expose stable WASM exports with explicit memory ownership.

## Deliverables

- `[x]` exported engine lifecycle
- `[x]` load_rules
- `[x]` evaluate
- `[x]` status
- `[x]` memory helpers

## Tasks

- `[x]` Create `rust-core/src/memory.rs`
- `[x]` Create `rust-core/src/exports.rs`
- `[x]` Update `rust-core/src/lib.rs`
- `[x]` Implement `create_engine` (`ks_create_engine`)
- `[x]` Implement `destroy_engine` (`ks_destroy_engine`)
- `[x]` Implement `load_rules` (`ks_load_rules`)
- `[x]` Implement `evaluate` (`ks_evaluate`)
- `[x]` Implement `get_status` (`ks_get_status`)
- `[x]` Implement `alloc` (`ks_alloc`)
- `[x]` Implement `free` (`ks_free`)
- `[x]` Implement output/result freeing (`ks_free_result`, length-prefixed buffers)
- `[x]` Return structured JSON responses
- `[x]` Handle invalid engine handle cases safely (`INVALID_HANDLE`)
- `[x]` Handle invalid JSON input safely (`INVALID_INPUT`)

## Validation before phase complete

- `[x]` Exported API matches host contract (documented in `docs/wasm-host-contract.md` §8)
- `[x]` JSON in/out works correctly
- `[x]` Memory lifecycle is explicit and testable
- `[x]` Repeated calls do not obviously leak state incorrectly (1000× load+evaluate loop)

## Phase completion checklist

- `[x]` WASM boundary is stable enough for wrapper work
- `[x]` Engine lifecycle works end-to-end inside WASM

**Done:** `rust-core/src/memory.rs` + `exports.rs` expose 8 `ks_*` exports
(JSON-in/JSON-out, `ks_` prefix avoids libc clashes; length-prefixed result
buffers; thread-local engine handle table). `Cargo.toml` builds
`crate-type = ["cdylib", "rlib"]`. **Verified: compiles to
`wasm32-unknown-unknown` and the `.wasm` exports all 8 `ks_*` symbols + `memory`;
host ABI test (`tests/wasm_abi.rs`) drives the real exports through the documented
memory protocol** (lifecycle, invalid handle, malformed input, 1000× stability).
`cargo test` (44 tests) and `cargo clippy -D warnings` clean. Concrete ABI frozen
in `docs/wasm-host-contract.md` §8.

---

# 13. Phase 8 — Node SDK Wrapper Foundation

## Goal

Build the first production wrapper around the WASM engine.

## Deliverables

- `[x]` wrapper config
- `[x]` bridge
- `[x]` client API
- `[x]` status surface

## Tasks

- `[x]` Create `sdk-node/src/types.ts`
- `[x]` Create `sdk-node/src/config.ts`
- `[x]` Create `sdk-node/src/wasm-bridge.ts`
- `[x]` Create `sdk-node/src/client.ts`
- `[x]` Create `sdk-node/src/status.ts`
- `[x]` Define wrapper config types
- `[x]` Define public result types
- `[x]` Load bundled WASM (`WasmBridge.load`, instantiate with zero imports)
- `[x]` Create engine instance through bridge
- `[x]` Implement JSON input to WASM (alloc + write, re-view after grow)
- `[x]` Implement JSON output from WASM (length-prefixed read + free)
- `[x]` Implement `init`
- `[x]` Implement `evaluate`
- `[~]` Implement `refresh` (API method present; remote fetch/polling is Phase 9 — currently throws)
- `[x]` Implement `getStatus`
- `[x]` Implement `close`

## Validation before phase complete

- `[x]` Wrapper API matches frozen wrapper contract (`init`/`evaluate`/`refresh`/`getStatus`/`close`)
- `[x]` Memory is freed correctly after bridge calls (try/finally frees input + result; 500× stability test)
- `[x]` Wrapper does not own compiled rule cache (engine owns it inside WASM)
- `[x]` Wrapper can initialize from inline rules

## Phase completion checklist

- `[x]` Basic wrapper lifecycle works
- `[x]` Wrapper can evaluate successfully via WASM

**Done:** `sdk-node/` wrapper — `WasmBridge` (JSON in/out, length-prefixed
buffers, memory-grow-safe views, unsigned-pointer coercion), `KLSwitch` client
(`init`/`evaluate`/`refresh`/`getStatus`/`close`), config + combined status, TS
types. The engine is bundled as `sdk-node/wasm/klswitch_core.wasm` (regenerate
via `npm run build:wasm`). **First real WebAssembly execution:** Node tests pass
(8/9, 1 Phase-9 skip) including a cross-language parity test that runs every
`eval-*` compliance fixture through the wrapper and matches the frozen results
byte-for-byte. `tsc --noEmit` and `tsc` build both clean. `refresh()` is a Phase 9
stub.

---

# 14. Phase 9 — Node Polling, Logging & Analytics

## Goal

Add runtime capabilities outside WASM.

## Deliverables

- `[x]` polling manager
- `[x]` logger adapter
- `[x]` analytics reporter

## Tasks

- `[x]` Create `sdk-node/src/polling-manager.ts`
- `[x]` Create `sdk-node/src/logger.ts`
- `[x]` Create `sdk-node/src/analytics.ts`
- `[x]` Add polling config support (`rulesUrl`, `refreshInterval`, `headers`, `timeoutMs`, `fetchImpl`)
- `[x]` Implement refresh interval behavior (disabled unless `refreshInterval` set; unref'd timer)
- `[x]` Implement remote rules fetch (injectable `fetch`; real mock API provider is Phase 14)
- `[x]` Pass fetched rules into `load_rules` (`applyRules` → bridge)
- `[x]` Keep last known good rules on refresh failure
- `[x]` Implement logger interface (`Logger`, `consoleLogger`, `noopLogger` default)
- `[x]` Add init/refresh/error log points
- `[x]` Implement analytics queue
- `[x]` Implement analytics batching (size + interval, unref'd timer)
- `[x]` Ensure analytics never blocks evaluate path (`record()` is a sync enqueue)

## Validation before phase complete

- `[x]` Polling is outside WASM
- `[x]` Refresh failure does not break active rules (tested: keeps last known good)
- `[x]` Logging is outside WASM
- `[x]` Analytics is outside WASM
- `[x]` Analytics does not block evaluation

## Phase completion checklist

- `[x]` Runtime wrapper concerns are working correctly
- `[x]` WASM boundary remains clean

**Done:** `polling-manager.ts` (fetch + unref'd poll timer, last-known-good on
failure), `logger.ts` (`Logger` + console/noop), `analytics.ts` (batched,
non-blocking, unref'd flush timer, sink errors logged). Client wires them into
`init`/`evaluate`/`refresh`/`close` and exposes refresh state in `getStatus`.
`refresh()` is now fully implemented.

**Review hardening (independent + self):** single-flight analytics flush +
`maxQueueSize` cap (no unbounded sink concurrency or queue growth); coalesced
polls (a slow fetch can't apply an older version after a newer one); post-`close`
poll callbacks no-op; analytics config validation. 26 Node tests pass (incl.
refresh-keeps-old, analytics non-blocking/capped/single-flight, poll coalescing,
post-close race), process exits cleanly (no hanging timers), `tsc --noEmit` clean.

---

# 15. Phase 10 — Node Framework Adapters

## Goal

Provide ergonomic app integration.

## Deliverables

- `[x]` request translation utilities
- `[x]` framework adapters

## Tasks

- `[x]` Create `sdk-node/src/request-context.ts`
- `[x]` Create `sdk-node/src/adapters/express.ts`
- `[x]` Create `sdk-node/src/adapters/fastify.ts`
- `[x]` Translate framework request to evaluation input (`toEvaluationInput`)
- `[x]` Normalize `method` (uppercase)
- `[x]` Normalize `path` (prefer templated route; strip query on fallback)
- `[x]` Build `facts` from request parts (all five buckets; non-object → `{}`)
- `[x]` Implement Express adapter (`klswitchMiddleware`)
- `[x]` Implement Fastify adapter (`klswitchHook`)

## Validation before phase complete

- `[x]` Framework code stays outside WASM
- `[x]` Method/path extraction is correct (tested: templated route, uppercased method, query strip)
- `[x]` Evaluation input matches schema (all five fact buckets present)
- `[x]` Adapters are thin integration layers only (map request → result; no action interpretation; no framework dependency — structural types)

## Phase completion checklist

- `[x]` Express adapter works
- `[x]` Fastify adapter works

**Done:** `request-context.ts` (`toEvaluationInput` + `pathWithoutQuery`),
`adapters/express.ts` (`klswitchMiddleware`), `adapters/fastify.ts`
(`klswitchHook`). Dependency-free (structural request types, no `@types/express`
/Fastify); default attaches the result to the request, `onResult` for custom
handling; Express errors route to `next(err)`.

**Review hardening (independent + self):** widened `ExpressLikeRequest.route.path`
to `string | RegExp | (string|RegExp)[]` and guard at runtime — fixes (a) silent
no-match on RegExp/array routes and (b) type-incompatibility with Express 5's
`RequestHandler`; documented the `onResult`-must-call-`next()` footgun. 38 Node
tests pass (regressions for baseUrl composition, RegExp route fallback, app-level
fallback, Fastify v3 `routerPath`), `tsc --noEmit` clean. **Node wrapper complete
(Phases 8–10).**

---

# 16. Phase 11 — Java SDK Wrapper Foundation

## Goal

Build the second production wrapper (JVM) around the same WASM engine, proving
cross-language behavioral consistency.

## Deliverables

- `[x]` wrapper config
- `[x]` WASM bridge (host WASM runtime: **Chicory**, pure-Java, no native deps)
- `[x]` client API
- `[x]` status surface

## Tasks

- `[x]` Set up `sdk-java/pom.xml` with a JVM WASM runtime dependency (Chicory + Jackson; JDK 21 build, `release` 17)
- `[x]` Implement `Config.java` (wrapper config types; builder)
- `[x]` Implement `WasmBridge.java` (load bundled `.wasm` from resources, alloc/copy/free, JSON in/out, length-prefixed result)
- `[x]` Implement `Client.java` public API: `init`, `evaluate`, `refresh`, `getStatus`, `close`
- `[x]` Implement status surface (`EngineStatus.java` record; `Status.java` reserved for the Phase-12 combined wrapper status)
- `[x]` Create engine instance through the bridge
- `[x]` Free returned buffers correctly after each bridge call (try/finally)
- `[x]` Initialize from inline rules

## Validation before phase complete

- `[x]` Wrapper API matches frozen wrapper contract (`init`/`evaluate`/`refresh`/`getStatus`/`close`)
- `[x]` Memory is freed correctly after bridge calls
- `[x]` Wrapper does not own compiled rule cache (engine owns it inside WASM)
- `[x]` Wrapper passes the **same** compliance fixtures as Node (eval + scenario)

## Phase completion checklist

- `[x]` Basic Java wrapper lifecycle works
- `[x]` Java wrapper can evaluate successfully via WASM

**Done:** `sdk-java` — `WasmBridge` (Chicory, JSON in/out, length-prefixed
buffers), `Client` (`init`/`evaluate`/`getStatus`/`close`; `refresh` is a Phase-12
stub), `Config` builder, typed result records (Jackson, snake_case). Engine
bundled at `src/main/resources/klswitch_core.wasm`. **Cross-language parity
proven:** replays every eval fixture and all 8 scenario fixtures through the JVM
and matches the frozen results byte-for-byte — the same suite Rust and Node pass.
Built on JDK 21 / Maven 3.6.3 (system default untouched), targeting release 17.

**Review hardening (independent + self):** the headline fix is **thread safety** —
the JVM is multi-threaded and a Chicory instance has one shared operand stack +
linear memory + (engine) RefCell registry, so a shared `Client` is now serialized
on a per-bridge lock (a Spring singleton can be hit concurrently). Plus: release
the Chicory instance on `close()`; `getStatus()` after close returns a
not-initialized status (Node parity) instead of throwing; unsigned pointer
coercion (`& 0xFFFFFFFFL`, matching Node's `>>> 0`) + null-result guard. 8 JUnit
tests pass, including a 2000-evaluation concurrency test on a shared client.

---

# 17. Phase 12 — Java Polling, Logging & Analytics

## Goal

Add runtime capabilities outside WASM for the Java wrapper.

## Deliverables

- `[x]` polling manager
- `[x]` logger adapter
- `[x]` analytics reporter

## Tasks

- `[x]` Implement `PollingManager.java` (synchronized poll, daemon timer, last-known-good on failure)
- `[x]` Implement `LoggerAdapter.java` (`Logger` interface + NOOP/CONSOLE; default silent)
- `[x]` Implement `AnalyticsReporter.java` (single daemon-thread drain = single-flight; queue cap)
- `[x]` Add polling config + refresh interval behavior (disabled unless `refreshIntervalMs` set)
- `[x]` Fetch remote rules (injectable `RulesFetcher`; default `java.net.http`; real mock API is Phase 14)
- `[x]` Keep last known good rules on refresh failure
- `[x]` Add init/refresh/error log points
- `[x]` Implement analytics queue + batching; never block evaluate path (`record` enqueues, daemon thread sends)

## Validation before phase complete

- `[x]` Polling, logging, analytics are outside WASM
- `[x]` Refresh failure does not break active rules (tested: keeps last known good)
- `[x]` Analytics does not block evaluation

## Phase completion checklist

- `[x]` Java runtime wrapper concerns work correctly
- `[x]` WASM boundary remains clean

**Done:** `PollingManager` (synchronized polls so a slow fetch can't apply an older
version; daemon timer = Node's unref'd timer), `Logger`/`LoggerAdapter`,
`AnalyticsReporter` (non-blocking `record`, single-flight drain on one daemon
thread, `maxQueueSize` cap, sink errors logged). `Client` wires them in; `refresh()`
is now live; `getStatus()` returns a combined `Status` (engine fields flattened +
`ready`/`polling`/`lastRefresh*`, matching Node). Mirrors the Node Phase-9 review
hardening. **20 JUnit tests pass** (incl. refresh-keeps-old, recurring poll,
analytics non-blocking/capped/single-flight); `examples-java` still green against
the updated SDK.

---

# 18. Phase 13 — Java Framework Adapters

## Goal

Provide ergonomic JVM app integration.

## Deliverables

- `[x]` request translation utilities (`RequestContext`)
- `[x]` JAX-RS adapter (`KLSwitchFilter` + `@KLSwitchBinding`)

## Tasks

- `[x]` Implement `RequestContext.java` (translate request to evaluation input)
- `[x]` Normalize `method` (uppercase) and `path` (templated route)
- `[x]` Build `facts` from request parts (headers/query/params/body/meta)
- `[x]` Implement a JAX-RS `ContainerRequestFilter` adapter

## Validation before phase complete

- `[x]` Framework code stays outside WASM
- `[x]` Method/path extraction is correct
- `[x]` Evaluation input matches schema
- `[x]` Adapter is a thin integration layer only

## Phase completion checklist

- `[x]` JAX-RS adapter works
- `[x]` Java request normalization matches Node behavior

**Done (2026-05-25):**

- `RequestContext.toEvaluationInput(...)` — framework-agnostic translation of
  request parts into the engine's `EvaluationInput` JSON: uppercases the method,
  uses the caller-supplied templated path (engine matches exactly — rule-model
  §8), and guarantees all five fact buckets (headers/query/params/body/meta) are
  present, coercing a non-object body to `{}`. No framework types leak in.
- JAX-RS adapter chosen over Spring: the demo server is Dropwizard/Jersey, so a
  `ContainerRequestFilter` is the natural fit and exercises the same stack the
  examples use. `jakarta.ws.rs-api` is a `provided` dependency (the host app
  brings its own JAX-RS runtime).
- `@KLSwitchBinding` (`@NameBinding`, `path()` default `""`) opts a resource
  method/class into the filter and lets the app declare the canonical templated
  path (e.g. `/v1/payments/:id`) that rules target — decoupling rule selectors
  from JAX-RS's `{id}` syntax.
- `KLSwitchFilter` (name-bound `@Provider`): injects `@Context ResourceInfo`
  to read the matched method's declared path, extracts lowercased headers +
  query + path params, reads and **resets** the JSON request body (so the
  resource can still consume it), calls `RequestContext` + `Client.evaluate`,
  aborts with the action's `statusCode` on a `block` match, and otherwise
  attaches the `EvaluationResult` under `KLSwitchFilter.RESULT_PROPERTY`.
- Robustness (from review): the filter null-guards a matched rule's `action()`,
  and a malformed JSON body fails safe (parsed as no body facts) instead of 500.
- Tests: `RequestContextTest` (sdk-java, 2) + `KLSwitchFilterTest`
  (examples-java, 3 — block + allow-with-body-preserved + malformed-body
  fail-safe, via in-memory Jersey `ResourceExtension`). The demo
  `KLSwitchGuard` now delegates input building to `RequestContext`. `mvn test`
  green: sdk-java 22, examples-java 9.

---

# 19. Phase 14 — Mock Rules API Provider

## Goal

Provide a temporary mocked API provider for rules fetching.

## Important note

This is **not** the real backend.  
This is only a mock provider so wrapper polling can be validated in v1.

## Deliverables

- `[x]` mock rules API response contract (`backend/openapi/rules-api.yaml`)
- `[x]` static mock fixtures + in-process test servers per wrapper

## Tasks

- `[x]` Create `backend/examples/mock-rules-response.json`
- `[x]` Create `backend/openapi/rules-api.yaml` or minimal endpoint doc
- `[x]` Define mock rules fetch response shape
- `[x]` Validate wrapper polling against mock rules endpoint
- `[x]` Add invalid payload mock response
- `[x]` Add changed rules version mock response

## Validation before phase complete

- `[x]` Wrapper can initialize from mock remote rules
- `[x]` Wrapper can refresh from mock API
- `[x]` Invalid mock response is handled safely
- `[x]` Mock provider is clearly marked temporary

## Phase completion checklist

- `[x]` Remote rules path is testable without real backend
- `[x]` Future backend can replace mock without breaking wrapper contract

**Done (2026-05-25):**

- Canonical, language-neutral fixtures in `backend/examples/`:
  `mock-rules-response.json` (`rules_version: 1`),
  `mock-rules-response-v2.json` (`rules_version: 2`, tightened/added rules),
  `mock-rules-invalid.json` (a non-ruleset payload). The response body **is**
  the ruleset (the shape passed verbatim to `load_rules`,
  `spec/schemas/ruleset.schema.json`) — no outer envelope, no ETag/304 in v1.
- `backend/openapi/rules-api.yaml` documents `GET /v1/ruleset` (200 ruleset +
  4xx/5xx), referencing the spec schema, clearly marked MOCK. `backend/README.md`
  updated to point at all artifacts.
- Polling validated over a **real local HTTP server** through each wrapper's
  default HTTP fetcher (not an injected fetcher): Node uses `http.createServer`
  (`sdk-node/test/mock-rules-api.test.ts`, 4 tests), Java uses the JDK
  `com.sun.net.httpserver.HttpServer` (`sdk-java/.../MockRulesApiTest.java`, 4
  tests). Both cover: init-from-URL, `refresh()` v1→v2 version bump (and the new
  rules actually take effect), invalid 200 payload → keep last-known-good +
  `lastRefreshOk=false`, and 5xx → keep last-known-good + failed refresh.
- Both suites read the **same** `backend/` fixtures, so the mock contract is
  shared. `npm test` 42 (was 38); `mvn test` sdk-java 26 (was 22).

---

# 20. Phase 15 — End-to-End Validation & Hardening (both wrappers)

## Goal

Make the system stable enough for serious internal testing.

## Deliverables

- `[x]` docs aligned
- `[x]` tests passing
- `[x]` known limitations documented
- `[x]` MVP-ready flow

## Tasks

- `[x]` Review docs vs code
- `[x]` Review schemas vs code
- `[x]` Review examples vs code
- `[x]` Review compliance fixtures vs behavior
- `[x]` Review WASM host contract vs bridge implementation
- `[x]` Review wrapper boundary compliance
- `[x]` Add known limitations notes
- `[x]` Add release checklist
- `[x]` Add versioning notes
- `[-]` Tag MVP-ready milestone internally — left to the maintainer (merge the
  feature branches, then `git tag -a`; `scripts/release-checklist.sh` is the gate)

## Validation before phase complete

- `[x]` Full compliance suite passes
- `[x]` Engine replacement behavior is stable
- `[x]` Wrapper lifecycle is stable
- `[x]` Polling flow is stable
- `[x]` End-to-end init → evaluate → refresh → status → close works

## Phase completion checklist

- `[x]` KLSwitch v1 is internally usable
- `[x]` Boundaries are preserved
- `[x]` Contracts are stable

**Done (2026-05-25):**

- **Independent end-to-end audit** across six axes (docs/schemas/examples/
  fixtures/WASM-ABI/boundary): **no blockers**. The frozen architecture boundary,
  WASM ABI (`ks_*`, length-prefixed buffers, free-after-copy), and all frozen
  semantics (selector-first, priority-desc + declaration-order tie-break,
  `enabled:false` skip, missing-field→false except exists, no coercion, atomic
  last-known-good replacement) are honored in Rust and mirrored by both bridges.
- **Cross-language parity completed:** the Node suite now also replays all 8
  **scenario** fixtures (load/atomic-replacement/status), matching Rust and Java
  byte-for-byte — previously Node only ran the eval fixtures. So Rust ≡ Node ≡
  Java across the full `spec/compliance/v1/` set.
- **Fixed example drift:** `spec/examples/load-rules-failure.json` used an error
  code (`RULE_VALIDATION_FAILED`) the engine never emits → now `INVALID_OPERATOR`
  with the canonical message.
- **Automation:** filled the three `scripts/` (`validate-schemas.sh`,
  `run-compliance-tests.sh`, `release-checklist.sh`). The release gate rebuilds
  the wasm, runs Rust + Node + Java against one engine, and enforces the
  WASM-core boundary; it passes green.
- **Docs:** added `docs/known-limitations.md`; added versioning notes + a release
  checklist to `docs/release-strategy.md`; gave the root `README.md` a status +
  quickstart; removed root cruft (`klswitch-sdk-full.zip`, `todo.md`).
- Suite totals: Rust 11 test binaries, Node 43, sdk-java 26, examples 38 (Node
  demo) + examples-java 9. The MVP-ready tag itself is deferred to the maintainer
  (branch integration is a human decision).

---

# 21. Cross-Phase Validation Checklist

Before marking any task complete, verify:

- `[ ]` Does this respect WASM vs wrapper boundary?
- `[ ]` Does it match frozen schemas?
- `[ ]` Does it preserve JSON-only boundary?
- `[ ]` Does it preserve explicit memory ownership?
- `[ ]` Does it preserve selector-first lookup?
- `[ ]` Does it preserve all-match behavior?
- `[ ]` Does it preserve `enabled=false` skip behavior?
- `[ ]` Does it preserve atomic replacement?
- `[ ]` Does it keep last known good rules on replacement failure?
- `[ ]` Are tests or fixtures updated if behavior changed?
- `[ ]` Are docs/examples still aligned?

---

# 22. Agent Working Method

Use the coding agent (Claude Code) like this for every task:

## Step 1 — Read

Have the agent read:

- `[ ]` `CLAUDE.md`
- `[ ]` relevant docs
- `[ ]` relevant schemas/examples

## Step 2 — Plan

Have the agent state:

- `[ ]` what files it will modify
- `[ ]` what it will add
- `[ ]` assumptions
- `[ ]` risks

## Step 3 — Approve

Review the plan before coding.

## Step 4 — Implement

Have the agent modify only the agreed files.

## Step 5 — Self-review

Have the agent review its output against:

- `[ ]` `docs/architecture.md`
- `[ ]` `docs/wasm-host-contract.md`
- `[ ]` `docs/review-checklist.md`

## Step 6 — Update this file

Have the agent mark completed tasks as `[x]`.

---

# 23. Definition of Done for MVP

KLSwitch v1 is done when all below are true:

- `[x]` schemas are frozen
- `[x]` example payloads are frozen
- `[x]` compliance fixtures are frozen
- `[ ]` Rust engine parses, validates, compiles, indexes, stores, replaces, and evaluates rules
- `[ ]` WASM exports are stable
- `[ ]` Node wrapper can `init`, `evaluate`, `refresh`, `getStatus`, and `close`
- `[ ]` Java wrapper can `init`, `evaluate`, `refresh`, `getStatus`, and `close`
- `[ ]` polling works using mock API provider
- `[ ]` failed refresh preserves last known good rules
- `[ ]` logging and analytics exist outside WASM (both wrappers)
- `[ ]` Express/Fastify adapters work (Node)
- `[ ]` Spring adapter works (Java)
- `[ ]` Node and Java wrappers pass the **same** compliance fixtures (cross-language consistency)
- `[ ]` end-to-end local flow works from rules fetch to rule evaluation

---

# 24. Immediate Next Steps

Start here:

## Current Priority

- `[x]` Phase 1 — Contracts, Schemas & Example Payloads (**follow-up:** doc/schema parity review; **automated** Draft 2020-12 validation in CI for schemas, examples, and compliance JSON)
- `[x]` Phase 2 — Compliance Fixtures & Behavior Freeze (**follow-up:** optional `spec/compliance/v1/cases/README.md`; run fixtures against a real engine when Rust/WASM lands; tie-break frozen in `docs/rule-model.md` §7.4 and `eval-equal-priority.json` authored)
- `[x]` Phase 3 — Rust Core Domain Models (**follow-up:** keep `spec/examples` and serde attributes aligned with schemas; Phase 4+ adds compiler and engine behavior)
- `[x]` Phase 4 — Rule Parsing, Validation & Compilation (`compiler.rs`; validated against load fixtures)
- `[x]` Phase 5 — Selector Lookup & Evaluation Engine (`evaluator.rs`; validated against all eval fixtures)
- `[x]` Phase 6 — Active State & Atomic Rule Replacement (`engine.rs`; all scenario fixtures replay through the engine)
- `[x]` Phase 7 — WASM Export Layer & Memory Boundary (`ks_*` exports; builds to wasm32; host ABI test passes)
- `[x]` Core end-to-end review (independent + self): fixed handle-wrap, `u32` length-prefix guard, leaf unknown-key rejection; **PRD §22.2 input caps now enforced** (`RULE_LIMIT_EXCEEDED` / `CONDITION_LIMIT_EXCEEDED`, `rule-model.md` §9). 51 tests, clippy clean, wasm builds.
- `[x]` Phase 8 — Node SDK Wrapper Foundation (`sdk-node`; first real WASM execution; cross-language fixture parity passes)
- `[x]` Phase 9 — Node Polling, Logging & Analytics (polling/logger/analytics outside WASM; `refresh()` live; hardened in review)
- `[x]` Phase 10 — Node Framework Adapters (Express/Fastify + request translation; Node wrapper complete, 38 tests)
- `[x]` Phase 11 — Java SDK Wrapper Foundation (Chicory; cross-language fixture parity proven — Rust ≡ Node ≡ Java)
- `[x]` Phase 12 — Java Polling, Logging & Analytics (polling/logger/analytics outside WASM; `refresh()` live; 20 tests)
- `[x]` Phase 13 — Java Framework Adapters (`RequestContext` + JAX-RS `KLSwitchFilter`/`@KLSwitchBinding`; sdk-java 22 tests, examples-java 9; Java wrapper complete)
- `[x]` Phase 14 — Mock Rules API Provider (`backend/` fixtures + OpenAPI; both wrappers poll a real local HTTP server through their default fetcher — Node 42 tests, sdk-java 26 tests)
- `[x]` Phase 15 — E2E Validation & Hardening (independent six-axis audit, no blockers; Node scenario-fixture parity added so Rust ≡ Node ≡ Java on the full suite; `scripts/` validation+compliance+release gate filled and green; `docs/known-limitations.md` + release checklist/versioning added; root cruft removed. MVP-ready tag left to the maintainer)

## Done in `spec/` (paths under `spec/schemas/`, `spec/examples/`, `spec/compliance/v1/cases/`)

- `[x]` Core schemas including `load-rules-result.schema.json`
- `[x]` Ruleset, evaluation, load-rules, **engine status**, and **invalid-rule error** example payloads under `spec/examples/`
- `[x]` Eval compliance cases (golden path, selector miss, missing field, `enabled=false`, priority / multiple match, nested all/any/not, all v1 operators, no-rules, multiple disabled)
- `[x]` Scenario compliance envelope documented in `spec/compliance/v1/README.md` plus load / replace / status multi-step cases
- `[x]` Pre-Rust review follow-ups: **JSON Schema validation policy** (negative load scenarios) in `spec/compliance/v1/README.md`; **engine semantics notes** in `rust-core/IMPLEMENTATION-NOTES.md`

## Next concrete tasks

- `[x]` Add `spec/examples` for engine status and standalone invalid rule error
- `[~]` Automated validation: `scripts/validate-schemas.sh` checks JSON well-formedness + draft declaration and runs the Rust roundtrip (instance conformance); a standalone Draft-2020-12 validator (e.g. ajv) is not yet vendored
- `[ ]` Optionally add `spec/compliance/v1/cases/README.md` listing case `id`s

Domain models in `rust-core` align with frozen schemas and `spec/examples`; keep them aligned as schemas evolve. **All 15 phases are complete** — the Rust engine and both wrappers (Node 8–10, Java 11–13) are built, Phase 14 mocks the rules API, and Phase 15 hardened the system end-to-end.

---


# 25. Phase 16 — Remove the Python sync/async duplication

## Goal

One implementation of the Python wrapper's behaviour, not two. Today
`client.py` (718 lines) and `async_client.py` (723 lines) share **313 identical
non-trivial lines** across 25 and 26 methods; the only genuine differences are
`close`/`aclose`, `__enter__`/`__aenter__` and `_apply_rules`/`_apply_rules_sync`.
Every rule-handling, caching, status and config behaviour exists twice, so a fix
to one is a fix somebody has to remember to make to the other.

**Corrected during the work.** The "313 identical lines" above came from
intersecting sorted unique lines across whole files, which matches any line
appearing anywhere — including `with self._mu:` and bare `return`. Measured
properly, 18 method names are shared and **10 of those were true duplicates**;
the rest differ for real reasons (`await`, construction order, an async-specific
error message). The duplication was genuine but a third of what that number
suggested.

This is first because it is the highest risk of silent divergence and the most
mechanical to correct.

## Deliverables

- A shared core holding everything that is not I/O-bound
- Two thin clients that differ only where `async` genuinely forces them to
- No behavioural change — the compliance fixtures must replay byte-identically

## Tasks

- `[x]` Inventory the two files: list every method and mark it *shared*, *sync-only*,
  or *async-only*. The 313 shared lines are the starting set, not the answer
- `[x]` Extract the shared behaviour into a base (rule application, rules-by-id
  indexing, status assembly, config resolution, instance identity, disk cache)
- `[x]` Leave only genuinely-async members on `AsyncKLSwitch`: `aclose`,
  `__aenter__`, `__aexit__`, and the awaited fetch/post paths
- `[x]` Confirm the public surface of both clients is unchanged — this phase must
  not alter what callers see
- `[x]` Delete the duplicated bodies rather than leaving them as dead fallbacks

## Validation before phase complete

- `[x]` Both clients replay every `spec/compliance/v1/cases/*.json` fixture
- `[x]` `grep` finds no method body present in both files
- `[~]` Line count of the two clients drops by roughly the shared 313, not by less
- `[x]` No new public names appear in `__init__.py`

## Phase completion checklist

- `[x]` Fixtures green in all four wrappers (Python change must not move the others)
- `[x]` `docs/cross-language-parity.md` still accurate
- `[x]` No boundary violations — nothing moved into or out of WASM

---

# 26. Phase 17 — Decouple analytics and manifest from evaluation

## Goal

`evaluate()` should evaluate. Today it also re-checks the manifest, times itself,
computes missing fields and builds-and-records an analytics event
(`sdk-node/src/client.ts:309`, and the equivalents in the other three). Analytics
is woven into the hot path rather than observing it, and manifest reporting — a
startup concern — is hooked onto every evaluation via `recheckManifestOnce()`
(`client.ts:302`).

The same phase fixes the dependency direction: the clients hold concrete
collaborators (`client.ts:25`, `client.go:43,45`, `Client.java:34,36,37`), so
none can be substituted in a test. The codebase already knows how to do this —
`AnalyticsSink.java:7` and `ManifestSource` in `manifest.ts:80` are proper
abstractions that were simply not applied to the three main collaborators.

## Deliverables

- `evaluate()` returns a result and notifies an observer; it does not assemble
  telemetry
- Manifest re-check moved off the evaluation path
- Polling, analytics and manifest reached through interfaces in all four wrappers

## Tasks

- `[x]` Define an evaluation-observer abstraction per language, with a no-op default
- `[x]` Move event construction (event id, timestamps, matched mapping, latency
  bucket, missing fields) out of `evaluate()` and behind that observer
- `[x]` Move `recheckManifestOnce()` off the evaluation path — it is a
  `ManifestRecheckObserver` now. It could not move to init: routes register after
  the client exists, and with async frameworks that finishes after the deferred
  initial push has walked an empty table. The trigger stays; `evaluate()` no
  longer knows about it
- `[x]` Introduce interfaces for the polling, analytics and manifest collaborators
  in all four wrappers; keep the concrete classes as the default bindings.
  Deliberately **narrow**, and analytics is **split in two**: the observer records
  and never closes, the client closes and never records, so one interface carrying
  both would hand each a method it has no business calling. Python types only what
  is honestly shareable — `poll_once` and `close` are `def` in the sync stack and
  `async def` in the async one, so no single Protocol describes both, and two
  parallel hierarchies would cost more than they explain
- `[x]` Prove substitutability with a test that swaps a fake for each — Node has
  fakes for all three collaborators plus both analytics halves; Go asserts
  conformance at compile time (`var _ PollingControl = (*pollingManager)(nil)`);
  Java's `implements` clause is checked by javac

## Validation before phase complete

- `[x]` `evaluate()` body contains no analytics or manifest calls
- `[~]` Evaluation latency unchanged within noise — not measured; the work moved is
  the same work, now behind one virtual call
- `[x]` A test can run evaluation with analytics disabled and with a fake sink
- `[x]` Fixtures green in all four wrappers (`scripts/run-compliance-tests.sh`:
  rust-core + node + java + java-spring + go + python)

## Phase completion checklist

- `[~]` `docs/sdk-wrapper-contract.md` updated if the observer is public surface —
  not done; the observer is internal in every wrapper, but the interfaces are
  exported in Node and Go and arguably belong in the contract
- `[x]` No rule logic moved into a wrapper, no runtime concern into WASM
- `[x]` `docs/review-checklist.md` self-review done

---

# 27. Phase 18 — Config validation and cross-SDK surface parity

## Goal

Two smaller issues that are both about change-cost.

`validateConfig` (`sdk-node/src/config.ts:103,150`) carries migration logic for a
pre-A1 config shape — `rejectsLegacy()`, `pickLegacyKeys()`, `MaybeLegacy` listing
`headers` / `serviceCode` / `environment`. Every new config field means editing a
function that also handles a shape that no longer exists; validation and
deprecation-handling are two reasons for one file to change.

And the four wrappers have drifted apart — though less than first reported. The
original count here (node 8 / go 11 / java 12 / python 12, with `URLs` as Go-only)
was measured with a method-only regex that missed Node's getters and Python's
properties. Re-measured, all four share a 12-member core, and the real drift is
three members: `activeRulesVersion` (Java + Python), `registerGuardMount` (Java),
and `log` (Node). `CLAUDE.md` still names only `init` / `evaluate` / `refresh` /
`getStatus` / `close`, so the documented contract is nine members behind the code
either way.

## Deliverables

- Config validation separated from legacy-shape migration
- One agreed public surface, documented, with any extras deliberate and named

## Tasks

- `[x]` Split legacy-shape detection out of `validateConfig` into its own unit
  — now `sdk-node/src/config-legacy.ts`, with all branches pinned by
  `test/config-legacy.test.ts` so retiring it is a visible deletion
- `[ ]` **Decision owed:** whether the pre-A1 shape still needs rejecting at all.
  Evidence gathered: `serviceCode` appears nowhere in the repo outside the check
  itself and its tests, and the SDK is unpublished at `0.1.0` — so there are no
  known callers on the old shape. The check is now isolated and costs one call,
  so keeping it is cheap; dropping it is one file plus one line
- `[x]` Tabulate every public method in all four wrappers — done by reading each
  client directly rather than by regex; 12-member shared core, 3 divergent members
- `[ ]` **Decision owed:** bring the three divergent members into line. Each is
  a breaking change in at least one language, so the call is the maintainer's
- `[x]` Record the surface in `docs/sdk-wrapper-contract.md` §2 — core frozen in
  §2.1, the three divergences listed in §2.2 as drift rather than contract, so a
  fourth one is a review failure

## Validation before phase complete

- `[x]` Adding a config field touches validation only, not migration code
- `[x]` The wrapper-contract doc lists the surface; all four match §2.1 (§2.2
  divergences remain, pending the decision above)
- `[x]` Fixtures green in all four wrappers

## Phase completion checklist

- `[x]` `docs/cross-language-parity.md` reflects the surface — a per-language
  table pointing at `docs/sdk-wrapper-contract.md` §2 as the contract
- `[ ]` No boundary violations

---
