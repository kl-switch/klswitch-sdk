import { test } from "node:test";
import assert from "node:assert/strict";

import { KLSwitch } from "../src/client.js";
import { noopLogger, type Logger } from "../src/logger.js";
import {
  ManifestReporter,
  type ManifestPayload,
  type ManifestRoute,
  type ManifestSource,
  canonicalisePath,
} from "../src/manifest.js";
import type { FetchLike } from "../src/polling-manager.js";
import type { RuleSetPayload } from "../src/types.js";

function sourceOf(routes: ManifestRoute[]): ManifestSource {
  return { framework: "fastify", collect: () => routes };
}

function recordingFetch(): {
  impl: FetchLike;
  calls: Array<{ url: string; method?: string; body?: ManifestPayload }>;
  setReplyStatus: (s: number) => void;
} {
  const calls: Array<{ url: string; method?: string; body?: ManifestPayload }> = [];
  let status = 204;
  const impl: FetchLike = async (url, init) => {
    calls.push({
      url,
      method: init?.method,
      body: init?.body ? JSON.parse(init.body) : undefined,
    });
    return { ok: status >= 200 && status < 300, status, json: async () => null };
  };
  return { impl, calls, setReplyStatus: (s) => { status = s; } };
}

const baseOpts = (overrides: Partial<ConstructorParameters<typeof ManifestReporter>[0]> = {}) => ({
  url:         "http://api/v1/manifest",
  headers:     { Authorization: "Bearer t", "X-Instance-Key": "pod-1" },
  instanceKey: "pod-1",
  source:      sourceOf([{ method: "POST", path: "/v1/payments" }]),
  logger:      noopLogger,
  ...overrides,
});

test("manifest: report() POSTs the discovered routes (sorted + normalised)", async () => {
  const { impl, calls } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({
    fetchImpl: impl,
    source: sourceOf([
      // Out-of-order, mixed-case methods — the reporter normalises.
      { method: "get",  path: "/v1/healthz" },
      { method: "POST", path: "/v1/payments" },
      { method: "get",  path: "/v1/orders/:id" },
    ]),
  }));

  const ok = await rep.report();
  assert.equal(ok, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0]!.url, "http://api/v1/manifest");
  assert.equal(calls[0]!.method, "POST");

  const body = calls[0]!.body!;
  assert.equal(body.instanceKey, "pod-1");
  assert.equal(body.framework,   "fastify");
  assert.equal(body.routes.length, 3);
  // Sorted alphabetically by `${METHOD} ${path}`.
  assert.deepEqual(body.routes.map((r) => `${r.method} ${r.path}`), [
    "GET /v1/healthz",
    "GET /v1/orders/:id",
    "POST /v1/payments",
  ]);
  assert.ok(body.hash.length === 64, "sha256 hex is 64 chars");
});

test("manifest: re-report with unchanged routes is a no-op (no extra POST)", async () => {
  const { impl, calls } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({ fetchImpl: impl }));

  assert.equal(await rep.report(), true);
  assert.equal(calls.length, 1);

  // Calling again with the same source → no additional POST.
  assert.equal(await rep.report(), true);
  assert.equal(calls.length, 1, "dedup by hash; no extra request");
});

test("manifest: re-report with changed routes pushes the new set", async () => {
  let routes: ManifestRoute[] = [{ method: "POST", path: "/v1/payments" }];
  const { impl, calls } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({
    fetchImpl: impl,
    source: { framework: "fastify", collect: () => routes },
  }));

  await rep.report();
  assert.equal(calls.length, 1);
  assert.equal(calls[0]!.body!.routes.length, 1);

  // Operator added a new route at runtime.
  routes = [
    { method: "POST", path: "/v1/payments" },
    { method: "POST", path: "/v1/refunds" },
  ];
  await rep.report();
  assert.equal(calls.length, 2);
  assert.equal(calls[1]!.body!.routes.length, 2);
});

test("manifest: non-2xx response counts as failure", async () => {
  const { impl, setReplyStatus } = recordingFetch();
  setReplyStatus(500);
  const warnings: string[] = [];
  const logger: Logger = { ...noopLogger, warn: (m) => warnings.push(m) };
  const rep = new ManifestReporter(baseOpts({ fetchImpl: impl, logger }));

  assert.equal(await rep.report(), false);
  const st = rep.status();
  assert.equal(st.lastPushOk, false);
  assert.ok(st.lastPushError?.includes("status 500"));
  assert.equal(st.pushesFailed, 1);
  assert.equal(st.pushesSucceeded, 0);
  assert.ok(warnings.find((w) => w.includes("rejected")));
});

test("manifest: thrown source counts as failure and never propagates", async () => {
  const { impl } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({
    fetchImpl: impl,
    source: { framework: "fastify", collect: () => { throw new Error("walk boom"); } },
  }));
  assert.equal(await rep.report(), false);
  const st = rep.status();
  assert.equal(st.pushesFailed, 1);
  assert.ok(st.lastPushError?.includes("walk boom"));
});

test("manifest: status() snapshots counters", async () => {
  const { impl } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({ fetchImpl: impl }));
  let st = rep.status();
  assert.equal(st.enabled,         true);
  assert.equal(st.lastReportedAt,  null);
  assert.equal(st.pushAttempts,    0);

  await rep.report();
  st = rep.status();
  assert.equal(st.pushAttempts,     1);
  assert.equal(st.pushesSucceeded,  1);
  assert.equal(st.lastPushOk,       true);
  assert.equal(st.routeCount,       1);
  assert.ok(st.lastReportedAt);
});

test("manifest: stop() makes report() a no-op", async () => {
  const { impl, calls } = recordingFetch();
  const rep = new ManifestReporter(baseOpts({ fetchImpl: impl }));
  rep.stop();
  assert.equal(await rep.report(), false);
  assert.equal(calls.length, 0);
});

// ─── Client integration ────────────────────────────────────────────────

test("client: manifest is pushed at init when token + source are configured", async () => {
  const seen: string[] = [];
  const fetchImpl: FetchLike = async (url, init) => {
    seen.push(`${init?.method ?? "GET"} ${url}`);
    if (url.endsWith("/v1/ruleset")) {
      return { ok: true, status: 200, json: async () => ruleset() };
    }
    return { ok: true, status: 204, json: async () => null };
  };

  const ks = await KLSwitch.init({
    token:    "ks_live_test",
    manifest: sourceOf([{ method: "POST", path: "/v1/payments" }]),
    fetchImpl,
  });
  // Initial manifest push is fire-and-forget — give it a beat.
  await new Promise((r) => setImmediate(r));
  await new Promise((r) => setImmediate(r));
  await ks.reportManifest(); // force a wait for any pending push to settle
  assert.ok(
    seen.some((s) => s === "POST https://api.klswitch.dev/v1/manifest"),
    `expected manifest POST; got: ${seen.join(",")}`,
  );
  const st = ks.manifestStatus();
  assert.equal(st.enabled, true);
  assert.equal(st.framework, "fastify");
  assert.equal(st.routeCount, 1);
  ks.close();
});

test("client: manifestStatus is 'disabled' when no source supplied", async () => {
  const ks = await KLSwitch.init({ rules: ruleset() });
  const st = ks.manifestStatus();
  assert.equal(st.enabled, false);
  assert.equal(st.framework, null);
  ks.close();
});

test("client: reportManifest: false opts out even when source supplied", async () => {
  const seen: string[] = [];
  const fetchImpl: FetchLike = async (url, init) => {
    seen.push(`${init?.method ?? "GET"} ${url}`);
    if (url.endsWith("/v1/ruleset")) {
      return { ok: true, status: 200, json: async () => ruleset() };
    }
    return { ok: true, status: 204, json: async () => null };
  };
  const ks = await KLSwitch.init({
    token:          "ks_live_test",
    manifest:       sourceOf([{ method: "POST", path: "/v1/payments" }]),
    reportManifest: false,
    fetchImpl,
  });
  await new Promise((r) => setImmediate(r));
  assert.equal(
    seen.filter((s) => s.includes("/v1/manifest")).length,
    0,
    "expected no manifest POST when opted out",
  );
  assert.equal(ks.manifestStatus().enabled, false);
  ks.close();
});

function ruleset(): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "r", name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/v1/payments" } },
        enabled: true, priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 0 }] },
        action: { type: "block" },
      },
    ],
  };
}

/**
 * Five frameworks published `/api/items/{id}` and eight published
 * `/api/items/:id`, so a rule written from the console's route picker matched
 * nothing — evaluation normalises to the colon form. The manifest must agree,
 * and agreeing in the reporter means a hand-written custom source cannot get it
 * wrong either.
 */
test("canonicalisePath: one dialect reaches the backend", () => {
  assert.equal(canonicalisePath("/api/items/{id}"), "/api/items/:id");
  assert.equal(
    canonicalisePath("/api/items/{id}/reviews/{reviewId}"),
    "/api/items/:id/reviews/:reviewId",
  );
  // A converter spec is a routing constraint, not part of the path.
  assert.equal(canonicalisePath("/api/items/{id:int}"), "/api/items/:id");
  assert.equal(canonicalisePath("/api/items/:id"), "/api/items/:id");
  assert.equal(canonicalisePath("/health"), "/health");
});
