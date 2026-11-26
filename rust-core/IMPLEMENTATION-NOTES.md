# rust-core — v1 implementation notes (pre-code)

Use this when implementing Phase 3+ so behavior matches [spec/compliance/v1/cases/](../spec/compliance/v1/cases/) and [docs/rule-model.md](../docs/rule-model.md).

The engine semantics that JSON Schema cannot express are now **frozen in
[docs/rule-model.md](../docs/rule-model.md) §5.1, §7, §8**. This file only
records the Rust-level implementation reminders. Do not re-decide these here —
follow `rule-model.md`.

## Equal priority (tie-break) — frozen

- Matches are **priority-descending**, ties broken by **ascending rules-array
  declaration order** (`rule-model.md` §7.4).
- Implement with a **stable sort** keyed on `priority` (descending) over rules
  kept in declaration order. `eval-equal-priority.json` can now be authored
  against this rule.

## Field paths and missing parents — frozen

- A path is **missing** if it does not resolve, including when an intermediate
  parent object is absent (`body.user.id` with no `body.user`).
- Missing field ⇒ leaf `false` for **all** operators except `exists` /
  `not_exists` (`rule-model.md` §7.2). Apply this uniformly to the negative
  operators too (`neq`, `not_contains`, `not_in`).

## Operators and types — frozen

- **No implicit coercion.** Type mismatch ⇒ leaf `false` for comparison
  operators; `eq` is `false` / `neq` is `true` on type mismatch of a present
  field (`rule-model.md` §5.1, §7.3).
- Invalid operator, or `in` / `not_in` with a non-array `value`, is a
  **compile-time** error surfaced in the load result (rule skipped), not an
  evaluate-time error.

## `rules_version` (payload vs load result)

- [ruleset.schema.json](../spec/schemas/ruleset.schema.json) allows `rules_version` as **integer or string**.
- [load-rules-result.schema.json](../spec/schemas/load-rules-result.schema.json) exposes `rules_version` as **integer** in the load result.
- The engine should **normalize** (e.g. parse numeric strings to `i64`, or define a single stored representation) so `load_rules` output matches fixture expectations.

## `load_rules` `ok` flag vs partial compile

- Scenario fixtures encode expected `ok`, `compiled_rule_count`, `skipped_rule_count`, and `errors` for **all-invalid** vs **partial** loads. Implement `load_rules` so these match [spec/compliance/v1/cases/load-invalid-rule.json](../spec/compliance/v1/cases/load-invalid-rule.json), [load-partial-invalid-multiple-errors.json](../spec/compliance/v1/cases/load-partial-invalid-multiple-errors.json), and related replace/status scenarios.
