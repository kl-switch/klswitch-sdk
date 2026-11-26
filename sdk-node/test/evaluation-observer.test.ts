import { test } from "node:test";
import assert from "node:assert/strict";

import {
  AnalyticsEvaluationObserver,
  CompositeEvaluationObserver,
  ManifestRecheckObserver,
  noopEvaluationObserver,
  type EvaluationContext,
  type EvaluationObserver,
} from "../src/evaluation-observer.js";
import type { AnalyticsEvent, AnalyticsStatus } from "../src/analytics.js";
import type {
  AnalyticsControl,
  AnalyticsRecorder,
  ManifestControl,
  PollingControl,
} from "../src/collaborators.js";
import type { ManifestStatus } from "../src/manifest.js";
import type { PollingStatus } from "../src/polling-manager.js";
import type { EvaluationInput, EvaluationResult } from "../src/types.js";

/**
 * `evaluate()` used to build decision events and push route manifests inline.
 * Those are observers now, which means they can be tested without an engine, a
 * network, or a client — and swapped for a fake, which is the point.
 */

const INPUT: EvaluationInput = {
  selector: { kind: "api", keys: { method: "get", path: "/v1/pay/:id" } },
  fields:   {},
};

const OK: EvaluationResult = {
  ok: true, matched: [], evaluated: 3, skipped: 1,
} as unknown as EvaluationResult;

function ctx(over: Partial<EvaluationContext> = {}): EvaluationContext {
  return {
    input: INPUT, result: OK, elapsedMs: 0.2,
    rulesVersion: 7, rulesById: new Map(), ...over,
  };
}

/** Captures what it was given instead of enqueuing anything. */
function fakeReporter(): { events: AnalyticsEvent[]; reporter: AnalyticsRecorder } {
  const events: AnalyticsEvent[] = [];
  const reporter: AnalyticsRecorder = { record: (e) => void events.push(e) };
  return { events, reporter };
}

test("the no-op observer accepts anything and does nothing", () => {
  assert.doesNotThrow(() => noopEvaluationObserver.onEvaluated(ctx()));
});

test("analytics observer builds one event per successful evaluation", () => {
  const { events, reporter } = fakeReporter();
  new AnalyticsEvaluationObserver(reporter).onEvaluated(ctx());

  assert.equal(events.length, 1);
  assert.equal(events[0]!.rulesVersion, 7);
  assert.equal(events[0]!.evaluated, 3);
  assert.equal(events[0]!.skipped, 1);
  // The selector's method is normalised for the console's schema.
  assert.equal(events[0]!.request.method, "GET");
  assert.equal(events[0]!.request.path, "/v1/pay/:id");
});

test("a failed evaluation is not a decision and is not reported", () => {
  const { events, reporter } = fakeReporter();
  const failed = { ok: false, matched: [], evaluated: 0, skipped: 0 } as unknown as EvaluationResult;
  new AnalyticsEvaluationObserver(reporter).onEvaluated(ctx({ result: failed }));

  assert.equal(events.length, 0, "ok:false is a caller/lifecycle error, not a decision");
});

test("nothing is reported before a ruleset version is known", () => {
  const { events, reporter } = fakeReporter();
  new AnalyticsEvaluationObserver(reporter).onEvaluated(ctx({ rulesVersion: null }));

  assert.equal(events.length, 0);
});

test("the manifest observer reports once, however many evaluations arrive", () => {
  let reports = 0;
  const reporter: ManifestControl = {
    report: async () => { reports++; return true; },
    status: () => ({} as unknown as ManifestStatus),
    stop:   () => {},
  };
  const observer = new ManifestRecheckObserver(reporter);

  observer.onEvaluated(ctx());
  observer.onEvaluated(ctx());
  observer.onEvaluated(ctx());

  assert.equal(reports, 1, "the re-check exists to close a startup gap, not to run per request");
});

test("a composite notifies every observer", () => {
  const seen: string[] = [];
  const named = (n: string): EvaluationObserver => ({ onEvaluated: () => void seen.push(n) });

  new CompositeEvaluationObserver([named("a"), named("b"), named("c")]).onEvaluated(ctx());

  assert.deepEqual(seen, ["a", "b", "c"]);
});

test("an observer can be swapped for a fake without an engine or a network", () => {
  const seen: EvaluationContext[] = [];
  const fake: EvaluationObserver = { onEvaluated: (c) => void seen.push(c) };

  fake.onEvaluated(ctx({ elapsedMs: 42 }));

  assert.equal(seen.length, 1);
  assert.equal(seen[0]!.elapsedMs, 42);
});

/**
 * The collaborators behind interfaces.
 *
 * The point of the narrow interfaces is that a fake needs to implement only what
 * the client actually calls — not batching, flushing, single-flight or a real
 * HTTP transport. If these fakes compile, the interfaces are honest about what is
 * required; if someone widens them carelessly, this file stops compiling.
 */

test("a fake satisfies AnalyticsControl with two methods", () => {
  let closed = false;
  const fake: AnalyticsControl = {
    status: () => ({ queued: 0, dropped: 0, sent: 0 } as unknown as AnalyticsStatus),
    close:  () => void (closed = true),
  };

  fake.close();
  assert.equal(closed, true);
  assert.equal(fake.status().queued, 0);
});

test("a fake satisfies AnalyticsRecorder with one method", () => {
  const seen: AnalyticsEvent[] = [];
  const fake: AnalyticsRecorder = { record: (e) => void seen.push(e) };

  new AnalyticsEvaluationObserver(fake).onEvaluated(ctx());

  assert.equal(seen.length, 1, "the observer needs nothing but record()");
});

test("recording and lifecycle are separate interfaces on purpose", () => {
  // The observer records and never closes; the client closes and never records.
  // A single fat interface would hand each a method it has no business calling.
  const recorder: AnalyticsRecorder = { record: () => {} };
  assert.equal("close" in recorder, false);
});

test("a fake satisfies ManifestControl with three methods", async () => {
  let stopped = false;
  const fake: ManifestControl = {
    report: async () => true,
    status: () => ({ reported: false } as unknown as ManifestStatus),
    stop:   () => void (stopped = true),
  };

  assert.equal(await fake.report(), true);
  fake.stop();
  assert.equal(stopped, true);
});

test("a fake satisfies PollingControl without timers or network", async () => {
  const calls: string[] = [];
  const fake: PollingControl = {
    start:    () => void calls.push("start"),
    pollOnce: async () => void calls.push("pollOnce"),
    status:   () => ({ currentInterval: 0 } as unknown as PollingStatus),
    stop:     () => void calls.push("stop"),
  };

  fake.start(1000);
  await fake.pollOnce();
  fake.stop();

  assert.deepEqual(calls, ["start", "pollOnce", "stop"]);
});
