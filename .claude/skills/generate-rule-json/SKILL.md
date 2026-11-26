---
name: generate-rule-json
description: Generates KLSwitch rule objects and ruleset snippets conforming to spec/schemas/ruleset.schema.json and docs/rule-model.md. Use when authoring rules, fixtures, examples, or when the user asks for rule JSON, RuleSetPayload entries, selectors, conditions, or actions.
---

# Generate Rule JSON

## Instructions

1. **Envelope**: Full payloads need `schema_version`, `rules_version`, and `rules` (see `spec/schemas/ruleset.schema.json`). Single-rule requests may return just the rule object if the user only asked for a rule.

2. **Rule object** — include required fields: `id`, `name`, `selector`, `enabled`, `condition`, `action`. Also include `priority` (integer) whenever ordering matters; matches are returned in **priority-descending** order (ties break by declaration order) per `docs/rule-model.md`.

3. **Selector (v1 default)**: Unless the user specifies another kind, use `selector.kind: "api"` with `selector.keys`: `{ "method": "<HTTP method>", "path": "<path>" }`. Path is matched by exact string on the normalized/templated route.

4. **Condition**: Use nested `all` / `any` / `not` arrays or a leaf with `field`, `operator`, `value`. V1 operators: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `contains`, `not_contains`, `in`, `not_in`, `exists`, `not_exists`. Missing fact fields evaluate to **false** (except `exists`/`not_exists`); no implicit type coercion.

5. **Action**: Object with `type` and `payload` (structure depends on action type).

6. **Optional**: `tags` (string array), `metadata` (object). `enabled: false` skips the rule.

7. **Reference**: Mirror shape of `spec/examples/ruleset-api-basic.json` and field definitions in `docs/rule-model.md`.

## Examples

Minimal API rule shape:

```json
{
  "id": "example_rule",
  "name": "Example",
  "selector": {
    "kind": "api",
    "keys": { "method": "GET", "path": "/v1/resource" }
  },
  "enabled": true,
  "priority": 10,
  "condition": { "all": [{ "field": "body.flag", "operator": "eq", "value": true }] },
  "action": { "type": "block", "payload": { "statusCode": 403, "code": "RULE_BLOCKED" } },
  "tags": ["demo"]
}
```
