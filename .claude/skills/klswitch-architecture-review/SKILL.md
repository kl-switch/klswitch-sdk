---
name: klswitch-architecture-review
description: Reviews KLSwitch files, designs, or modules for WASM vs wrapper boundaries, folder discipline, JSON and atomic replacement rules, and frozen rule model. Use when reviewing PRs, refactors, or when the user asks if something violates architecture or CLAUDE.md.
---

# KLSwitch Architecture Review

## Instructions

When reviewing code, docs, or designs, check each category below. Cite specific files or symbols when flagging issues.

### 1. WASM vs wrapper boundary

- **WASM must contain only**: Rule validation, compilation, selector indexing, in-memory compiled rules, **atomic** rule replacement, evaluation, engine status.
- **WASM must not contain**: HTTP/client calls, polling/timers, logging, analytics sending, framework-specific APIs.
- **Wrappers must own**: WASM loading, lifecycle, remote rules fetch/poll, logging, analytics, public SDK API, framework adapters.

Reference: `docs/wasm-host-contract.md`, `docs/sdk-wrapper-contract.md`, `CLAUDE.md`.

### 2. Folder placement

- `rust-core/`: shared engine logic only.
- `sdk-node/`, `sdk-java/`: wrapper logic only for that language (v1 scope: Node + Java).
- `spec/`: schemas, examples, compliance fixtures only.
- `docs/`: architecture and contracts only.
- `backend/`: rules API materials, not engine logic.

Reference: `CLAUDE.md`, `docs/architecture.md`.

### 3. JSON boundary (v1)

- Wrapper ↔ WASM: **JSON** for rulesets, evaluation input/output, load results, status, structured errors.
- Explicit memory ownership; no custom binary host protocol in v1.

Reference: `docs/wasm-host-contract.md` §5–6.

### 4. Atomic replacement

- New rules must be fully parsed, validated, compiled, and indexed **before** becoming active.
- On failure: structured error; **last known good** ruleset stays active; no partial visible state.

Reference: `docs/wasm-host-contract.md` §2, `docs/review-checklist.md`.

### 5. Frozen rule model (v1)

- Model: selector + condition + action; v1 selector kind **`api`** with keys `method`, `path` (exact match).
- Selector shortlists first; conditions run on shortlisted rules only.
- Nested conditions: `all` / `any` / `not` / leaf.
- Missing fact fields → false (except `exists`/`not_exists`); no implicit type coercion; `enabled: false` → skip rule.
- Return **all** matched rules, **priority-descending**; ties break by declaration order.

Reference: `docs/rule-model.md`, `docs/review-checklist.md`.

## Output format

Summarize as:

1. **Compliant**: brief confirmation, or
2. **Violations**: bullet list with severity (blocker vs suggestion), file/path, which rule above is breached, and a concrete fix direction.

If docs and code disagree, flag it and recommend following **`docs/`** and **`spec/`** per project rules.
