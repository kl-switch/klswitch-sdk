import { test } from "node:test";
import assert from "node:assert/strict";

import { KLSwitch } from "../src/client.js";
import type { FetchLike } from "../src/polling-manager.js";
import type { ManifestRoute, ManifestSource } from "../src/manifest.js";
import type { RuleSetPayload } from "../src/types.js";

/**
 * The initial manifest push is deferred by a macrotask, which covers routes
 * registered synchronously after `await init()` returns. It does NOT cover
 * asynchronous registration — NestJS's `await app.init()`, Fastify's
 * `await app.register(...)` — because those span ticks and finish after the
 * deferred push has already walked an empty route table.
 *
 * That gap used to be papered over by telling operators to call
 * `reportManifest()` by hand, which nobody would infer. A single re-check on
 * the first evaluation closes it, matching the Python and Go wrappers.
 */

const RULES: RuleSetPayload = {
  schema_version: "1.0",
  rules_version: 1,
  rules: [
    {
      id: "r", name: "R",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/orders/:id" } },
      enabled: true,
      condition: { field: "body.amount", operator: "exists" },
      action: { type: "flag" },
    },
  ],
};

/** Captures every manifest POST; serves an empty ruleset for the rules fetch. */
function recordingFetch() {
  const pushes: string[][] = [];
  const impl: FetchLike = async (url, init) => {
    if (url.includes("/manifest")) {
      const body = init?.body ? JSON.parse(init.body) : { routes: [] };
      pushes.push(body.routes.map((r: ManifestRoute) => `${r.method} ${r.path}`));
      return { ok: true, status: 204, json: async () => null };
    }
    return { ok: true, status: 200, json: async () => RULES };
  };
  return { impl, pushes };
}

const input = {
  selector: { kind: "api" as const, keys: { method: "POST", path: "/v1/orders/:id" } },
  facts: { headers: {}, query: {}, params: {}, body: { amount: 1 }, meta: {} },
};

test("manifest: routes registered asynchronously are picked up on first evaluate", async () => {
  const { impl, pushes } = recordingFetch();

  // A source whose routes appear only after an await — the shape NestJS's
  // `app.init()` and Fastify's `register()` produce.
  let routes: ManifestRoute[] = [];
  const source: ManifestSource = { framework: "test", collect: () => routes };

  const ks = await KLSwitch.init({
    token: "kls_test",
    baseUrl: "http://api",
    refreshInterval: 0,
    manifest: source,
    fetchImpl: impl,
  });

  // Let the deferred init push run against the still-empty table.
  await new Promise((r) => setTimeout(r, 20));
  const beforeRoutes = pushes.flat().length;

  routes = [
    { method: "POST", path: "/v1/orders/:id" },
    { method: "GET", path: "/v1/users/:id" },
  ];

  ks.evaluate(input);
  await new Promise((r) => setTimeout(r, 50));
  ks.close();

  assert.equal(beforeRoutes, 0, "the deferred push ran before routes existed");
  const carried = pushes.filter((p) => p.length > 0);
  assert.ok(carried.length > 0, "no push carried the late routes — the gap is still open");
  assert.deepEqual(carried[carried.length - 1]!.sort(), [
    "GET /v1/users/:id",
    "POST /v1/orders/:id",
  ]);
});

test("manifest: the re-check runs once, not per request", async () => {
  const { impl, pushes } = recordingFetch();
  const source: ManifestSource = {
    framework: "test",
    collect: () => [{ method: "GET", path: "/x" }],
  };

  const ks = await KLSwitch.init({
    token: "kls_test",
    baseUrl: "http://api",
    refreshInterval: 0,
    manifest: source,
    fetchImpl: impl,
  });
  await new Promise((r) => setTimeout(r, 20));

  for (let i = 0; i < 5; i++) ks.evaluate(input);
  await new Promise((r) => setTimeout(r, 50));
  ks.close();

  // Hash dedupe makes repeats cheap regardless; this pins the guard so we do
  // not schedule a discovery walk per request.
  assert.ok(pushes.length <= 2, `expected at most init + one re-check, got ${pushes.length}`);
});
