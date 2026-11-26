---
name: generate-schema-example
description: Generates realistic JSON examples for KLSwitch rulesets, evaluation inputs, and evaluation results aligned with spec/schemas and spec/examples. Use when the user needs sample payloads, API examples, or test data for RuleSetPayload, EvaluationInput, or EvaluationResult.
---

# Generate Schema Example

## Instructions

1. **Align to schemas**:
   - **Ruleset**: `spec/schemas/ruleset.schema.json` — include `schema_version`, `rules_version`, `rules[]` with required rule fields.
   - **Evaluation input**: `spec/schemas/evaluation-input.schema.json` — `selector` + `facts`.
   - **Evaluation result**: `spec/schemas/evaluation-result.schema.json` — `ok`, `matched`, `evaluated`, `skipped`, `errors`.

2. **Realism**: Use coherent domain stories (payments, auth, rate limits, feature flags). Use v1 API selectors by default: `kind: "api"`, `keys.method`, `keys.path`.

3. **Facts shape**: Mirror how wrappers map HTTP into facts (e.g. `headers`, `query`, `params`, `body`, `meta`) as in `spec/examples/evaluation-input-payment.json`.

4. **Matched entries**: Typically include `id`, `name`, `priority`, `action` for matched rules (see `spec/examples/evaluation-result-block.json`).

5. **Errors**: When illustrating failures, use objects compatible with `spec/schemas/error-info.schema.json` (`code`, `message`, optional `rule_id`, `field`) inside `errors` arrays or status `last_error` contexts.

6. **Engine status** (if requested): `spec/schemas/engine-status.schema.json`.

7. **Do not contradict** `docs/rule-model.md` (ordering, tie-break, missing fields, type handling, enabled skip).

## Reference files

Copy style and field naming from:

- `spec/examples/ruleset-api-basic.json`
- `spec/examples/evaluation-input-payment.json`
- `spec/examples/evaluation-result-block.json`
