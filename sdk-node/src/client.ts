import { hostname as osHostname, networkInterfaces } from "node:os";

import {
  ANALYTICS_EVENT_TYPE,
  AnalyticsReporter,
  disabledAnalyticsStatus,
  latencyBucket,
  newEventId,
  type AnalyticsStatus,
  type ReporterTransport,
} from "./analytics.js";
import {
  DEFAULT_REFRESH_INTERVAL_MS,
  DEFAULT_REFRESH_TIMEOUT_MS,
  validateConfig,
  type KLSwitchConfig,
} from "./config.js";
import {
  ManifestReporter,
  disabledManifestStatus,
  type ManifestStatus,
} from "./manifest.js";
import { resolveInstanceId, type InstanceIdResolution } from "./instance-id.js";
import { noopLogger, type Logger } from "./logger.js";
import { PollingManager, type PollingStatus } from "./polling-manager.js";
import { readCachedRuleset, writeCachedRuleset } from "./rules-cache.js";
import type {
  AnalyticsControl,
  AnalyticsRecorder,
  ManifestControl,
  PollingControl,
} from "./collaborators.js";
import { rulesById } from "./schema-drift.js";
import {
  AnalyticsEvaluationObserver,
  CompositeEvaluationObserver,
  ManifestRecheckObserver,
  noopEvaluationObserver,
  type EvaluationObserver,
} from "./evaluation-observer.js";
import { buildHeaders } from "./request-headers.js";
import { combineStatus, type KLSwitchStatus } from "./status.js";
import type { EngineStatus, EvaluationInput, EvaluationResult, LoadRulesResult, RuleSetPayload } from "./types.js";
import { buildUrls, type ResolvedUrls } from "./url-builder.js";
import { WasmBridge } from "./wasm-bridge.js";

/** Pinned at build time so the user-agent reflects the shipped version. */
export const SDK_VERSION = "0.1.0";

/**
 * The public KLSwitch SDK client. One client owns one WASM engine instance and
 * all host-side concerns — polling, logging, analytics — which live outside WASM.
 *
 * Lifecycle: `init` → `evaluate` / `refresh` / `getStatus` → `close`.
 */
export class KLSwitch {
  private bridge: WasmBridge | null = null;
  private handle = 0;
  private logger: Logger = noopLogger;

  /**
   * The configured logger, so bundled adapters can report a misconfiguration
   * through the operator's own logging rather than staying silent or writing
   * to the console. Read-only: the logger is fixed at init.
   */
  get log(): Logger {
    return this.logger;
  }
  private polling: PollingControl | null = null;
  private pollingActive = false;
  private analytics: AnalyticsControl | null = null;
  private manifest: ManifestControl | null = null;
  /** Notified after every evaluation. Never null — the no-op default keeps the
   *  hot path branch-free. */
  private observer: EvaluationObserver = noopEvaluationObserver;
  /** One-shot guard for the first-evaluation manifest re-check. */

  /** Resolved at init; immutable for the process's lifetime. */
  private _instanceId: InstanceIdResolution | null = null;
  /** Resolved at init from baseUrl + overrides. */
  private _urls: ResolvedUrls | null = null;

  private activeRulesVersion: number | null = null;
  /** Active rules indexed by id — for schema-drift Layer 3 lookups in
   *  `evaluate()`. Rebuilt atomically with `activeRulesVersion` on every
   *  successful ruleset swap. */
  private rulesById: Map<string, import("./types.js").Rule> = new Map();
  /** Opt-in cache path from `config.cachePath`. null when caching is off. */
  private cachePath: string | null = null;
  private lastRefreshAt: number | null = null;
  private lastRefreshOk: boolean | null = null;
  /**
   * Rolling refresh-failure counter. Reserved for the future instance-health /
   * instance-health channel — not stamped onto decision events (those are
   * pure per-evaluation facts).
   */
  private pendingErrorCount = 0;

  private constructor() {}

  /**
   * Create and initialize a client: load the WASM engine, load inline rules
   * and/or fetch from the configured rules endpoint, and start polling if
   * configured. Fails clearly (releasing the engine) if no rules can be
   * loaded.
   */
  static async init(config: KLSwitchConfig): Promise<KLSwitch> {
    validateConfig(config);

    const client = new KLSwitch();
    let analyticsRecorder: AnalyticsRecorder | null = null;
    client.logger = config.logger ?? noopLogger;
    client._instanceId = resolveInstanceId(config.instanceId, process.env, client.logger);
    client._urls = buildUrls(config);
    client.cachePath = config.cachePath ?? null;
    client.logger.info("instance identity resolved", {
      instanceKey: client._instanceId.value,
      source:      client._instanceId.source,
    });

    client.bridge = await WasmBridge.load(config.wasmPath);
    client.logger.info("wasm engine loaded");
    client.handle = client.bridge.createEngine();

    // Any failure after the engine exists must release it (engine + timers).
    try {
      if (config.rules) {
        const result = client.applyRules(config.rules);
        if (!result.ok) {
          const first = result.errors[0];
          const detail = first ? `${first.code}: ${first.message}` : "no rules compiled";
          throw new Error(`KLSwitch init: initial rules failed to load (${detail}).`);
        }
      }

      // Remote rules are fetched whenever a token is present (the SDK's only
      // required field). The dev-only inline-rules escape hatch is the one
      // path where remote fetch is skipped.
      // Capture the narrowed token rather than re-reading `config.token` at each
      // use: TypeScript cannot narrow an optional field through an intermediate
      // boolean, and the alternative is a `!` assertion at every call site.
      const remoteToken =
        typeof config.token === "string" && config.token.length > 0 ? config.token : null;
      const remoteFetchConfigured = remoteToken !== null;
      if (remoteFetchConfigured) {
        const headers = buildHeaders(
          {
            token:        remoteToken,
            instanceKey:  client._instanceId.value,
            sdkVersion:   SDK_VERSION,
            extraHeaders: config.extraHeaders,
          },
          client.logger,
        );

        client.polling = new PollingManager(
          {
            rulesUrl:  client._urls.rules,
            headers,
            timeoutMs: config.timeoutMs ?? DEFAULT_REFRESH_TIMEOUT_MS,
            fetchImpl: config.fetchImpl,
          },
          client.logger,
          (payload) => client.onRulesFetched(payload),
          () => client.onRefreshFailed(),
        );
        await client.polling.pollOnce(); // initial fetch

        // Boot-time disk-cache fallback: the first fetch errored AND
        // the operator opted in to caching AND a previous good
        // ruleset is on disk → load it so we can serve traffic while
        // polling keeps retrying the real backend in the background.
        if (client.activeRulesVersion === null && client.cachePath !== null) {
          const cached = readCachedRuleset(client.cachePath, client.logger);
          if (cached !== null) {
            client.logger.info("ruleset boot-cache hit", { path: client.cachePath });
            client.applyRules(cached);
          }
        }

        const intervalMs = config.refreshInterval ?? DEFAULT_REFRESH_INTERVAL_MS;
        if (intervalMs > 0) {
          client.polling.start(intervalMs);
          client.pollingActive = true;
        }
      }

      if (client.activeRulesVersion === null) {
        throw new Error(
          "KLSwitch init: no rules available — provide inline `rules` (dev) " +
            "or a reachable backend (set `token`).",
        );
      }

      // Analytics setup is inside the try so a failure here still releases the
      // engine + polling timers via close(), instead of leaking them.
      //
      // When analytics is enabled AND a token is set, we wire the default
      // HTTP transport that ships gzipped batches to /v1/decisions. When
      // an operator passes `analytics.sink`, the reporter routes batches
      // there and skips HTTP entirely.
      if (config.analytics) {
        const transport: ReporterTransport | null = remoteFetchConfigured
          ? {
              url:         client._urls.decisions,
              headers:     buildHeaders(
                {
                  token:        remoteToken,
                  instanceKey:  client._instanceId.value,
                  sdkVersion:   SDK_VERSION,
                  extraHeaders: config.extraHeaders,
                },
                client.logger,
              ),
              sdkLang:     "node",
              sdkVersion:  SDK_VERSION,
              instanceKey: client._instanceId.value,
              fetchImpl:   config.fetchImpl,
            }
          : null;
        // One object, two roles: the client gets the lifecycle half, the observer
        // gets the recording half. Keeping the concrete reference here is what lets
        // each depend on only the interface it uses.
        const reporter = new AnalyticsReporter(config.analytics, transport, client.logger);
        client.analytics = reporter;
        analyticsRecorder = reporter;
      }

      // Manifest reporting — only when remote-fetch is configured AND the
      // operator passed a source (no source = no reporter, console falls
      // back to free-text). `reportManifest: false` is an explicit opt-out
      // even when a source was provided.
      if (
        remoteFetchConfigured
        && config.manifest
        && config.reportManifest !== false
      ) {
        const headers = buildHeaders(
          {
            token:        remoteToken,
            instanceKey:  client._instanceId.value,
            sdkVersion:   SDK_VERSION,
            extraHeaders: config.extraHeaders,
          },
          client.logger,
        );
        client.manifest = new ManifestReporter({
          url:         client._urls.manifest,
          headers,
          source:      config.manifest,
          instanceKey: client._instanceId.value,
          logger:      client.logger,
          fetchImpl:   config.fetchImpl,
          timeoutMs:   config.timeoutMs ?? DEFAULT_REFRESH_TIMEOUT_MS,
        });
        // Fire-and-forget the initial push, deferred by one macrotask.
        //
        // Routes are almost always registered *after* `await init()` returns —
        // that is the natural shape, since the manifest source needs the app
        // object, which needs to exist first. Collecting inline would therefore
        // walk an empty route table and sync nothing, and the operator would
        // have to know to call `reportManifest()` by hand afterwards.
        //
        // `setImmediate` runs after the current macrotask, so the synchronous
        // route registration following `init()` has completed. `unref()` keeps a
        // pending push from holding the process open; `report()` no-ops if the
        // client was closed in the meantime.
        //
        // A failure here is logged and never blocks the customer's app.
        setImmediate(() => {
          void client.manifest?.report();
        }).unref();
      }

      // Assemble the observers once, now that the reporters exist. Composing here
      // rather than branching inside evaluate() is what keeps the hot path free of
      // null checks and free of any knowledge of what telemetry is configured.
      const observers: EvaluationObserver[] = [];
      if (analyticsRecorder) observers.push(new AnalyticsEvaluationObserver(analyticsRecorder));
      if (client.manifest) observers.push(new ManifestRecheckObserver(client.manifest));
      if (observers.length === 1) {
        client.observer = observers[0]!;
      } else if (observers.length > 1) {
        client.observer = new CompositeEvaluationObserver(observers);
      }
    } catch (err) {
      client.close();
      throw err;
    }

    client.logger.info("client initialized", {
      rulesVersion: client.activeRulesVersion,
      instanceKey:  client._instanceId.value,
    });
    return client;
  }

  /** The resolved instance identifier. Stable for the process's lifetime. */
  get instanceId(): string {
    if (!this._instanceId) {
      throw new Error("KLSwitch: client is not initialized.");
    }
    return this._instanceId.value;
  }

  /** The resolved backend URLs (rules, decisions, manifest). */
  get urls(): ResolvedUrls {
    if (!this._urls) {
      throw new Error("KLSwitch: client is not initialized.");
    }
    return this._urls;
  }

  /** SDK version string, surfaced for adapters that want to log it. */
  get sdkVersion(): string {
    return SDK_VERSION;
  }

  /**
   * Re-run manifest discovery once, on the first evaluation.
   *
   * The initial push is deferred by a macrotask, which is enough when routes are
   * registered synchronously after `await init()` returns. It is not enough when
   * registration is itself asynchronous — NestJS's `await app.init()`, Fastify's
   * `await app.register(...)` — because those span ticks and finish after the
   * deferred push has already walked an empty route table.
   *
   * By the first request every route exists, so one re-check here closes that gap
   * without the operator having to know to call `reportManifest()` by hand. Matches
   * what the Python and Go wrappers do.
   *
   * Cheap: `report()` hashes the route list and skips the network when nothing has
   * changed, so the already-synced case pays only a discovery walk.
   */

  /**
   * Evaluate a request against the active ruleset.
   *
   * Evaluates, times the call, and tells the observer. It does not decide what is
   * worth reporting, build a decision event, walk condition trees, or push a route
   * manifest — all of which it used to do inline. Those are observers now, so this
   * method has one reason to change and telemetry can change without touching the
   * hot path.
   */
  evaluate(input: EvaluationInput): EvaluationResult {
    const bridge = this.activeBridge();
    const start = performance.now();
    // Inject the trusted clock the engine lacks (for effective-window rules).
    // Don't mutate the caller's object; respect a caller-supplied `now`.
    const payload = input.now === undefined ? { ...input, now: Date.now() } : input;
    const result = bridge.evaluate(this.handle, JSON.stringify(payload));
    this.observer.onEvaluated({
      input,
      result,
      elapsedMs:    performance.now() - start,
      rulesVersion: this.activeRulesVersion,
      rulesById:    this.rulesById,
    });
    return result;
  }

  /**
   * Force an immediate fetch + reload from the backend. Requires that the
   * client was initialized with a `token` (i.e. remote fetch is configured).
   * Resolves even if the fetch failed (the active ruleset is kept) — check
   * `getStatus().lastRefreshOk` for the outcome.
   */
  async refresh(): Promise<void> {
    this.activeBridge();
    if (!this.polling) {
      throw new Error("KLSwitch refresh: no backend configured (init with a `token`).");
    }
    await this.polling.pollOnce();
  }

  /** Combined wrapper + engine status. Safe to call after `close`. */
  getStatus(): KLSwitchStatus {
    const wrapper = {
      ready: this.bridge !== null && this.handle !== 0,
      polling: this.pollingActive,
      lastRefreshAt: this.lastRefreshAt,
      lastRefreshOk: this.lastRefreshOk,
    };
    if (!wrapper.ready || !this.bridge) {
      return combineStatus(
        {
          initialized: false,
          schema_version: "1.0",
          active_rules_version: null,
          rule_count: 0,
          last_load_ok: false,
          last_error: null,
        },
        wrapper,
      );
    }
    return combineStatus(this.bridge.getStatus(this.handle), wrapper);
  }

  /** Stop polling/analytics and destroy the engine instance. Idempotent. */
  close(): void {
    this.polling?.stop();
    this.pollingActive = false;
    // forget here so close() can stay sync — same shape as analytics.close.
    // The reporter's internal stopped flag means in-flight callbacks won't
    // touch freed state.
    this.manifest?.stop();
    this.manifest = null;
    this.analytics?.close();
    this.analytics = null;
    if (this.bridge && this.handle !== 0) {
      this.bridge.destroyEngine(this.handle);
      this.logger.info("client closed");
    }
    this.handle = 0;
    this.bridge = null;
  }

  /** Load a ruleset into the engine, tracking the active version. Engine keeps last-known-good on failure. */
  private applyRules(payload: RuleSetPayload): LoadRulesResult {
    const result = this.activeBridge().loadRules(this.handle, JSON.stringify(payload));
    if (result.ok) {
      // Swap the wrapper-side rules cache atomically with activeRulesVersion
      // — Layer-3 lookups during the same tick must never see version N
      // alongside version N-1's rules map.
      this.activeRulesVersion = result.rules_version;
      this.rulesById = rulesById(payload.rules);
      this.logger.info("rules loaded", {
        rulesVersion: result.rules_version,
        ruleCount: result.compiled_rule_count,
        skipped: result.skipped_rule_count,
      });
    } else {
      this.logger.warn("rules load failed; keeping last known good", { errors: result.errors });
    }
    return result;
  }

  private onRulesFetched(payload: RuleSetPayload): void {
    // A poll may resolve after close(); ignore it rather than touch freed state.
    if (!this.bridge) return;
    const result = this.applyRules(payload);
    this.lastRefreshAt = Date.now();
    this.lastRefreshOk = result.ok;
    // Persist to the boot cache only when the engine actually accepted
    // the ruleset — we don't want to overwrite a known-good cache with
    // a broken refresh.
    if (result.ok && this.cachePath !== null) {
      writeCachedRuleset(this.cachePath, payload, this.logger);
    }
  }

  private onRefreshFailed(): void {
    if (!this.bridge) return;
    this.lastRefreshAt = Date.now();
    this.lastRefreshOk = false;
    // Reserved for the future instance-health channel — refresh
    // failures are an instance-operational signal, no longer stamped onto
    // decision events (which are now pure per-evaluation facts).
    this.pendingErrorCount++;
  }

  /** Snapshot the analytics reporter's status block (or a "disabled" block). */
  analyticsStatus(): AnalyticsStatus {
    return this.analytics ? this.analytics.status() : disabledAnalyticsStatus();
  }

  /** Snapshot the manifest reporter's status block (or a "disabled" block). */
  manifestStatus(): ManifestStatus {
    return this.manifest ? this.manifest.status() : disabledManifestStatus();
  }

  /**
   * Snapshot the polling loop's state: `lastEtag`, `nextPollAt` (ISO 8601),
   * and `currentIntervalMs` (server-driven when available, otherwise the
   * configured bootstrap interval). Returns a disabled snapshot when
   * polling isn't configured (inline-rules dev path).
   */
  pollingStatus(): PollingStatus {
    return this.polling
      ? this.polling.status()
      : { lastEtag: null, nextPollAt: null, currentIntervalMs: 0 };
  }

  /**
   * Re-run framework discovery and push the manifest if the route set
   * has changed since the last push. Backend dedups by hash so calling
   * this on a steady-state app is free. Useful after registering new
   * routes at runtime (dev hot-reload, dynamic plugins).
   *
   * Returns true on success or no-op (hash unchanged); false on push
   * failure (logged + counted on `manifestStatus()`).
   */
  async reportManifest(): Promise<boolean> {
    return this.manifest ? this.manifest.report() : false;
  }

  private activeBridge(): WasmBridge {
    if (!this.bridge || this.handle === 0) {
      throw new Error("KLSwitch: client is not initialized (or has been closed).");
    }
    return this.bridge;
  }
}


