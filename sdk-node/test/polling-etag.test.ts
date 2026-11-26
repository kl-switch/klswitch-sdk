/**
 * B1 — ETag + Cache-Control polling tests.
 *
 * Covers the v1.1 server-driven polling contract: HEAD-first when an
 * ETag is cached, GET only on flip, If-None-Match race safety with 304,
 * server-driven cadence via Cache-Control, and graceful degradation
 * when the server omits either header.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import { noopLogger } from "../src/logger.js";
import {
  MAX_POLL_MS,
  MIN_POLL_MS,
  PollingManager,
  type FetchLike,
} from "../src/polling-manager.js";
import type { RuleSetPayload } from "../src/types.js";

function ruleset(version: number): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: version,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 0 }] },
        action: { type: "block" },
      },
    ],
  };
}

interface Reply {
  status: number;
  etag?: string;          // optional ETag on the response
  cacheControl?: string;  // optional Cache-Control on the response
  body?: unknown;         // ignored for HEAD and 304
}

interface Call {
  method: string;
  headers?: Record<string, string>;
}

/**
 * Configurable fake fetcher. `replies` is a queue — each call shifts one
 * off; the last entry sticks for any further calls.
 */
function makeFetcher(replies: Reply[]): { fetchImpl: FetchLike; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = async (_url, init) => {
    calls.push({
      method:  init?.method ?? "GET",
      headers: init?.headers,
    });
    const reply = replies.length > 1 ? replies.shift()! : replies[0];
    const headerMap: Record<string, string> = {};
    if (reply.etag !== undefined)         headerMap["etag"] = reply.etag;
    if (reply.cacheControl !== undefined) headerMap["cache-control"] = reply.cacheControl;
    return {
      ok:     reply.status >= 200 && reply.status < 300,
      status: reply.status,
      json:   async () => reply.body,
      text:   async () => "",
      headers: {
        get: (name: string) => headerMap[name.toLowerCase()] ?? null,
      },
    };
  };
  return { fetchImpl, calls };
}

function makePollingManager(
  fetchImpl: FetchLike,
  onFetched: (p: RuleSetPayload) => void = () => {},
) {
  let lastError: unknown = null;
  const pm = new PollingManager(
    { rulesUrl: "http://api/v1/ruleset", headers: {}, fetchImpl },
    noopLogger,
    onFetched,
    (e) => { lastError = e; },
  );
  return { pm, error: () => lastError };
}

// ─── HEAD-first / ETag dedup ─────────────────────────────────────────────

test("polling-etag: first poll is GET; second poll is HEAD when ETag was cached", async () => {
  const v1 = ruleset(1);
  const { fetchImpl, calls } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=30", body: v1 }, // cold GET
    { status: 200, etag: '"v1"', cacheControl: "max-age=30" },           // HEAD probe
  ]);
  const fetched: RuleSetPayload[] = [];
  const { pm } = makePollingManager(fetchImpl, (p) => fetched.push(p));

  await pm.pollOnce();
  await pm.pollOnce();

  assert.deepEqual(calls.map((c) => c.method), ["GET", "HEAD"]);
  // ETag matched on the HEAD → no second GET, no second onFetched.
  assert.equal(fetched.length, 1);
  assert.deepEqual(fetched[0], v1);
});

test("polling-etag: HEAD with changed ETag triggers a conditional GET", async () => {
  const v1 = ruleset(1);
  const v2 = ruleset(2);
  const { fetchImpl, calls } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=30", body: v1 }, // cold GET
    { status: 200, etag: '"v2"', cacheControl: "max-age=30" },           // HEAD shows flip
    { status: 200, etag: '"v2"', cacheControl: "max-age=30", body: v2 }, // conditional GET
  ]);
  const fetched: RuleSetPayload[] = [];
  const { pm } = makePollingManager(fetchImpl, (p) => fetched.push(p));

  await pm.pollOnce();
  await pm.pollOnce();

  assert.deepEqual(calls.map((c) => c.method), ["GET", "HEAD", "GET"]);
  assert.equal(calls[2]!.headers?.["If-None-Match"], '"v1"',
    "the post-HEAD GET sends If-None-Match: <lastEtag> for race safety");
  assert.equal(fetched.length, 2);
  assert.deepEqual(fetched[1], v2);
  assert.equal(pm.status().lastEtag, '"v2"');
});

test("polling-etag: 304 on conditional GET is treated as no-op (no onFetched)", async () => {
  const v1 = ruleset(1);
  const { fetchImpl, calls } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=30", body: v1 }, // cold GET
    { status: 200, etag: '"v2"', cacheControl: "max-age=30" },           // HEAD claims new
    { status: 304, etag: '"v1"', cacheControl: "max-age=30" },           // race: server reverted
  ]);
  const fetched: RuleSetPayload[] = [];
  const { pm } = makePollingManager(fetchImpl, (p) => fetched.push(p));

  await pm.pollOnce();
  await pm.pollOnce();

  assert.deepEqual(calls.map((c) => c.method), ["GET", "HEAD", "GET"]);
  // 304 → no onFetched. Only the cold-start GET produced a payload.
  assert.equal(fetched.length, 1);
});

// ─── Cache-Control max-age tuning ────────────────────────────────────────

test("polling-etag: server-driven Cache-Control max-age sets currentIntervalMs", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=120", body: ruleset(1) },
  ]);
  const { pm } = makePollingManager(fetchImpl);

  await pm.pollOnce();
  assert.equal(pm.status().currentIntervalMs, 120_000);
});

test("polling-etag: changing max-age across responses retunes the interval", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=30", body: ruleset(1) },
    { status: 200, etag: '"v1"', cacheControl: "max-age=600" }, // HEAD only — match → no GET
  ]);
  const { pm } = makePollingManager(fetchImpl);

  await pm.pollOnce();
  assert.equal(pm.status().currentIntervalMs, 30_000);

  await pm.pollOnce();
  // HEAD didn't change the ETag but DID update the cadence.
  assert.equal(pm.status().currentIntervalMs, 600_000);
});

test("polling-etag: max-age=0 clamps to MIN_POLL_MS (self-DDoS guard)", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=0", body: ruleset(1) },
  ]);
  const { pm } = makePollingManager(fetchImpl);
  await pm.pollOnce();
  assert.equal(pm.status().currentIntervalMs, MIN_POLL_MS);
});

test("polling-etag: enormous max-age clamps to MAX_POLL_MS", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "max-age=999999", body: ruleset(1) },
  ]);
  const { pm } = makePollingManager(fetchImpl);
  await pm.pollOnce();
  assert.equal(pm.status().currentIntervalMs, MAX_POLL_MS);
});

test("polling-etag: max-age parsing tolerates extra Cache-Control directives", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', cacheControl: "public, max-age=45, must-revalidate", body: ruleset(1) },
  ]);
  const { pm } = makePollingManager(fetchImpl);
  await pm.pollOnce();
  assert.equal(pm.status().currentIntervalMs, 45_000);
});

// ─── Graceful degradation when server omits ETag / Cache-Control ─────────

test("polling-etag: no ETag in response → every pollOnce stays a plain GET", async () => {
  const v1 = ruleset(1);
  const { fetchImpl, calls } = makeFetcher([
    { status: 200, body: v1 },  // sticks for all calls
  ]);
  const fetched: RuleSetPayload[] = [];
  const { pm } = makePollingManager(fetchImpl, (p) => fetched.push(p));

  await pm.pollOnce();
  await pm.pollOnce();
  await pm.pollOnce();

  // No ETag was ever cached → every poll is a cold GET, every one delivers.
  assert.deepEqual(calls.map((c) => c.method), ["GET", "GET", "GET"]);
  assert.equal(fetched.length, 3);
});

test("polling-etag: no Cache-Control → currentIntervalMs falls back to bootstrap", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v1"', body: ruleset(1) }, // ETag but no Cache-Control
  ]);
  const { pm } = makePollingManager(fetchImpl);
  // Before any poll, status is zero-ish; explicitly check after start() bootstrap.
  pm.start(45_000);
  // Calling pm.pollOnce() now would race with the timer. The bootstrap value
  // is what matters here — no Cache-Control on the response leaves it intact.
  await pm.pollOnce();
  pm.stop();
  assert.equal(pm.status().currentIntervalMs, 45_000,
    "bootstrap interval survives when server omits Cache-Control");
});

// ─── Status surface ──────────────────────────────────────────────────────

test("polling-etag: status() reports lastEtag and nextPollAt", async () => {
  const { fetchImpl } = makeFetcher([
    { status: 200, etag: '"v9"', cacheControl: "max-age=30", body: ruleset(1) },
  ]);
  const { pm } = makePollingManager(fetchImpl);

  assert.equal(pm.status().lastEtag, null);
  assert.equal(pm.status().nextPollAt, null);

  await pm.pollOnce();
  assert.equal(pm.status().lastEtag, '"v9"');

  pm.start(30_000);
  const next = pm.status().nextPollAt;
  assert.ok(next !== null, "nextPollAt set after start()");
  assert.ok(new Date(next!).getTime() > Date.now() - 1000,
    "nextPollAt is in the (near) future, not the past");
  pm.stop();
});
