/**
 * B3 — Schema-drift Layer 3 (missing fields) tests.
 *
 * Covers the wrapper-side condition-tree walk: nested all/any/not, every
 * leaf operator, presence semantics (null vs zero vs empty string),
 * dedup across multiple matched rules, and the integration with the
 * analytics event stamped by `evaluate()`.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import { KLSwitch } from "../src/client.js";
import {
  computeMissingFields,
  hasField,
  rulesById,
} from "../src/schema-drift.js";
import type {
  AnalyticsEvent,
  AnalyticsOptions,
} from "../src/analytics.js";
import type {
  EvaluationInput,
  MatchedRule,
  Rule,
  RuleSetPayload,
} from "../src/types.js";

// ─── Fixtures ─────────────────────────────────────────────────────────────

function rule(id: string, condition: Rule["condition"]): Rule {
  return {
    id,
    name: id,
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    enabled: true,
    priority: 100,
    condition,
    action: { type: "block" },
  };
}

function input(facts: Partial<EvaluationInput["facts"]>): EvaluationInput {
  return {
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    facts: {
      headers: {},
      query:   {},
      params:  {},
      body:    {},
      meta:    {},
      ...facts,
    },
  };
}

function matched(...ids: string[]): MatchedRule[] {
  return ids.map((id) => ({
    id,
    name: id,
    action: { type: "block" },
  }));
}

// ─── hasField — path resolution ───────────────────────────────────────────

test("hasField: dotted path resolves through bucket + nested object", () => {
  const i = input({ body: { transaction: { amount: 100 } } });
  assert.equal(hasField(i, "body.transaction.amount"), true);
  assert.equal(hasField(i, "body.transaction.currency"), false);
  assert.equal(hasField(i, "body.absent"), false);
});

test("hasField: missing bucket → false", () => {
  // Drop the body bucket entirely (operator hit a Content-Type-less request).
  const i: EvaluationInput = {
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    // @ts-expect-error: deliberately omit body
    facts: { headers: {}, query: {}, params: {}, meta: {} },
  };
  assert.equal(hasField(i, "body.amount"), false);
});

test("hasField: empty string and zero count as PRESENT", () => {
  // The operator deliberately sent these; only undefined/null are "absent".
  const i = input({ body: { coupon: "", amount: 0 } });
  assert.equal(hasField(i, "body.coupon"), true);
  assert.equal(hasField(i, "body.amount"), true);
});

test("hasField: null counts as absent (matches engine missing-field semantics)", () => {
  const i = input({ body: { coupon: null as unknown as string } });
  assert.equal(hasField(i, "body.coupon"), false);
});

test("hasField: dotted header key with embedded dash", () => {
  const i = input({ headers: { "x-api-key": "abc" } });
  assert.equal(hasField(i, "headers.x-api-key"), true);
  assert.equal(hasField(i, "headers.x-missing"), false);
});

test("hasField: rejects empty path", () => {
  assert.equal(hasField(input({}), ""), false);
});

// ─── computeMissingFields — condition walking ────────────────────────────

test("computeMissingFields: no matched rules → empty list", () => {
  const rules = [rule("r1", { all: [{ field: "body.amount", operator: "gt", value: 0 }] })];
  const out = computeMissingFields([], rulesById(rules), input({}));
  assert.deepEqual(out, []);
});

test("computeMissingFields: matched rule referencing missing field → reported", () => {
  const r = rule("block_large", {
    all: [{ field: "body.amount", operator: "gt", value: 100 }],
  });
  const out = computeMissingFields(
    matched("block_large"),
    rulesById([r]),
    input({ body: {} }), // no `amount`
  );
  assert.deepEqual(out, ["body.amount"]);
});

test("computeMissingFields: matched rule with field present → empty", () => {
  const r = rule("block_large", {
    all: [{ field: "body.amount", operator: "gt", value: 100 }],
  });
  const out = computeMissingFields(
    matched("block_large"),
    rulesById([r]),
    input({ body: { amount: 500 } }),
  );
  assert.deepEqual(out, []);
});

test("computeMissingFields: walks nested all/any/not", () => {
  const r = rule("nested", {
    any: [
      { all: [
        { field: "body.amount", operator: "gt", value: 0 },
        { field: "body.country", operator: "in", value: ["US"] },
      ]},
      { not: { field: "headers.x-api-key", operator: "exists" } },
    ],
  });
  const out = computeMissingFields(
    matched("nested"),
    rulesById([r]),
    input({ body: { amount: 5 } }),  // country + x-api-key absent
  );
  assert.deepEqual(out, ["body.country", "headers.x-api-key"]);
});

test("computeMissingFields: dedup across matched rules", () => {
  const r1 = rule("r1", { all: [{ field: "body.amount", operator: "gt", value: 0 }] });
  const r2 = rule("r2", { all: [{ field: "body.amount", operator: "lt", value: 10 }] });
  const out = computeMissingFields(
    matched("r1", "r2"),
    rulesById([r1, r2]),
    input({ body: {} }),
  );
  assert.deepEqual(out, ["body.amount"]);
});

test("computeMissingFields: sorted alphabetically (stable wire format)", () => {
  const r = rule("r", {
    all: [
      { field: "headers.x-zeta", operator: "exists" },
      { field: "body.alpha",     operator: "exists" },
      { field: "query.middle",   operator: "exists" },
    ],
  });
  const out = computeMissingFields(matched("r"), rulesById([r]), input({}));
  assert.deepEqual(out, ["body.alpha", "headers.x-zeta", "query.middle"]);
});

test("computeMissingFields: every leaf operator is treated the same", () => {
  // The presence/absence check runs for *every* leaf operator equally —
  // `exists`/`not_exists` are about checking presence themselves but for
  // schema-drift purposes any operator that references a field counts.
  const r = rule("ops", {
    any: [
      { field: "body.a", operator: "eq",         value: 1 },
      { field: "body.b", operator: "neq",        value: 1 },
      { field: "body.c", operator: "gt",         value: 0 },
      { field: "body.d", operator: "lt",         value: 0 },
      { field: "body.e", operator: "gte",        value: 0 },
      { field: "body.f", operator: "lte",        value: 0 },
      { field: "body.g", operator: "in",         value: [1] },
      { field: "body.h", operator: "not_in",     value: [1] },
      { field: "body.i", operator: "exists" },
      { field: "body.j", operator: "not_exists" },
    ],
  });
  const out = computeMissingFields(matched("ops"), rulesById([r]), input({ body: {} }));
  assert.deepEqual(out, [
    "body.a", "body.b", "body.c", "body.d", "body.e",
    "body.f", "body.g", "body.h", "body.i", "body.j",
  ]);
});

test("computeMissingFields: matched rule not in rulesById is ignored (no throw)", () => {
  // A matched rule with no entry in the cache shouldn't crash the walk —
  // it's a defensive case for races between rule-swap and evaluate.
  const out = computeMissingFields(
    matched("ghost"),
    new Map(),  // empty cache
    input({}),
  );
  assert.deepEqual(out, []);
});

// ─── Client integration: missingFields stamped on the analytics event ────

test("client.evaluate: missingFields ride on the analytics event", async () => {
  const events: AnalyticsEvent[] = [];
  const sink: AnalyticsOptions["sink"] = (batch) => { events.push(...batch); };

  // Use `any` so the rule fires via body.amount even when body.country is
  // absent — that's the exact schema-drift case we want to surface:
  // matched-but-references-a-missing-field.
  const ks = await KLSwitch.init({
    rules: {
      schema_version: "1.0",
      rules_version: 1,
      rules: [{
        id: "audit_either",
        name: "audit",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 50,
        condition: {
          any: [
            { field: "body.amount",  operator: "gt", value: 0 },
            { field: "body.country", operator: "in", value: ["KP"] },
          ],
        },
        action: { type: "flag" },
      }],
    },
    analytics: { sink, batchSize: 1, flushIntervalMs: 50 },
  });

  ks.evaluate({
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    facts: {
      headers: {}, query: {}, params: {},
      body: { amount: 5, country: "US" }, // both fields present
      meta: {},
    },
  });
  ks.evaluate({
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    facts: {
      headers: {}, query: {}, params: {},
      body: { amount: 5 }, // amount matches via `any`; country MISSING
      meta: {},
    },
  });
  await new Promise((r) => setTimeout(r, 80));
  await ks.close();

  assert.equal(events.length, 2);
  assert.deepEqual(events[0]!.missingFields, []);
  assert.deepEqual(events[1]!.missingFields, ["body.country"]);
});
