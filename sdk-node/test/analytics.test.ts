import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { gunzipSync } from "node:zlib";

import {
  AnalyticsReporter,
  latencyBucket,
  type AnalyticsEvent,
  type ReporterTransport,
} from "../src/analytics.js";
import { KLSwitch } from "../src/client.js";
import { noopLogger, type Logger } from "../src/logger.js";
import type { FetchLike } from "../src/polling-manager.js";
import type { EvaluationInput, RuleSetPayload } from "../src/types.js";

const tick = () => new Promise((resolve) => setImmediate(resolve));

function event(overrides: Partial<AnalyticsEvent> = {}): AnalyticsEvent {
  return {
    type:          "evaluation",
    eventId:       randomUUID(),
    occurredAt:    new Date().toISOString(),
    rulesVersion:  1,
    request:       { method: "POST", path: "/p" },
    matched:       [],
    evaluated:     0,
    skipped:       0,
    latencyBucket: "lt_500us",
    ...overrides,
  };
}

test("latencyBucket: maps durations to the cross-language enum", () => {
  assert.equal(latencyBucket(0.01), "lt_50us");
  assert.equal(latencyBucket(0.1),  "lt_500us");
  assert.equal(latencyBucket(2),    "lt_5ms");
  assert.equal(latencyBucket(50),   "ge_5ms");
});

test("analytics: record + flush delivers the batch via sink", async () => {
  const batches: AnalyticsEvent[][] = [];
  const reporter = new AnalyticsReporter(
    { sink: (b) => void batches.push(b), flushIntervalMs: 1_000_000 },
    null,
    noopLogger,
  );
  reporter.record(event());
  reporter.record(event());
  assert.equal(batches.length, 0, "nothing sent before flush");

  await reporter.flush();
  assert.equal(batches.length, 1);
  assert.equal(batches[0]!.length, 2);
  reporter.close();
});

test("analytics: auto-flushes when batchSize is reached", async () => {
  const batches: AnalyticsEvent[][] = [];
  const reporter = new AnalyticsReporter(
    { sink: (b) => void batches.push(b), batchSize: 2, flushIntervalMs: 1_000_000 },
    null,
    noopLogger,
  );
  reporter.record(event());
  reporter.record(event()); // hits batchSize -> fire-and-forget flush
  await tick();
  assert.equal(batches.length, 1);
  assert.equal(batches[0]!.length, 2);
  reporter.close();
});

test("analytics: overflow drops OLDEST (keeps most-recent N)", async () => {
  const seen: AnalyticsEvent[] = [];
  const reporter = new AnalyticsReporter(
    {
      sink: (b) => { seen.push(...b); },
      batchSize: 100,
      flushIntervalMs: 1_000_000,
      maxQueueSize: 2,
    },
    null,
    noopLogger,
  );
  // Tag each event so we can verify which survived.
  for (let i = 0; i < 5; i++) {
    reporter.record(event({ occurredAt: `evt-${i}` }));
  }
  await reporter.flush();
  assert.equal(seen.length, 2, "buffer capped at maxQueueSize");
  // The two latest events (indices 3 and 4) survived; oldest (0,1,2) dropped.
  assert.deepEqual(
    seen.map((e) => e.occurredAt),
    ["evt-3", "evt-4"],
    "drop-oldest policy keeps the most-recent events",
  );
  assert.equal(reporter.status().eventsDropped, 3);
  reporter.close();
});

test("analytics: flush is single-flight (no overlapping sink calls)", async () => {
  let concurrent = 0;
  let maxConcurrent = 0;
  const sink = async () => {
    concurrent++;
    maxConcurrent = Math.max(maxConcurrent, concurrent);
    await tick();
    concurrent--;
  };
  const reporter = new AnalyticsReporter(
    { sink, batchSize: 1, flushIntervalMs: 1_000_000 },
    null,
    noopLogger,
  );
  for (let i = 0; i < 5; i++) reporter.record(event()); // each tries to flush
  await tick(); await tick(); await tick();
  assert.equal(maxConcurrent, 1, "at most one sink call in flight");
  reporter.close();
});

test("analytics: a throwing sink is warned + dropped, never propagated", async () => {
  const warnings: string[] = [];
  const logger: Logger = { ...noopLogger, warn: (m) => warnings.push(m) };
  const reporter = new AnalyticsReporter(
    { sink: () => { throw new Error("boom"); }, flushIntervalMs: 1_000_000 },
    null,
    logger,
  );
  reporter.record(event());
  await reporter.flush(); // must not throw
  assert.ok(
    warnings.find((w) => w.includes("sink threw")),
    `expected sink-threw warning; saw: ${warnings.join(",")}`,
  );
  assert.equal(reporter.status().lastFlushOk, false);
  assert.equal(reporter.status().consecutiveFailures, 1);
  reporter.close();
});

test("analytics: status() snapshots counters", async () => {
  const reporter = new AnalyticsReporter(
    { sink: async () => { /* succeeds */ }, batchSize: 100, flushIntervalMs: 1_000_000 },
    null,
    noopLogger,
  );
  for (let i = 0; i < 3; i++) reporter.record(event());
  let st = reporter.status();
  assert.equal(st.bufferSize, 3);
  assert.equal(st.eventsRecorded, 3);
  assert.equal(st.eventsSent, 0);

  await reporter.flush();
  st = reporter.status();
  assert.equal(st.bufferSize, 0);
  assert.equal(st.eventsSent, 3);
  assert.equal(st.lastFlushOk, true);
  assert.equal(st.consecutiveFailures, 0);
  reporter.close();
});

test("analytics: HTTP transport POSTs gzipped JSON envelope with batch", async () => {
  let seenUrl: string | undefined;
  let seenMethod: string | undefined;
  let seenHeaders: Record<string, string> | undefined;
  let seenBody: string | undefined;

  const fetchImpl: FetchLike = async (url, init) => {
    seenUrl     = url;
    seenMethod  = init?.method;
    seenHeaders = init?.headers;
    seenBody    = init?.body as unknown as string;
    return { ok: true, status: 204, json: async () => null };
  };

  const transport: ReporterTransport = {
    url:         "http://api.example/v1/decisions",
    headers:     { Authorization: "Bearer t", "X-Instance-Key": "pod-1" },
    sdkLang:     "node",
    sdkVersion:  "0.1.0",
    instanceKey: "pod-1",
    fetchImpl,
  };
  const reporter = new AnalyticsReporter(
    { batchSize: 2, flushIntervalMs: 1_000_000 },
    transport,
    noopLogger,
  );
  reporter.record(event({ matched: [{ ruleId: "r1", actionType: "block" }] }));
  reporter.record(event({ matched: [{ ruleId: "r2", actionType: "notify" }] }));
  await tick();
  // The auto-flush is fire-and-forget; give it a beat to land.
  await tick(); await tick();
  await reporter.flush();

  assert.equal(seenUrl,    "http://api.example/v1/decisions");
  assert.equal(seenMethod, "POST");
  assert.equal(seenHeaders?.["Authorization"],     "Bearer t");
  assert.equal(seenHeaders?.["Content-Encoding"],  "gzip");
  assert.equal(seenHeaders?.["Content-Type"],      "application/json");

  const decompressed = gunzipSync(Buffer.from(seenBody as unknown as Buffer)).toString("utf-8");
  const envelope = JSON.parse(decompressed) as {
    sdkLang: string;
    sdkVersion: string;
    instanceKey: string;
    events: AnalyticsEvent[];
  };
  assert.equal(envelope.sdkLang,     "node");
  assert.equal(envelope.sdkVersion,  "0.1.0");
  assert.equal(envelope.instanceKey, "pod-1");
  assert.equal(envelope.events.length, 2);
  assert.equal(envelope.events[0]!.matched[0]!.ruleId, "r1");
  // Every event carries a UUID idempotency key on the wire.
  assert.match(
    envelope.events[0]!.eventId,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
  );
  // Polymorphic discriminator present; legacy errorCount gone.
  assert.equal(envelope.events[0]!.type, "evaluation");
  assert.equal(
    (envelope.events[0] as unknown as Record<string, unknown>).errorCount,
    undefined,
  );
  reporter.close();
});

test("analytics: HTTP non-2xx is logged + counts as a failure", async () => {
  const warns: string[] = [];
  const fetchImpl: FetchLike = async () => ({ ok: false, status: 500, json: async () => null });
  const reporter = new AnalyticsReporter(
    { batchSize: 1, flushIntervalMs: 1_000_000 },
    {
      url: "http://api/decisions", headers: {}, sdkLang: "node",
      sdkVersion: "0.1.0", instanceKey: "i", fetchImpl,
    },
    { ...noopLogger, warn: (m) => warns.push(m) },
  );
  reporter.record(event());
  await reporter.flush();
  const st = reporter.status();
  assert.equal(st.eventsSent,    0);
  assert.equal(st.batchesFailed, 1);
  assert.equal(st.lastFlushOk,   false);
  assert.ok(st.lastFlushError?.includes("status 500"), `error: ${st.lastFlushError}`);
  assert.ok(warns.find((w) => w.includes("rejected")));
  reporter.close();
});

test("client: evaluate records an event with the new contract shape", async () => {
  const rules: RuleSetPayload = {
    schema_version: "1.0",
    rules_version: 1,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 100 }] },
        action: { type: "block" },
      },
    ],
  };
  const input: EvaluationInput = {
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    facts: { headers: {}, query: {}, params: {}, body: { amount: 500 }, meta: {} },
  };

  const events: AnalyticsEvent[] = [];
  const ks = await KLSwitch.init({
    rules,
    analytics: { sink: (b) => events.push(...b), batchSize: 1, flushIntervalMs: 1_000_000 },
  });
  ks.evaluate(input);
  await tick();

  assert.equal(events.length, 1);
  const e = events[0]!;
  assert.equal(e.rulesVersion, 1);
  assert.equal(e.evaluated, 1);
  assert.equal(e.skipped, 0);
  assert.deepEqual(e.request, { method: "POST", path: "/p" });
  assert.equal(e.matched.length, 1);
  assert.deepEqual(e.matched[0], { ruleId: "r", actionType: "block" });
  assert.equal(e.type, "evaluation");
  assert.equal((e as Record<string, unknown>).errorCount, undefined, "errorCount removed from wire");
  assert.match(e.latencyBucket, /^(lt_50us|lt_500us|lt_5ms|ge_5ms)$/);
  // ISO 8601 with Z suffix.
  assert.match(e.occurredAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
  ks.close();
});

test("client: no-match evaluations still produce an event with matched: []", async () => {
  const rules: RuleSetPayload = {
    schema_version: "1.0",
    rules_version: 2,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 1000 }] },
        action: { type: "block" },
      },
    ],
  };
  const input: EvaluationInput = {
    selector: { kind: "api", keys: { method: "POST", path: "/p" } },
    facts: { headers: {}, query: {}, params: {}, body: { amount: 50 }, meta: {} },   // below threshold
  };

  const events: AnalyticsEvent[] = [];
  const ks = await KLSwitch.init({
    rules,
    analytics: { sink: (b) => events.push(...b), batchSize: 1, flushIntervalMs: 1_000_000 },
  });
  ks.evaluate(input);
  await tick();

  assert.equal(events.length, 1);
  assert.deepEqual(events[0]!.matched, []);
  assert.equal(events[0]!.evaluated, 1, "candidate ran");
  ks.close();
});

test("client: a failed (ok:false) evaluation records NO event", async () => {
  const rules: RuleSetPayload = {
    schema_version: "1.0",
    rules_version: 3,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "exists" }] },
        action: { type: "block" },
      },
    ],
  };

  const events: AnalyticsEvent[] = [];
  const ks = await KLSwitch.init({
    rules,
    analytics: { sink: (b) => events.push(...b), batchSize: 1, flushIntervalMs: 1_000_000 },
  });
  // Malformed input (missing selector/facts) → the WASM boundary returns an
  // ok:false result (INVALID_INPUT), it does not throw. No decision happened,
  // so no analytics event must be recorded.
  const result = ks.evaluate({} as unknown as EvaluationInput);
  await tick();

  assert.equal(result.ok, false, "malformed input yields ok:false");
  assert.equal(events.length, 0, "ok:false evaluations do not emit an event");
  ks.close();
});
