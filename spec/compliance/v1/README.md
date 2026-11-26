# KLSwitch compliance fixtures (v1)

## Eval fixtures (`cases/*.json` — single-shot)

These files exercise **`evaluate`** only. Each file is a self-contained **RuleSetPayload** plus one evaluation.

**Required top-level fields:**

| Field | Type | Description |
| ----- | ---- | ----------- |
| `id` | string | Stable case id (usually matches filename without `.json`). |
| `description` | string | Human-readable intent for reviewers and test output. |
| `ruleset` | object | Full ruleset passed to `load_rules` in the test harness (same shape as [ruleset.schema.json](../schemas/ruleset.schema.json)). |
| `evaluation_input` | object | Input to `evaluate` ([evaluation-input.schema.json](../schemas/evaluation-input.schema.json)). |
| `expected_evaluation_result` | object | Expected `evaluate` output ([evaluation-result.schema.json](../schemas/evaluation-result.schema.json)). |

Test harness: load `ruleset` into a fresh engine, then call `evaluate` once with `evaluation_input`, then assert JSON equality (or subset) against `expected_evaluation_result`.

---

## Scenario fixtures (`cases/*` — multi-step)

These files exercise **`load_rules`**, **`get_status`**, and/or **`evaluate`** in sequence. They use a **scenario** envelope so atomic replacement and status semantics can be tested.

**Required top-level fields:**

| Field | Type | Description |
| ----- | ---- | ----------- |
| `id` | string | Stable case id. |
| `description` | string | Human-readable intent; state whether the case is partial load, catastrophic failure, etc. |
| `fixture_kind` | string | Must be `"scenario"`. |
| `steps` | array | Ordered steps executed by the harness (see below). |

**Harness preconditions:**

- The harness **creates** one engine instance before executing `steps`.
- Before step 1, no rules are loaded unless the scenario assumes otherwise (document in `description`).
- Each step runs **after** the previous step completes; the engine retains state (active ruleset, last load result, status) across steps.

### Step object

Each element of `steps` is an object with:

| Field | Type | Required when | Description |
| ----- | ---- | ------------- | ----------- |
| `op` | string | always | Operation: `"load_rules"`, `"evaluate"`, or `"get_status"`. |
| `ruleset` | object | `op === "load_rules"` | Full **RuleSetPayload** passed to `load_rules`. |
| `expected_load_rules_result` | object | `op === "load_rules"` | Expected JSON from `load_rules` ([load-rules-result.schema.json](../schemas/load-rules-result.schema.json)). |
| `evaluation_input` | object | `op === "evaluate"` | Input to `evaluate`. |
| `expected_evaluation_result` | object | `op === "evaluate"` | Expected output from `evaluate`. |
| `expected_engine_status` | object | `op === "get_status"` | Expected output from `get_status` ([engine-status.schema.json](../schemas/engine-status.schema.json)). |

Steps **omit** fields that are not used by `op`. The harness must assert the expected object for each step matches actual engine output (after the op).

**Optional later:** a JSON Schema for this envelope (e.g. `scenario.schema.json`) may be added for CI validation; v1 is documented here only.

---

## JSON Schema validation policy (CI and tooling)

- **Eval fixtures** (`eval-*.json`): the embedded `ruleset` is intended to be valid against [ruleset.schema.json](../schemas/ruleset.schema.json); validating it in CI is appropriate.
- **Scenario fixtures** with **negative load** cases (e.g. `load-invalid-rule.json`, `load-partial-invalid-multiple-errors.json`, `replace-failure-keeps-old.json`): the `ruleset` inside `load_rules` steps may use **invalid operators** or other values that **fail** [ruleset.schema.json](../schemas/ruleset.schema.json) on purpose—the engine must reject them at compile time. **Do not** require full ruleset-schema validation on those embedded payloads, or exclude those files / steps from strict ruleset validation.

---

## Related specs

- Rule model: [docs/rule-model.md](../../../docs/rule-model.md) (repo root).
- WASM contract: [docs/wasm-host-contract.md](../../../docs/wasm-host-contract.md).
