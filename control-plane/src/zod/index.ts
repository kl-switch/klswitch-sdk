/**
 * Zod schemas — mirrored from the console for Phase 8. The console's
 * schemas are themselves derived from `spec/schemas/*.schema.json`, so
 * the contract is single-sourced at the spec level.
 *
 * v0 duplicates rather than imports: extracting to a shared workspace
 * package can wait until divergence becomes a problem.
 */
import { z } from "zod";

// ─── Service + env ───────────────────────────────────────────────────────
export const ENV_KEYS = ["dev", "staging", "prod"] as const;
export type EnvKey = (typeof ENV_KEYS)[number];

export const ServiceSchema = z.object({
  id:       z.string(),
  orgId:    z.string(),
  key:      z.string(),
  name:     z.string(),
  language: z.enum(["node", "java", "go", "python"]),
  archived: z.boolean(),
});
export type Service = z.infer<typeof ServiceSchema>;

export const EnvSchema = z.object({
  id:        z.string(),
  serviceId: z.string(),
  key:       z.enum(ENV_KEYS),
  archived:  z.boolean(),
});
export type Env = z.infer<typeof EnvSchema>;

// ─── Rule body — mirrors spec/schemas/ruleset.schema.json ────────────────
export const SelectorSchema = z.object({
  kind: z.literal("api"),
  keys: z.object({ method: z.string().min(1), path: z.string().min(1) }),
});

export const ConditionOperatorSchema = z.enum([
  "eq", "neq", "gt", "gte", "lt", "lte",
  "contains", "not_contains", "in", "not_in",
  "exists", "not_exists",
]);

export const ConditionValueSchema = z.union([
  z.string(), z.number(), z.boolean(), z.null(),
  z.array(z.union([z.string(), z.number(), z.boolean(), z.null()])),
]);

const ConditionLeafSchema = z.object({
  field:    z.string().min(1),
  operator: ConditionOperatorSchema,
  value:    ConditionValueSchema.optional(),
});

export type ConditionNode =
  | { all: ConditionNode[] }
  | { any: ConditionNode[] }
  | { not: ConditionNode  }
  | z.infer<typeof ConditionLeafSchema>;

export const ConditionNodeSchema: z.ZodType<ConditionNode> = z.lazy(() =>
  z.union([
    z.object({ all: z.array(ConditionNodeSchema).min(1) }),
    z.object({ any: z.array(ConditionNodeSchema).min(1) }),
    z.object({ not: ConditionNodeSchema }),
    ConditionLeafSchema,
  ]),
);

export const ActionSchema = z.object({
  type:    z.string().min(1),
  payload: z.record(z.string(), z.unknown()).optional(),
});

export const RuleBodySchema = z.object({
  id:        z.string(),
  name:      z.string(),
  selector:  SelectorSchema,
  enabled:   z.boolean(),
  priority:  z.number().int().optional(),
  condition: ConditionNodeSchema,
  action:    ActionSchema,
  tags:      z.array(z.string()).optional(),
  metadata:  z.record(z.string(), z.unknown()).optional(),
});
export type RuleBody = z.infer<typeof RuleBodySchema>;

export const RuleVersionStatusSchema = z.enum([
  "draft", "pending_review", "approved", "published", "archived",
]);

export const RuleVersionSchema = z.object({
  id:           z.string(),
  ruleId:       z.string(),
  versionInt:   z.number().int().positive(),
  status:       RuleVersionStatusSchema,
  body:         RuleBodySchema,
  authorId:     z.string(),
  authorName:   z.string(),
  reviewedById: z.string().nullable().optional(),
  publishedAt:  z.string().nullable().optional(),
  createdAt:    z.string(),
  comment:      z.string().optional(),
});
export type RuleVersion = z.infer<typeof RuleVersionSchema>;

export const RuleSchema = z.object({
  id:                 z.string(),
  serviceId:          z.string(),
  envId:              z.string(),
  key:                z.string(),
  name:               z.string(),
  selectorMethod:     z.string(),
  selectorPath:       z.string(),
  priority:           z.number().int(),
  tags:               z.array(z.string()),
  enabled:            z.boolean(),
  currentVersionId:   z.string(),
  publishedVersionId: z.string().nullable(),
});
export type Rule = z.infer<typeof RuleSchema>;

// ─── Wrapper-facing ruleset payload (matches backend/examples/) ──────────
export const RuleSetPayloadSchema = z.object({
  schema_version: z.string(),
  rules_version:  z.number().int().nonnegative(),
  rules:          z.array(RuleBodySchema),
});
export type RuleSetPayload = z.infer<typeof RuleSetPayloadSchema>;

// ─── Analytics — wrapper-contract §6 ─────────────────────────────────────
export const LATENCY_BUCKETS = ["lt_50us", "lt_500us", "lt_5ms", "ge_5ms"] as const;
export const LatencyBucketSchema = z.enum(LATENCY_BUCKETS);
export type LatencyBucket = (typeof LATENCY_BUCKETS)[number];

export const AnalyticsEventSchema = z.object({
  id:             z.string(),
  orgId:          z.string(),
  serviceId:      z.string(),
  envId:          z.string(),
  instanceId:     z.string(),
  occurredAt:     z.string(),
  rulesVersion:   z.number().int().nonnegative(),
  request: z.object({ method: z.string(), path: z.string() }),
  matched:        z.array(z.object({ ruleId: z.string(), actionType: z.string() })),
  evaluated:      z.number().int().nonnegative(),
  skipped:        z.number().int().nonnegative(),
  latencyBucket:  LatencyBucketSchema,
  errorCount:     z.number().int().nonnegative(),
});
export type AnalyticsEvent = z.infer<typeof AnalyticsEventSchema>;

// ─── Instance shape (subset — health summary only for Phase 8) ───────────
export const InstanceHealthSummarySchema = z.object({
  total:   z.number().int(),
  healthy: z.number().int(),
  stale:   z.number().int(),
  down:    z.number().int(),
});

// ─── Rules summary ───────────────────────────────────────────────────────
export const RulesSummarySchema = z.object({
  total:          z.number().int(),
  enabled:        z.number().int(),
  disabled:       z.number().int(),
  pendingReviews: z.number().int(),
});

// ─── Phase 9: timeseries, histogram, sparkline, dormancy ──────────────────
// Schemas mirror klswitch-console/src/lib/zod/analytics.ts verbatim so
// the wire contract stays single-sourced at the JSON shape level.

export const TimeseriesPointSchema = z.object({
  tsStartMs: z.number().int(),
  decisions: z.number().int().nonnegative(),
  blocks:    z.number().int().nonnegative(),
});
export type TimeseriesPoint = z.infer<typeof TimeseriesPointSchema>;

export const TimeseriesResponseSchema = z.object({
  bucketMs: z.number().int().positive(),
  points:   z.array(TimeseriesPointSchema),
});
export type TimeseriesResponse = z.infer<typeof TimeseriesResponseSchema>;

export const LatencyHistogramSchema = z.object({
  lt_50us:  z.number().int().nonnegative(),
  lt_500us: z.number().int().nonnegative(),
  lt_5ms:   z.number().int().nonnegative(),
  ge_5ms:   z.number().int().nonnegative(),
  total:    z.number().int().nonnegative(),
});
export type LatencyHistogram = z.infer<typeof LatencyHistogramSchema>;

export const RuleSparklineSchema = z.object({
  ruleId:    z.string(),
  ruleName:  z.string(),
  points:    z.array(z.number().int().nonnegative()),
  total24h:  z.number().int().nonnegative(),
});
export type RuleSparkline = z.infer<typeof RuleSparklineSchema>;

export const DormancyEntrySchema = z.object({
  ruleId:      z.string(),
  ruleName:    z.string(),
  serviceId:   z.string(),
  envId:       z.string(),
  lastFiredAt: z.string().nullable(),
  daysSilent:  z.number().int().nullable(),
});
export type DormancyEntry = z.infer<typeof DormancyEntrySchema>;

// ─── Heartbeat — derived from analytics events ────────────────────────────
export const HeartbeatCellStateSchema = z.enum(["healthy", "stale", "missed"]);
export type HeartbeatCellState = z.infer<typeof HeartbeatCellStateSchema>;

export const HeartbeatCellSchema = z.object({
  tsStartMs: z.number().int(),
  state:     HeartbeatCellStateSchema,
});
export type HeartbeatCell = z.infer<typeof HeartbeatCellSchema>;

export const HeartbeatResponseSchema = z.object({
  bucketMs: z.number().int().positive(),
  cells:    z.array(HeartbeatCellSchema),
});
export type HeartbeatResponse = z.infer<typeof HeartbeatResponseSchema>;
