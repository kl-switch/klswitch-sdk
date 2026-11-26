/**
 * Analytics — ingestion + reads.
 *
 * Ingestion is what real wrappers POST per the §6 contract. The reads
 * back the console's instance-detail card, the rule-detail fire chip,
 * and the dashboard's Blocks tile.
 *
 * Ingestion validates with the same Zod schema the console uses, so a
 * wrapper that ships a malformed event gets a 422 instead of silent
 * data loss.
 */
import { Hono } from "hono";

import { store, type DecisionScope } from "../store/index.js";
import {
  AnalyticsEventSchema,
  type DormancyEntry,
  type HeartbeatResponse,
  type RuleSparkline,
  type TimeseriesResponse,
} from "../zod/index.js";

const HOUR    = 60 * 60 * 1000;
const DAY     = 24 * HOUR;
const QUARTER = 15 * 60 * 1000;

function parseScope(s: string | null): DecisionScope {
  if (!s || s === "org") return () => true;
  if (s.startsWith("service:"))  { const id = s.slice(8);  return (e) => e.serviceId  === id; }
  if (s.startsWith("env:"))      { const id = s.slice(4);  return (e) => e.envId      === id; }
  if (s.startsWith("instance:")) { const id = s.slice(9);  return (e) => e.instanceId === id; }
  return () => true;
}

function parseBucketMs(b: string | null): number {
  if (b === "5min")  return 5 * 60 * 1000;
  if (b === "15min") return QUARTER;
  if (b === "6h")    return 6 * HOUR;
  return HOUR;
}

function parseWindowMs(w: string | null): number {
  if (w === "1h")  return HOUR;
  if (w === "24h") return DAY;
  if (w === "7d")  return 7 * DAY;
  return DAY;
}

export const analytics = new Hono();

analytics.post("/orgs/:org/analytics", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid JSON" }, 400);
  }
  const arr = Array.isArray(body) ? body : [body];
  const parsed = arr.map((row) => AnalyticsEventSchema.safeParse(row));
  const bad    = parsed.findIndex((p) => !p.success);
  if (bad >= 0) {
    const err = parsed[bad];
    return c.json({
      error: "schema validation failed",
      at:    bad,
      issues: err && !err.success ? err.error.issues : [],
    }, 422);
  }
  store.ingest(parsed.map((p) => p.success ? p.data : null!).filter(Boolean));
  return c.body(null, 202);
});

analytics.get("/orgs/:org/analytics/recent", (c) => {
  const instanceId = c.req.query("instanceId");
  if (!instanceId) return c.json({ error: "instanceId required" }, 400);
  const limit = Number(c.req.query("limit") ?? "25");
  return c.json(store.recentForInstance(instanceId, limit));
});

analytics.get("/orgs/:org/analytics/aggregates", (c) => {
  const ids = (c.req.query("ruleIds") ?? "").split(",").filter(Boolean);
  if (ids.length === 0) return c.json([]);
  return c.json(
    ids.map((ruleId) => ({
      ruleId,
      lastHour: store.firesForRule(ruleId, HOUR),
      last24h:  store.firesForRule(ruleId, DAY),
    })),
  );
});

analytics.get("/orgs/:org/analytics/summary", (c) => {
  return c.json({
    blocksLast24h:    store.blocksLast24h(),
    decisionsLast24h: store.decisionsLast24h(),
    blocksLastHour:   store.blocksLastHour(),
    errorsLast24h:    store.errorCount(() => true, DAY),
  });
});

// ── Phase 9: timeseries ────────────────────────────────────────────────
analytics.get("/orgs/:org/analytics/timeseries", (c) => {
  const scope    = parseScope(c.req.query("scope") ?? null);
  const bucketMs = parseBucketMs(c.req.query("bucket") ?? null);
  const windowMs = parseWindowMs(c.req.query("window") ?? null);
  const points   = store.timeseries(scope, bucketMs, windowMs);
  const payload: TimeseriesResponse = { bucketMs, points };
  return c.json(payload);
});

// ── Phase 9: latency histogram ─────────────────────────────────────────
analytics.get("/orgs/:org/analytics/histogram", (c) => {
  const scope    = parseScope(c.req.query("scope") ?? null);
  const windowMs = parseWindowMs(c.req.query("window") ?? null);
  return c.json(store.latencyHistogram(scope, windowMs));
});

// ── Phase 9: per-rule sparklines ───────────────────────────────────────
analytics.get("/orgs/:org/analytics/sparklines", (c) => {
  const idsParam = c.req.query("ruleIds");
  const wanted   = idsParam
    ? new Set(idsParam.split(",").filter(Boolean))
    : null;
  const candidates = store.rules().filter((r) => {
    if (!r.publishedVersionId) return false;
    if (wanted && !wanted.has(r.id)) return false;
    return true;
  });
  const out: RuleSparkline[] = candidates.map((r) => {
    const scope: DecisionScope = (e) =>
      e.matched.some((m) => m.ruleId === r.id);
    const points = store
      .timeseries(scope, QUARTER, DAY)
      .map((p) => p.decisions);
    const total24h = points.reduce((n, v) => n + v, 0);
    return { ruleId: r.id, ruleName: r.name, points, total24h };
  });
  return c.json(out);
});

// ── Phase 9.1: heartbeat ───────────────────────────────────────────────
analytics.get("/orgs/:org/analytics/heartbeat", (c) => {
  const instanceId = c.req.query("instanceId");
  if (!instanceId) return c.json({ error: "instanceId required" }, 400);
  const bucketMs = Number(c.req.query("bucketMs") ?? String(5 * 60 * 1000));
  const windowMs = Number(c.req.query("windowMs") ?? String(DAY));
  const cells = store.heartbeat(instanceId, bucketMs, windowMs);
  const payload: HeartbeatResponse = { bucketMs, cells };
  return c.json(payload);
});

// ── Phase 9: dormant rule report ───────────────────────────────────────
analytics.get("/orgs/:org/analytics/dormancy", (c) => {
  const days = Number(c.req.query("days") ?? "7");
  const published = store.rules().filter((r) => r.publishedVersionId);
  const out: DormancyEntry[] = published.map((r) => {
    const at = store.lastFiredAt(r.id);
    const daysSilent = at
      ? Math.floor((Date.now() - new Date(at).getTime()) / DAY)
      : null;
    return {
      ruleId:      r.id,
      ruleName:    r.name,
      serviceId:   r.serviceId,
      envId:       r.envId,
      lastFiredAt: at,
      daysSilent,
    };
  }).filter((d) => d.daysSilent === null || d.daysSilent >= days);
  return c.json(out);
});
