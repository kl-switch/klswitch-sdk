import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { AddressInfo } from "node:net";

import { KLSwitch } from "../src/client.js";
import type { EvaluationInput } from "../src/types.js";

// Phase 14: drive the REAL default fetch (globalThis.fetch) against a real local
// HTTP server serving the canonical backend/ fixtures — no injected fetchImpl.

const examplesDir = new URL("../../backend/examples/", import.meta.url);
const fixture = (name: string): string => readFileSync(fileURLToPath(new URL(name, examplesDir)), "utf8");

const V1 = fixture("mock-rules-response.json");
const V2 = fixture("mock-rules-response-v2.json");
const INVALID = fixture("mock-rules-invalid.json");

/** A mock rules endpoint whose response can be swapped between requests. */
interface MockState {
  status: number;
  body: string;
}

async function startMock(state: MockState): Promise<{ url: string; server: Server }> {
  const server = createServer((_req, res) => {
    res.writeHead(state.status, { "content-type": "application/json" });
    res.end(state.body);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;
  return { url: `http://127.0.0.1:${port}/v1/ruleset`, server };
}

function transferInput(amount: number, country?: string): EvaluationInput {
  const body: Record<string, unknown> = { amount };
  if (country !== undefined) body.country = country;
  return {
    selector: { kind: "api", keys: { method: "POST", path: "/v1/transfers" } },
    facts: { headers: {}, query: {}, params: {}, body, meta: {} },
  };
}

const blockedTransfer = (ks: KLSwitch, amount: number): number => ks.evaluate(transferInput(amount)).matched.length;
// A small-amount transfer from a restricted country: below both thresholds, so it
// isolates the v2-only block_restricted_country rule from the threshold change.
const restrictedIds = (ks: KLSwitch): string[] =>
  ks.evaluate(transferInput(100, "KP")).matched.map((m) => m.id);

test("init fetches the v1 ruleset over real HTTP and activates it", async () => {
  const state: MockState = { status: 200, body: V1 };
  const { url, server } = await startMock(state);
  try {
    const ks = await KLSwitch.init({ token: "ks_live_test", rulesUrl: url });
    const status = ks.getStatus();
    assert.equal(status.ready, true);
    assert.equal(status.active_rules_version, 1);
    assert.equal(status.lastRefreshOk, true);
    assert.equal(blockedTransfer(ks, 100_001), 1, "v1 blocks transfers over 100000");
    assert.equal(blockedTransfer(ks, 60_000), 0, "v1 allows 60000");
    ks.close();
  } finally {
    server.close();
  }
});

test("refresh picks up a new rules_version over real HTTP", async () => {
  const state: MockState = { status: 200, body: V1 };
  const { url, server } = await startMock(state);
  try {
    const ks = await KLSwitch.init({ token: "ks_live_test", rulesUrl: url });
    assert.equal(ks.getStatus().active_rules_version, 1);
    assert.equal(blockedTransfer(ks, 60_000), 0);
    assert.deepEqual(restrictedIds(ks), [], "v1 has no restricted-country rule");

    state.body = V2; // backend now serves the tightened ruleset
    await ks.refresh();
    assert.equal(ks.getStatus().active_rules_version, 2);
    assert.equal(ks.getStatus().lastRefreshOk, true);
    assert.equal(blockedTransfer(ks, 60_000), 1, "v2 lowered the threshold to 50000");
    assert.deepEqual(restrictedIds(ks), ["block_restricted_country"], "v2's new rule now fires");
    ks.close();
  } finally {
    server.close();
  }
});

test("an invalid (non-ruleset) payload is rejected and last-known-good is kept", async () => {
  const state: MockState = { status: 200, body: V1 };
  const { url, server } = await startMock(state);
  try {
    const ks = await KLSwitch.init({ token: "ks_live_test", rulesUrl: url });
    assert.equal(ks.getStatus().active_rules_version, 1);

    state.body = INVALID; // 200, but the body is not a ruleset
    await ks.refresh();
    const status = ks.getStatus();
    assert.equal(status.active_rules_version, 1, "kept last known good");
    assert.equal(status.lastRefreshOk, false);
    assert.equal(blockedTransfer(ks, 100_001), 1, "still evaluates against v1");
    ks.close();
  } finally {
    server.close();
  }
});

test("a 5xx response keeps last-known-good and marks the refresh failed", async () => {
  const state: MockState = { status: 200, body: V1 };
  const { url, server } = await startMock(state);
  try {
    const ks = await KLSwitch.init({ token: "ks_live_test", rulesUrl: url });
    assert.equal(ks.getStatus().active_rules_version, 1);

    state.status = 500;
    state.body = '{"error":"boom"}';
    await ks.refresh();
    const status = ks.getStatus();
    assert.equal(status.active_rules_version, 1);
    assert.equal(status.lastRefreshOk, false);
    ks.close();
  } finally {
    server.close();
  }
});
