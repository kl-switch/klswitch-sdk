import type { AnalyticsRecorder, ManifestControl } from "./collaborators.js";
import { ANALYTICS_EVENT_TYPE, latencyBucket, newEventId } from "./analytics.js";
import { computeMissingFields } from "./schema-drift.js";
import type { EvaluationInput, EvaluationResult, Rule } from "./types.js";

/**
 * What an observer is told after each evaluation.
 *
 * Everything an observer could want, gathered once by the caller, so no observer
 * has to reach back into the client for it. `rulesById` is passed by reference —
 * observers must not mutate it.
 */
export interface EvaluationContext {
  readonly input: EvaluationInput;
  readonly result: EvaluationResult;
  readonly elapsedMs: number;
  readonly rulesVersion: number | null;
  readonly rulesById: Map<string, Rule>;
}

/**
 * Notified after every evaluation, so that `evaluate()` can evaluate.
 *
 * Before this existed, `evaluate()` also re-checked the manifest, timed itself,
 * walked condition trees for missing fields and assembled an analytics event —
 * four reasons for one method to change, three of which were telemetry riding on
 * the hot path. They are observers now; the hot path notifies and returns.
 *
 * Implementations must not throw and must not block: they run inline, on the
 * caller's thread, between the engine returning and the caller getting its result.
 */
export interface EvaluationObserver {
  onEvaluated(ctx: EvaluationContext): void;
}

/** Does nothing. The default, so the hot path never branches on null. */
export const noopEvaluationObserver: EvaluationObserver = {
  onEvaluated(): void {
    /* nothing to observe */
  },
};

/**
 * Builds a decision event and hands it to the reporter.
 *
 * Owns the rule about *which* evaluations are reportable: only a successful one
 * against a known ruleset version. An `ok:false` result is a caller or lifecycle
 * error (INVALID_INPUT / INVALID_HANDLE — the WASM boundary returns these rather
 * than throwing), not a decision, and reporting it would put non-decisions in the
 * decision stream. That judgement is telemetry's, which is why it lives here and
 * no longer in `evaluate()`.
 */
export class AnalyticsEvaluationObserver implements EvaluationObserver {
  constructor(private readonly reporter: AnalyticsRecorder) {}

  onEvaluated(ctx: EvaluationContext): void {
    if (ctx.rulesVersion === null || !ctx.result.ok) return;

    // Schema-drift Layer 3: walk each matched rule's condition tree against the
    // input. Computed here, inline, so a slow walk (a large rule with a deep
    // tree) is paid by this caller rather than the analytics flush thread.
    const missingFields = computeMissingFields(ctx.result.matched, ctx.rulesById, ctx.input);

    // record() only enqueues — it must never block the evaluate path.
    this.reporter.record({
      type:          ANALYTICS_EVENT_TYPE,
      eventId:       newEventId(),
      occurredAt:    new Date().toISOString(),
      rulesVersion:  ctx.rulesVersion,
      request:       extractRequest(ctx.input),
      matched:       ctx.result.matched.map((m) => ({
        ruleId:     m.id,
        actionType: m.action.type,
      })),
      evaluated:     ctx.result.evaluated,
      skipped:       ctx.result.skipped,
      latencyBucket: latencyBucket(ctx.elapsedMs),
      missingFields,
    });
  }
}

/**
 * Pushes the route manifest once, on the first evaluation.
 *
 * This cannot move to `init()`, which is where it belongs on the face of it. The
 * initial push is deferred by a macrotask, which is enough when routes are
 * registered synchronously after `await init()` returns, and not enough when
 * registration is itself asynchronous — NestJS's `await app.init()`, Fastify's
 * `await app.register(...)` — because those span ticks and finish after the
 * deferred push has already walked an empty route table.
 *
 * By the first request every route exists. So the trigger stays where it was; what
 * changed is that `evaluate()` no longer knows about it. Cheap: `report()` hashes
 * the route list and skips the network when nothing has changed.
 */
export class ManifestRecheckObserver implements EvaluationObserver {
  private done = false;

  constructor(private readonly reporter: ManifestControl) {}

  onEvaluated(): void {
    if (this.done) return;
    this.done = true;
    void this.reporter.report();
  }
}

/** Fans out to several observers. One failing must not stop the others. */
export class CompositeEvaluationObserver implements EvaluationObserver {
  constructor(private readonly observers: readonly EvaluationObserver[]) {}

  onEvaluated(ctx: EvaluationContext): void {
    for (const o of this.observers) o.onEvaluated(ctx);
  }
}

/**
 * Pull the templated request shape out of an EvaluationInput's selector.
 * Falls back to safe defaults for non-API selectors (post-v1 kinds) so
 * the analytics event always satisfies the console schema.
 */
function extractRequest(input: EvaluationInput): { method: string; path: string } {
  const keys = (input.selector?.keys ?? {}) as Record<string, unknown>;
  const method = typeof keys.method === "string" ? keys.method.toUpperCase() : "UNKNOWN";
  const path   = typeof keys.path   === "string" ? keys.path                  : "";
  return { method, path };
}
