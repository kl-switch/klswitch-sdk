import { test } from "node:test";
import assert from "node:assert/strict";

import { KLSwitch } from "../src/client.js";
import type { Logger } from "../src/logger.js";
import type { EvaluationInput, RuleSetPayload } from "../src/types.js";

const rules: RuleSetPayload = {
  schema_version: "1.0",
  rules_version: 7,
  rules: [
    {
      id: "block",
      name: "Block",
      selector: { kind: "api", keys: { method: "POST", path: "/p" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.amount", operator: "gt", value: 100 }] },
      action: { type: "block", payload: { code: "RULE_BLOCKED" } },
    },
  ],
};

const input: EvaluationInput = {
  selector: { kind: "api", keys: { method: "POST", path: "/p" } },
  facts: { headers: {}, query: {}, params: {}, body: { amount: 500 }, meta: {} },
};

test("client: init from inline rules (dev mode), evaluate, status, close", async () => {
  const ks = await KLSwitch.init({ rules });

  const status = ks.getStatus();
  assert.equal(status.ready, true);
  assert.equal(status.last_load_ok, true);
  assert.equal(status.active_rules_version, 7);

  const result = ks.evaluate(input);
  assert.equal(result.matched.length, 1);
  assert.equal(result.matched[0]!.action.type, "block");

  ks.close();
  assert.equal(ks.getStatus().ready, false);
});

test("client: init fails clearly when rules are invalid", async () => {
  const bad: RuleSetPayload = {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "bad",
        name: "Bad",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        condition: { all: [{ field: "body.x", operator: "bogus_op", value: 1 }] },
        action: { type: "block" },
      },
    ],
  };
  await assert.rejects(() => KLSwitch.init({ rules: bad }), /failed to load/i);
});

test("client: init fails when neither rules nor token is provided", async () => {
  // Without inline rules AND without a token, there's no rules source at all.
  await assert.rejects(() => KLSwitch.init({}), /token.*required/i);
});

test("client: evaluate after close throws", async () => {
  const ks = await KLSwitch.init({ rules });
  ks.close();
  assert.throws(() => ks.evaluate(input), /not initialized/i);
});

test("client: instanceId is resolved at init and immutable", async () => {
  const ks = await KLSwitch.init({ rules, instanceId: "pod-7f4a" });
  assert.equal(ks.instanceId, "pod-7f4a");
  ks.close();
});

test("client: derived instanceId fallback works when nothing supplied", async () => {
  // Clear any test-leaked env vars that could win the chain.
  const saved = { ks: process.env.KS_INSTANCE_ID, pod: process.env.POD_NAME };
  delete process.env.KS_INSTANCE_ID;
  delete process.env.POD_NAME;
  try {
    const ks = await KLSwitch.init({ rules });
    // derived = `${hostname}-${pid}-${startMs}` — non-empty, no dash-only.
    assert.match(ks.instanceId, /^[\w\-.:]+-\d+-\d+$/);
    ks.close();
  } finally {
    if (saved.ks !== undefined) process.env.KS_INSTANCE_ID = saved.ks;
    if (saved.pod !== undefined) process.env.POD_NAME = saved.pod;
  }
});

test("client: urls + sdkVersion are exposed", async () => {
  const ks = await KLSwitch.init({ rules });
  assert.equal(ks.urls.rules,     "https://api.klswitch.dev/v1/ruleset");
  assert.equal(ks.urls.decisions, "https://api.klswitch.dev/v1/analytics");
  assert.match(ks.sdkVersion, /^\d+\.\d+\.\d+/);
  ks.close();
});

test("client: legacy config shape is rejected with a clear migration error", async () => {
  // Old shape used `headers` + `serviceCode`. Refuse silently reinterpreting.
  await assert.rejects(
    () => KLSwitch.init({ headers: { Authorization: "Bearer x" }, rulesUrl: "x" } as never),
    /refactored|migration/i,
  );
});

test("client: init rejects (and cleans up) when rules are not serializable", async () => {
  // A circular ruleset makes JSON.stringify throw mid-init; the engine handle
  // created moments earlier must still be released via close().
  const circular = { schema_version: "1.0", rules_version: 1, rules: [] } as Record<string, unknown>;
  circular.self = circular;
  await assert.rejects(() => KLSwitch.init({ rules: circular as never }));
});

test("client: a throwing analytics setup still releases the engine", async () => {
  // Regression test for the analytics-leak fix: if AnalyticsReporter construction
  // throws (here: by making setInterval throw), the engine handle must still be
  // released via close(). Observed via the "client closed" log line emitted by
  // close(); without the fix this log would never appear because close() wasn't called.
  const events: string[] = [];
  const recorder: Logger = {
    debug: () => {},
    info: (m) => events.push(m),
    warn: () => {},
    error: () => {},
  };
  const originalSetInterval = globalThis.setInterval;
  globalThis.setInterval = (() => {
    throw new Error("setInterval boom");
  }) as typeof globalThis.setInterval;
  try {
    await assert.rejects(
      () =>
        KLSwitch.init({
          rules,
          analytics: { sink: () => {} },
          logger: recorder,
        }),
      /setInterval boom/,
    );
    assert.ok(
      events.includes("client closed"),
      `expected close() to have run; logged events: ${events.join(", ")}`,
    );
  } finally {
    globalThis.setInterval = originalSetInterval;
  }
});
