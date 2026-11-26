import type { AnalyticsOptions } from "./analytics.js";
import type { Logger } from "./logger.js";
import type { ManifestSource } from "./manifest.js";
import type { FetchLike } from "./polling-manager.js";
import type { RuleSetPayload } from "./types.js";
import { assertNotLegacyShape } from "./config-legacy.js";

/**
 * Default per-request timeout for backend calls. Without a default, a wedged
 * backend would hang polling indefinitely.
 */
export const DEFAULT_REFRESH_TIMEOUT_MS = 5000;

/** Default poll interval when polling is enabled. */
export const DEFAULT_REFRESH_INTERVAL_MS = 30_000;

/**
 * Wrapper configuration.
 *
 * The `token` is the only required field. It is service-scoped + env-scoped
 * at issue time; the backend derives the service identity and environment
 * from the token, so neither needs to be repeated in the SDK config.
 *
 * `instanceId` is optional — falls back through KS_INSTANCE_ID, POD_NAME,
 * and a deterministic `${hostname}-${pid}-${startMs}` derivation. See
 * `instance-id.ts`.
 *
 * `baseUrl` defaults to the hosted KLSwitch backend; the per-endpoint
 * overrides (`rulesUrl`, `decisionsUrl`) are escape hatches
 * for self-hosted setups.
 *
 * `rules` is a dev-only inline ruleset that bypasses the backend entirely;
 * useful for tests and local examples.
 */
export interface KLSwitchConfig {
  /**
   * Service-scoped bearer token. Required to fetch rules from the backend.
   *
   * Optional only because an inline `rules` payload bypasses the backend
   * entirely (dev / tests): a config with neither a token nor inline rules is
   * rejected by {@link validateConfig}. The type has to allow that shape,
   * otherwise the documented inline-rules mode fails to typecheck.
   */
  token?: string;

  /** Override the auto-derived instance identity. See `instance-id.ts`. */
  instanceId?: string;

  /** Base URL of the KLSwitch backend (default https://api.klswitch.dev). */
  baseUrl?: string;
  /** Override the rules endpoint URL (advanced / self-hosted). */
  rulesUrl?: string;
  /** Override the decisions endpoint URL (advanced / self-hosted). */
  decisionsUrl?: string;
  /** Override the manifest endpoint URL (advanced / self-hosted). */
  manifestUrl?: string;

  /** Inline ruleset (dev-only). Bypasses the backend if `token` is absent. */
  rules?: RuleSetPayload;

  /** Poll interval in ms. Default 30s when polling. Pass 0 to disable polling. */
  refreshInterval?: number;
  /** Per-request timeout in ms for rules fetch. */
  timeoutMs?: number;
  /** Additional headers sent on every backend call. Authorization is set by the SDK. */
  extraHeaders?: Record<string, string>;
  /** Override the path to the .wasm artifact (defaults to the bundled engine). */
  wasmPath?: string;
  /**
   * Opt-in disk cache for the last-known-good ruleset. When set, every
   * successful remote fetch persists the accepted ruleset to this path.
   * On init, if the first fetch fails AND the file exists, the SDK loads
   * it and serves traffic while polling keeps retrying the backend in the
   * background. Off by default.
   *
   * Use {@link defaultCachePath} for the conventional
   * `${tmpdir}/ks-cache-<env>.json` location.
   */
  cachePath?: string;
  /** Logger for lifecycle/refresh events. Defaults to a silent logger. */
  logger?: Logger;
  /** Enable analytics by providing a sink (+ optional batching options). */
  analytics?: AnalyticsOptions;
  /**
   * API manifest reporting. Pass a source from a framework adapter
   * (e.g. `fastifyManifestSource(app)`) and the SDK will push the
   * discovered route list to the backend at init. Powers the console's
   * route picker + drift detection. Opt out by omitting (the default)
   * or by passing `reportManifest: false`.
   */
  manifest?: ManifestSource;
  /** Explicit opt-out: when false, no manifest reporter is constructed even if `manifest` is set. */
  reportManifest?: boolean;
  /** Override the fetch implementation (tests / custom transports). */
  fetchImpl?: FetchLike;
}

/**
 * Validate config shape. Throws on clearly-invalid combinations. The
 * `instanceId` value is validated separately in `instance-id.ts` (a bad
 * value falls through rather than crashing).
 */
export function validateConfig(config: KLSwitchConfig): void {
  // Migration handling is deliberately not here — see config-legacy.ts for why.
  assertNotLegacyShape(config);

  if (!config.rules) {
    // Without inline rules, we need a token to fetch them from the backend.
    if (typeof config.token !== "string" || config.token.trim().length === 0) {
      throw new Error("KLSwitch config: `token` is required.");
    }
  }
  if (config.refreshInterval !== undefined) {
    if (config.refreshInterval < 0) {
      throw new Error(
        "KLSwitch config: refreshInterval must be >= 0 (pass 0 to disable polling).",
      );
    }
  }
  if (config.timeoutMs !== undefined && config.timeoutMs <= 0) {
    throw new Error("KLSwitch config: timeoutMs must be positive.");
  }
  if (config.analytics) {
    const { batchSize, flushIntervalMs } = config.analytics;
    if (batchSize !== undefined && batchSize <= 0) {
      throw new Error("KLSwitch config: analytics.batchSize must be positive.");
    }
    if (flushIntervalMs !== undefined && flushIntervalMs <= 0) {
      throw new Error("KLSwitch config: analytics.flushIntervalMs must be positive.");
    }
  }
}

