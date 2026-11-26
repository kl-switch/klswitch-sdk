---
name: generate-compliance-tests
description: Generates shared compliance fixtures and tests for KLSwitch engine behavior (selectors, conditions, operators, disabled rules, ordering, replacement). Use when adding engine tests, cross-language fixtures, or when the user mentions compliance, spec fixtures, or docs/testing-strategy.md.
---

# Generate Compliance Tests

## Instructions

1. **Source of truth**: Behavior in `docs/rule-model.md`, `docs/testing-strategy.md`, and `docs/review-checklist.md`. Reuse or extend JSON under `spec/compliance/v1/cases/` (canonical suite), `spec/examples/`, and schemas under `spec/schemas/` where possible.

2. **Coverage checklist** — each area should have explicit fixtures and assertions:
   - **Selector lookup**: Same method/path shortlists correct rules; non-matching selector excludes rules before condition evaluation.
   - **Nested conditions**: `all`, `any`, `not` combinations; deep trees.
   - **Operators**: At least one case per v1 operator (`eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `contains`, `not_contains`, `in`, `not_in`, `exists`, `not_exists`), including type-mismatch → false cases.
   - **Missing fields**: Fact path absent → condition evaluates **false** (not error), except `exists`/`not_exists`.
   - **enabled=false**: Rule skipped; does not appear in `matched`.
   - **Matched rule ordering**: Multiple matches returned in **priority-descending** order; ties break by declaration order (stable sort).
   - **Replacement**: Valid `load_rules` replaces active set; invalid payload leaves previous rules unchanged (atomicity); optional concurrent evaluate during refresh boundary per `docs/testing-strategy.md`.

3. **Fixture format**: Prefer small JSON files matching the `spec/compliance/v1/cases/` envelope (eval fixtures: ruleset + evaluation input + expected result; scenario fixtures: ordered `steps`). See `spec/compliance/v1/README.md`.

4. **Sharing**: Structure fixtures so language wrappers (Node and Java) consume the same files (JSON on disk + table-driven tests).

5. **WASM bridge** (when relevant): Separate tests for alloc/free, JSON roundtrip, repeated `evaluate`/`load_rules`, output buffer freeing (`docs/testing-strategy.md`).

## Quick reference

| Concern | Expected behavior |
|--------|-------------------|
| Selector | Shortlist first (exact method/path) |
| Missing fact field | false (except exists/not_exists) |
| Type mismatch | false (no coercion) |
| enabled false | skipped |
| Multiple matches | all returned, priority desc, declaration-order tie-break |
| Bad reload | prior rules kept |
