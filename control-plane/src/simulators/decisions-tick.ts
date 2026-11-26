/**
 * Server-side decisions simulator.
 *
 * Mirrors the browser-side simulator the console used to ship in
 * mock mode. Stands in for real SDK wrappers shipping per-evaluation
 * analytics — every ~2s, picks the published rules in each
 * (service, env) and emits a batch of plausible `AnalyticsEvent`s
 * straight into the store.
 *
 * Without this, real-backend mode renders an honest-but-bleak demo:
 * the Blocks tile shows 0, the Fires card shows 0, the Recent
 * decisions log is empty. The moment a real wrapper batcher ships,
 * this file becomes dead code — the ingestion endpoint stays the
 * same.
 *
 * Flag: `KS_SIMULATE=1` (default on in dev). Set to `0` to turn off
 * the synthetic firehose (e.g. when testing real wrapper analytics).
 *
 * Bounded: store.ingest() trims to MAX_EVENTS=500. Memory stays flat.
 */
import type { AnalyticsEvent, LatencyBucket } from "../zod/index.js";

import { store } from "../store/index.js";

const TICK_MS = 2000;

/** Background traffic for "no-match" events — realistic, harmless paths. */
const BORING_TRAFFIC: Array<{ method: string; path: string }> = [
  { method: "GET",  path: "/health" },
  { method: "GET",  path: "/v1/users/:id" },
  { method: "GET",  path: "/v1/orders" },
  { method: "POST", path: "/v1/orders" },
  { method: "GET",  path: "/v1/products/:id" },
  { method: "PATCH",path: "/v1/users/:id/profile" },
];

const LATENCY_DIST: Array<{ bucket: LatencyBucket; weight: number }> = [
  { bucket: "lt_50us",  weight: 70 },
  { bucket: "lt_500us", weight: 22 },
  { bucket: "lt_5ms",   weight: 7  },
  { bucket: "ge_5ms",   weight: 1  },
];

function pickWeighted<T extends { weight: number }>(items: T[]): T {
  const total = items.reduce((n, it) => n + it.weight, 0);
  let r = Math.random() * total;
  for (const it of items) {
    r -= it.weight;
    if (r <= 0) return it;
  }
  return items[items.length - 1]!;
}

function pickOne<T>(items: readonly T[]): T | undefined {
  if (items.length === 0) return undefined;
  return items[Math.floor(Math.random() * items.length)];
}

let _eventCounter = 1;
function nextId(): string {
  return `ev_${Date.now().toString(36)}_${(_eventCounter++).toString(36)}`;
}

/**
 * Synthetic instances per (service, env). In Phase 8 the backend
 * doesn't have a real instance table — the dashboard surfaces a
 * fixed health summary, but the decisions stream pretends each
 * (service, env) has 2 healthy instances so per-rule fire counts
 * have multi-source variance.
 */
function instancesFor(serviceId: string, envId: string): string[] {
  // Stable ids so the decisions log feels consistent across ticks.
  return [
    `inst_${serviceId.replace("svc_", "")}_${envId.split("_").pop()}_a`,
    `inst_${serviceId.replace("svc_", "")}_${envId.split("_").pop()}_b`,
  ];
}

/** Emit one event for a single (service, env, instance). */
function maybeEmit(serviceId: string, envId: string, instanceId: string): AnalyticsEvent | null {
  // Drop a fraction outright — the wrapper-contract §6 "sampling"
  // behaviour wrappers are required to apply.
  if (Math.random() < 0.25) return null;

  const occurredAt   = new Date().toISOString();
  const latencyBucket = pickWeighted(LATENCY_DIST).bucket;
  const rulesVersion  = store.rulesVersion(envId);
  const published     = store.publishedRulesFor(serviceId, envId);

  if (published.length === 0 || Math.random() > 0.4) {
    // No-match background-traffic event.
    const req = pickOne(BORING_TRAFFIC)!;
    return {
      id:           nextId(),
      orgId:        "org_acme",
      serviceId,
      envId,
      instanceId,
      occurredAt,
      rulesVersion,
      request:      { method: req.method, path: req.path },
      matched:      [],
      evaluated:    Math.max(1, published.length),
      skipped:      0,
      latencyBucket,
      errorCount:   0,
    };
  }

  // Matched event — pick one published rule, sometimes two.
  const primary = published[Math.floor(Math.random() * published.length)]!;
  const matched = [{
    ruleId:     primary.rule.id,
    actionType: primary.version.body.action.type,
  }];
  if (Math.random() < 0.15 && published.length > 1) {
    const second = published.find((p) => p.rule.id !== primary.rule.id);
    if (second) {
      matched.push({ ruleId: second.rule.id, actionType: second.version.body.action.type });
    }
  }

  return {
    id:           nextId(),
    orgId:        "org_acme",
    serviceId,
    envId,
    instanceId,
    occurredAt,
    rulesVersion,
    request: {
      method: primary.rule.selectorMethod,
      path:   primary.rule.selectorPath,
    },
    matched,
    evaluated:    published.length,
    skipped:      Math.max(0, published.length - matched.length - 1),
    latencyBucket,
    errorCount:   0,
  };
}

function applyTick(): void {
  const batch: AnalyticsEvent[] = [];
  for (const env of store.envs()) {
    if (env.archived) continue;
    for (const instanceId of instancesFor(env.serviceId, env.id)) {
      // ~50% per (env, instance) per tick — same logic as the browser
      // simulator. Over a 6-tick window every instance has very high
      // coverage so the per-instance log is never starved.
      if (Math.random() > 0.5) continue;
      const perInstance = 1 + Math.floor(Math.random() * 2);   // 1..2 events
      for (let i = 0; i < perInstance; i++) {
        const ev = maybeEmit(env.serviceId, env.id, instanceId);
        if (ev) batch.push(ev);
      }
    }
  }
  if (batch.length === 0) return;
  store.ingest(batch);
}

let _handle: ReturnType<typeof setInterval> | null = null;

/**
 * Start the simulator. Idempotent. No-op if KS_SIMULATE=0.
 */
export function startDecisionsSimulator(): void {
  if (process.env.KS_SIMULATE === "0") {
    // eslint-disable-next-line no-console
    console.log("decisions simulator: disabled (KS_SIMULATE=0)");
    return;
  }
  if (_handle) return;
  _handle = setInterval(applyTick, TICK_MS);
  // eslint-disable-next-line no-console
  console.log(`decisions simulator: emitting every ${TICK_MS}ms`);
}

export function stopDecisionsSimulator(): void {
  if (_handle) {
    clearInterval(_handle);
    _handle = null;
  }
}
