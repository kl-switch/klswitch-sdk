import { test } from "node:test";
import assert from "node:assert/strict";

import { WasmBridge } from "../src/wasm-bridge.js";

const RULESET = JSON.stringify({
  schema_version: "1.0",
  rules_version: 12,
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
});

const INPUT = JSON.stringify({
  selector: { kind: "api", keys: { method: "POST", path: "/p" } },
  facts: { headers: {}, query: {}, params: {}, body: { amount: 500 }, meta: {} },
});

test("bridge: full lifecycle (create, load, status, evaluate, destroy)", async () => {
  const bridge = await WasmBridge.load();
  const handle = bridge.createEngine();
  assert.ok(handle >= 1, "handle is non-zero");

  const load = bridge.loadRules(handle, RULESET);
  assert.equal(load.ok, true);
  assert.equal(load.compiled_rule_count, 1);
  assert.equal(load.rules_version, 12);

  const status = bridge.getStatus(handle);
  assert.equal(status.initialized, true);
  assert.equal(status.active_rules_version, 12);
  assert.equal(status.rule_count, 1);

  const result = bridge.evaluate(handle, INPUT);
  assert.equal(result.ok, true);
  assert.equal(result.matched.length, 1);
  assert.equal(result.matched[0]!.id, "block");

  bridge.destroyEngine(handle);
  assert.equal(bridge.getStatus(handle).initialized, false);
});

test("bridge: invalid handle and malformed input return structured errors", async () => {
  const bridge = await WasmBridge.load();

  const badHandle = bridge.loadRules(999, RULESET);
  assert.equal(badHandle.ok, false);
  assert.equal(badHandle.errors[0]!.code, "INVALID_HANDLE");

  const handle = bridge.createEngine();
  const badInput = bridge.evaluate(handle, "{ not json");
  assert.equal(badInput.ok, false);
  assert.equal(badInput.errors[0]!.code, "INVALID_INPUT");
  bridge.destroyEngine(handle);
});

test("bridge: repeated calls are stable across the boundary", async () => {
  const bridge = await WasmBridge.load();
  const handle = bridge.createEngine();
  for (let i = 0; i < 500; i++) {
    assert.equal(bridge.loadRules(handle, RULESET).ok, true);
    assert.equal(bridge.evaluate(handle, INPUT).matched.length, 1);
  }
  bridge.destroyEngine(handle);
});
