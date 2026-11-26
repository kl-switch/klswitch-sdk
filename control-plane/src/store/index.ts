/**
 * In-memory store.
 *
 * v0 carries a small representative seed (3 services × 3 envs, ~4 rules,
 * 2 published) — enough to make the wires real without duplicating the
 * console's 350-line seed-rules file. A future Phase 8.x can lift the
 * full console seed via shared `fixtures/` once the JSON import story
 * is sorted.
 *
 * On boot we hydrate from `seed()`; mutations land later in Phase 9.
 */
import type {
  AnalyticsEvent, Env, HeartbeatCell, LatencyHistogram, Rule, RuleVersion, Service, TimeseriesPoint,
} from "../zod/index.js";

/** Scope filter — pluggable subset selector over the event stream. */
export type DecisionScope = (e: AnalyticsEvent) => boolean;

// ─── Seat / org / audit types — used by the operator surface ─────────────
export type Role = "owner" | "admin" | "editor" | "viewer";
export type Seat = {
  id: string; email: string; name: string; role: Role; avatarBg: string;
};

export type AuditEvent = {
  id:           string;
  orgId:        string;
  occurredAt:   string;
  actorId:      string;
  actorName:    string;
  actorKind:    "user" | "system" | "api_token";
  action:       string;
  resourceType: string;
  resourceId:   string;
  resourceName?: string;
  ip:           string | null;
  userAgent:    string | null;
  requestId:    string | null;
  beforeJson:   unknown;
  afterJson:    unknown;
  metadataJson?: Record<string, unknown>;
};

const NOW = () => new Date().toISOString();
const HOUR = 60 * 60 * 1000;
const DAY  = 24 * HOUR;

// ─── Seeds ──────────────────────────────────────────────────────────────
const SERVICES: Service[] = [
  { id: "svc_payments", orgId: "org_acme", key: "payments-api", name: "payments-api", language: "java", archived: false },
  { id: "svc_auth",     orgId: "org_acme", key: "auth-api",     name: "auth-api",     language: "node", archived: false },
  { id: "svc_search",   orgId: "org_acme", key: "search-api",   name: "search-api",   language: "go",   archived: false },
];

const ENVS: Env[] = SERVICES.flatMap((s) =>
  (["dev", "staging", "prod"] as const).map((k) => ({
    id:        `env_${s.id.replace("svc_", "")}_${k}`,
    serviceId: s.id,
    key:       k,
    archived:  false,
  })),
);

const RULE_VERSIONS: RuleVersion[] = [
  {
    id: "rv_payments_large_v1",
    ruleId: "rule_payments_block_large",
    versionInt: 1,
    status: "published",
    body: {
      id: "block_large_payment",
      name: "Block large payment",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/payments" } },
      enabled: true,
      priority: 100,
      condition: { all: [{ field: "body.amount", operator: "gt", value: 100000 }] },
      action: { type: "block", payload: { statusCode: 429, code: "PAYMENT_TOO_LARGE" } },
    },
    authorId: "user_devon", authorName: "Devon Rodriguez",
    reviewedById: "user_maya",
    publishedAt: new Date(Date.now() - 3 * DAY).toISOString(),
    createdAt:   new Date(Date.now() - 3 * DAY - HOUR).toISOString(),
    comment: "Initial publish",
  },
  {
    id: "rv_auth_token_v1",
    ruleId: "rule_auth_token_block",
    versionInt: 1,
    status: "published",
    body: {
      id: "block_token_endpoint_exploit",
      name: "Block CVE-2026-3142 token endpoint exploit",
      selector: { kind: "api", keys: { method: "POST", path: "/v1/auth/token" } },
      enabled: true,
      priority: 90,
      condition: {
        any: [
          { field: "headers.user-agent", operator: "contains", value: "exploit-kit" },
          { field: "body.grant_type",    operator: "eq",       value: "password_override" },
        ],
      },
      action: { type: "block", payload: { statusCode: 403, code: "CVE_2026_3142" } },
    },
    authorId: "user_riley", authorName: "Riley Park",
    reviewedById: "user_devon",
    publishedAt: new Date(Date.now() - 18 * HOUR).toISOString(),
    createdAt:   new Date(Date.now() - 20 * HOUR).toISOString(),
    comment: "CVE response",
  },
  {
    id: "rv_search_rate_v1",
    ruleId: "rule_search_rate_brake",
    versionInt: 1,
    status: "draft",
    body: {
      id: "search_rate_brake",
      name: "Search rate brake",
      selector: { kind: "api", keys: { method: "GET", path: "/v1/search" } },
      enabled: false,
      priority: 50,
      condition: { all: [{ field: "headers.x-tenant", operator: "eq", value: "globex" }] },
      action: { type: "block", payload: { statusCode: 429, code: "RATE_LIMITED" } },
    },
    authorId: "user_riley", authorName: "Riley Park",
    reviewedById: null, publishedAt: null,
    createdAt: new Date(Date.now() - HOUR).toISOString(),
    comment: undefined,
  },
];

const SEATS: Seat[] = [
  { id: "user_maya",  email: "maya@acme.com",  name: "Maya Singh",       role: "owner",  avatarBg: "bg-emerald-500" },
  { id: "user_devon", email: "devon@acme.com", name: "Devon Rodriguez",  role: "admin",  avatarBg: "bg-sky-500" },
  { id: "user_riley", email: "riley@acme.com", name: "Riley Park",       role: "editor", avatarBg: "bg-amber-500" },
  { id: "user_sam",   email: "sam@acme.com",   name: "Sam Quinn",        role: "viewer", avatarBg: "bg-violet-500" },
];

const PERMS_BY_ROLE: Record<Role, string[]> = {
  owner:  ["rule:read","rule:author","rule:review","rule:publish","rule:publish:prod","audit:read","member:invite","member:role:set","token:create","token:revoke","settings:manage","sso:manage","billing:manage"],
  admin:  ["rule:read","rule:author","rule:review","rule:publish","rule:publish:prod","audit:read","member:invite","member:role:set","token:create","token:revoke","settings:manage","sso:manage"],
  editor: ["rule:read","rule:author","rule:review","rule:publish","audit:read","token:create"],
  viewer: ["rule:read","audit:read"],
};

const AUDIT: AuditEvent[] = [
  {
    id: "aud_0001", orgId: "org_acme",
    occurredAt: new Date(Date.now() - 18 * 60 * 60 * 1000).toISOString(),
    actorId: "user_riley", actorName: "Riley Park", actorKind: "user",
    action: "rule.publish", resourceType: "rule",
    resourceId: "rule_auth_token_block",
    resourceName: "Block CVE-2026-3142 token endpoint exploit",
    ip: "10.0.0.21", userAgent: "Mozilla/5.0", requestId: "req_001",
    beforeJson: { status: "approved" },
    afterJson:  { status: "published", serviceId: "svc_auth", envId: "env_auth_prod" },
  },
  {
    id: "aud_0002", orgId: "org_acme",
    occurredAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(),
    actorId: "user_devon", actorName: "Devon Rodriguez", actorKind: "user",
    action: "rule.publish", resourceType: "rule",
    resourceId: "rule_payments_block_large",
    resourceName: "Block large payment",
    ip: "10.0.0.5", userAgent: "Mozilla/5.0", requestId: "req_002",
    beforeJson: { status: "approved" },
    afterJson:  { status: "published", serviceId: "svc_payments", envId: "env_payments_prod" },
  },
];

const RULES: Rule[] = [
  {
    id: "rule_payments_block_large",
    serviceId: "svc_payments", envId: "env_payments_prod",
    key: "block_large_payment", name: "Block large payment",
    selectorMethod: "POST", selectorPath: "/v1/payments",
    priority: 100, tags: ["fraud", "money"],
    enabled: true,
    currentVersionId:   "rv_payments_large_v1",
    publishedVersionId: "rv_payments_large_v1",
  },
  {
    id: "rule_auth_token_block",
    serviceId: "svc_auth", envId: "env_auth_prod",
    key: "block_token_endpoint_exploit", name: "Block CVE-2026-3142 token endpoint exploit",
    selectorMethod: "POST", selectorPath: "/v1/auth/token",
    priority: 90, tags: ["cve", "incident"],
    enabled: true,
    currentVersionId:   "rv_auth_token_v1",
    publishedVersionId: "rv_auth_token_v1",
  },
  {
    id: "rule_search_rate_brake",
    serviceId: "svc_search", envId: "env_search_dev",
    key: "search_rate_brake", name: "Search rate brake",
    selectorMethod: "GET", selectorPath: "/v1/search",
    priority: 50, tags: ["rate", "draft"],
    enabled: false,
    currentVersionId:   "rv_search_rate_v1",
    publishedVersionId: null,
  },
];

// ─── Mutable state ──────────────────────────────────────────────────────
type State = {
  services:       Service[];
  envs:           Env[];
  rules:          Rule[];
  ruleVersions:   RuleVersion[];
  analytics:      AnalyticsEvent[];
  audit:          AuditEvent[];
  /** Authoritative rules-version per env — wrappers see this on the next poll. */
  rulesVersionByEnv: Record<string, number>;
  /** Sticky demo-seat for /api/auth/me. Phase 9 swaps to cookie-backed session. */
  activeSeatId:   string;
};

const ANALYTICS_MAX = 500;

const state: State = {
  services:     SERVICES,
  envs:         ENVS,
  rules:        RULES,
  ruleVersions: RULE_VERSIONS,
  analytics:    [],
  audit:        AUDIT,
  rulesVersionByEnv: Object.fromEntries(
    ENVS.map((e) => [e.id, e.key === "prod" ? 4 : 1]),
  ),
  activeSeatId: "user_maya",
};

export const store = {
  // Reads
  services()     { return state.services; },
  envs()         { return state.envs; },
  rules()        { return state.rules; },
  ruleVersions() { return state.ruleVersions; },

  service(id: string)   { return state.services.find((s) => s.id === id); },
  env(id: string)       { return state.envs.find((e) => e.id === id); },
  rule(id: string)      { return state.rules.find((r) => r.id === id); },
  ruleVersion(id: string) { return state.ruleVersions.find((v) => v.id === id); },

  serviceByKey(key: string) {
    return state.services.find((s) => s.key === key && !s.archived);
  },
  envForService(serviceId: string, key: string) {
    return state.envs.find((e) => e.serviceId === serviceId && e.key === key && !e.archived);
  },

  /** All published rules currently live in (service, env). */
  publishedRulesFor(serviceId: string, envId: string) {
    return state.rules
      .filter((r) => r.serviceId === serviceId && r.envId === envId && r.publishedVersionId)
      .map((r) => {
        const v = state.ruleVersions.find((rv) => rv.id === r.publishedVersionId);
        return { rule: r, version: v! };
      })
      .filter((x) => !!x.version);
  },

  rulesVersion(envId: string) {
    return state.rulesVersionByEnv[envId] ?? 0;
  },

  // Analytics
  ingest(events: AnalyticsEvent[]) {
    // Newest first; trim to bound memory.
    state.analytics = [...events, ...state.analytics].slice(0, ANALYTICS_MAX);
  },
  recentForInstance(instanceId: string, limit: number) {
    return state.analytics.filter((e) => e.instanceId === instanceId).slice(0, limit);
  },
  firesForRule(ruleId: string, sinceMs: number) {
    const since = Date.now() - sinceMs;
    return state.analytics.reduce((n, e) => {
      if (new Date(e.occurredAt).getTime() < since) return n;
      return n + (e.matched.some((m) => m.ruleId === ruleId) ? 1 : 0);
    }, 0);
  },
  blocksLast24h() {
    const since = Date.now() - DAY;
    return state.analytics.reduce((n, e) => {
      if (new Date(e.occurredAt).getTime() < since) return n;
      return n + (e.matched.some((m) => m.actionType === "block") ? 1 : 0);
    }, 0);
  },
  decisionsLast24h() {
    const since = Date.now() - DAY;
    return state.analytics.reduce((n, e) =>
      n + (new Date(e.occurredAt).getTime() >= since ? 1 : 0), 0);
  },
  blocksLastHour() {
    const since = Date.now() - HOUR;
    return state.analytics.reduce((n, e) => {
      if (new Date(e.occurredAt).getTime() < since) return n;
      return n + (e.matched.some((m) => m.actionType === "block") ? 1 : 0);
    }, 0);
  },

  // Summaries
  rulesSummary() {
    return {
      total:          state.rules.length,
      enabled:        state.rules.filter((r) => r.enabled).length,
      disabled:       state.rules.filter((r) => !r.enabled).length,
      pendingReviews: 0,   // no pending in this seed
    };
  },

  /** Fixed for Phase 8 — instances/health stays static until Phase 9. */
  instanceHealthSummary() {
    return { total: 12, healthy: 10, stale: 1, down: 1 };
  },

  // ── Seats + sessions (Phase 8 stub — sticky in-memory) ───────────────
  seats() { return SEATS; },
  seat(id: string) { return SEATS.find((s) => s.id === id); },
  permsForRole(role: Role) { return PERMS_BY_ROLE[role]; },
  activeSeat() { return SEATS.find((s) => s.id === state.activeSeatId) ?? SEATS[0]!; },
  setActiveSeat(id: string) {
    if (SEATS.some((s) => s.id === id)) state.activeSeatId = id;
  },

  // ── Phase 9 aggregations ─────────────────────────────────────────────
  timeseries(scope: DecisionScope, bucketMs: number, windowMs: number): TimeseriesPoint[] {
    // Anchor on now: the last bucket is the one containing `now`, and
    // we walk N buckets backwards. Anchoring on (now - window) would
    // chop off the in-progress bucket exactly when most of the
    // interesting data lives.
    const nowMs    = Date.now();
    const nBuckets = Math.ceil(windowMs / bucketMs);
    const lastBucketStart  = Math.floor(nowMs / bucketMs) * bucketMs;
    const firstBucketStart = lastBucketStart - (nBuckets - 1) * bucketMs;
    const points: TimeseriesPoint[] = Array.from({ length: nBuckets }, (_, i) => ({
      tsStartMs: firstBucketStart + i * bucketMs,
      decisions: 0,
      blocks:    0,
    }));
    for (const e of state.analytics) {
      if (!scope(e)) continue;
      const t = new Date(e.occurredAt).getTime();
      if (t < firstBucketStart || t >= firstBucketStart + nBuckets * bucketMs) continue;
      const idx = Math.floor((t - firstBucketStart) / bucketMs);
      const p = points[idx];
      if (!p) continue;
      p.decisions += 1;
      if (e.matched.some((m) => m.actionType === "block")) p.blocks += 1;
    }
    return points;
  },

  latencyHistogram(scope: DecisionScope, windowMs: number): LatencyHistogram {
    const since = Date.now() - windowMs;
    const h: LatencyHistogram = {
      lt_50us: 0, lt_500us: 0, lt_5ms: 0, ge_5ms: 0, total: 0,
    };
    for (const e of state.analytics) {
      if (new Date(e.occurredAt).getTime() < since) continue;
      if (!scope(e)) continue;
      h[e.latencyBucket] += 1;
      h.total += 1;
    }
    return h;
  },

  lastFiredAt(ruleId: string): string | null {
    // events stored newest-first; first hit wins.
    for (const e of state.analytics) {
      if (e.matched.some((m) => m.ruleId === ruleId)) return e.occurredAt;
    }
    return null;
  },

  errorCount(scope: DecisionScope, windowMs: number): number {
    const since = Date.now() - windowMs;
    return state.analytics.reduce((n, e) => {
      if (new Date(e.occurredAt).getTime() < since) return n;
      if (!scope(e)) return n;
      return n + e.errorCount;
    }, 0);
  },

  heartbeat(instanceId: string, bucketMs: number, windowMs: number): HeartbeatCell[] {
    const nowMs    = Date.now();
    const nBuckets = Math.ceil(windowMs / bucketMs);
    const lastStart = Math.floor(nowMs / bucketMs) * bucketMs;
    const firstStart = lastStart - (nBuckets - 1) * bucketMs;

    const counts = new Array<number>(nBuckets).fill(0);
    const maxVer = new Array<number>(nBuckets).fill(-1);

    let envId: string | null = null;
    for (const e of state.analytics) {
      if (e.instanceId === instanceId && envId === null) envId = e.envId;
    }
    let envMaxVer = 0;
    for (const e of state.analytics) {
      if (envId && e.envId === envId && e.rulesVersion > envMaxVer) envMaxVer = e.rulesVersion;
      if (e.instanceId !== instanceId) continue;
      const t = new Date(e.occurredAt).getTime();
      if (t < firstStart || t >= firstStart + nBuckets * bucketMs) continue;
      const idx = Math.floor((t - firstStart) / bucketMs);
      counts[idx] = (counts[idx] ?? 0) + 1;
      if (e.rulesVersion > (maxVer[idx] ?? -1)) maxVer[idx] = e.rulesVersion;
    }

    const cells: HeartbeatCell[] = [];
    for (let i = 0; i < nBuckets; i++) {
      const c = counts[i] ?? 0;
      const v = maxVer[i] ?? -1;
      let stateVal: HeartbeatCell["state"];
      if (c === 0) stateVal = "missed";
      else if (envMaxVer > 0 && v >= 0 && v < envMaxVer) stateVal = "stale";
      else stateVal = "healthy";
      cells.push({ tsStartMs: firstStart + i * bucketMs, state: stateVal });
    }
    return cells;
  },

  // ── Audit ────────────────────────────────────────────────────────────
  audit(filter: { actor?: string; action?: string }, limit: number) {
    let items = state.audit;
    if (filter.actor)  items = items.filter((e) => e.actorId === filter.actor);
    if (filter.action) items = items.filter((e) => e.action === filter.action);
    items = items.slice().sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
    return { items: items.slice(0, limit), totalCount: items.length };
  },
};

export { NOW };
