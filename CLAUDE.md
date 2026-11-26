# KLSwitch — agent working rules

KLSwitch is a **WASM-first, multi-language rule engine SDK**. The engine is
written once in Rust, compiled to WebAssembly, and embedded inside per-language
SDK wrappers. v1 ships **Node.js/TypeScript, Java/Kotlin, Go, and Python**
wrappers — all four replay the same `spec/compliance/v1/cases/*.json` fixtures
byte-identically against the shared WASM engine.

`docs/` and `spec/` are the source of truth. If code and docs disagree, follow
`docs/`/`spec/` and flag the drift. If a design decision is missing, ask before
changing architecture.

## Architecture boundary (frozen)

Two strict layers. The boundary is the central rule of the project.

**WASM core (`rust-core/`) owns — and must contain only:**
- rule parsing, validation, and compilation
- selector indexing
- the in-memory active compiled ruleset (stateful engine)
- atomic rule replacement
- evaluation
- engine status

**WASM must NOT contain:** HTTP/network, polling/timers, logging, analytics
sending, filesystem or env access, or framework-specific code.

**SDK wrappers (`sdk-node/`, `sdk-java/`) own:**
- WASM loading and engine lifecycle
- polling a remote rules API
- logging
- analytics batching/sending
- public SDK API (`init` / `evaluate` / `refresh` / `getStatus` / `close`)
- framework adapters
- request → evaluation-input translation

Never move rule logic into wrappers; never move runtime/integration concerns
into WASM.

## WASM interface rules (v1)

- JSON input / JSON output only — no custom binary boundary protocol.
- Explicit memory ownership: wrapper allocates and writes input bytes; WASM
  returns pointer + length; wrapper copies output out immediately and frees the
  returned buffer with the agreed free function; wrapper holds no raw WASM
  pointers after the bridge call.
- One stateful engine instance per SDK instance, reused across evaluations.

See `docs/wasm-host-contract.md`.

## Rule engine behavior (frozen)

- Rule model: **selector + condition + action**.
- v1 selector kind is **`api`** with keys `method` and `path`. `method` matches
  by **exact string**; `path` matches by exact string unless it contains a `*`
  segment — `*` is exactly one segment, `**` is zero or more trailing segments
  and only valid last (schema 1.2, `docs/rule-model.md` §8.1). Both are matched
  against the normalized/templated route.
- A pattern rule and an exact rule can both match one request; **both** are
  returned and ordered by priority. Specificity never outranks priority (§8.2).
- Selector shortlists candidates first; conditions run on shortlisted rules only.
- Nested condition trees: `all` / `any` / `not` / leaf.
- `enabled: false` skips the rule before selector/condition evaluation.
- Return **all** matched rules in **priority-descending** order; ties break by
  declaration order (stable sort).
- **Missing field → leaf `false`** for every operator except `exists` /
  `not_exists`.
- **No implicit type coercion**; a present-but-wrong-type field → `false` for
  comparison operators.
- Rule replacement is **atomic**: new rules are fully parsed/validated/compiled/
  indexed before becoming active; on failure the last known good ruleset stays
  active and no partial state is visible.

Full semantics are frozen in `docs/rule-model.md` (§5.1 operators, §7
evaluation, §8 selector matching). The canonical behavior fixtures live in
`spec/compliance/v1/cases/`.

## Folder discipline

- `rust-core/` — shared engine logic only.
- `sdk-node/`, `sdk-java/`, `sdk-go/`, `sdk-python/` — wrapper logic only for
  that language (all four are v1).
- `examples/`, `examples-java/`, `examples-go/`, `examples-python/` — runnable
  demo servers for each wrapper.
- `spec/` — schemas, examples, and compliance fixtures only.
- `docs/` — architecture, contracts, and engineering guidance only.
- `backend/` — rules API materials (v1 is mocked), not engine logic.

## How to work on a task

1. Read the relevant `docs/` and `spec/` files first (start order below).
2. Identify the correct folder and whether the change belongs in WASM or the
   wrapper.
3. Make the change minimal and single-purpose; keep modules small and readable.
4. Add or update tests / fixtures when behavior changes.
5. Self-review against `docs/review-checklist.md`.
6. Keep `docs/`, `spec/`, and code aligned; update `docs/implementation-plan.md`
   task markers as work progresses.

**First-read order:** `CLAUDE.md` → `docs/architecture.md` →
`docs/wasm-host-contract.md` → `docs/sdk-wrapper-contract.md` →
`docs/rule-model.md`.

## Agent tooling in this repo

- Subagents: `.claude/agents/` (rust-wasm-core-engineer, sdk-wrapper-engineer,
  spec-and-contract-reviewer, test-strategist).
- Skills: `.claude/skills/` (rule/example/fixture/export/wrapper generators and
  the architecture-review skill).
- Slash commands: `.claude/commands/` (quick task triggers).
