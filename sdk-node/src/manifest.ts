import { createHash } from "node:crypto";
import type { ManifestControl } from "./collaborators.js";

import type { Logger } from "./logger.js";
import type { FetchLike } from "./polling-manager.js";

/**
 * API manifest reporting.
 *
 * Each SDK instance, at boot, asks its host framework "what routes do
 * you have registered?" via a {@link ManifestSource} the operator
 * configures, then POSTs the discovered route list to
 * `POST /v1/manifest`. The backend uses this as the live truth of
 * what's running, powering:
 *
 *  - the route picker on `/rules/new` (autocomplete from real routes)
 *  - field autocomplete in the condition editor
 *  - "stale route" warnings on the rules list
 *  - schema-drift Layer 4 (always-on detection)
 *
 * Push policy:
 *   - One push shortly after init, deferred by a macrotask. Routes are
 *     almost always registered *after* `await init()` returns — the
 *     manifest source needs the app object, which has to exist first — so
 *     collecting inline would walk an empty route table and sync nothing.
 *   - One re-check on the first evaluation. Asynchronous registration —
 *     NestJS's `await app.init()`, Fastify's `await app.register(...)` —
 *     spans ticks and finishes after the deferred push has run, so the
 *     deferral alone would miss those routes. By the first request they
 *     all exist. Hash-deduped, so the already-synced case costs only a
 *     discovery walk.
 *   - Re-push on demand (`client.reportManifest()`) for hot-reload setups
 *     that add routes at runtime, after traffic has already started.
 *
 * Failure policy: log and carry on; never disturb the customer's startup.
 * A manifest push failing is an operator-visibility regression, not an
 * incident.
 *
 * Opt-out: `config.reportManifest: false` skips the reporter entirely.
 * Frameworks without a discoverable route registry (raw http server,
 * opaque middleware chains) simply don't register a source — the
 * console falls back to free-text route entry with no regression.
 */

/** One discovered field on a route — best-effort, only when the framework supplies it. */
export interface ManifestField {
  name: string;
  type: string;
}

/** One route discovered from the framework's registry. */
export interface ManifestRoute {
  /** Always uppercase to match the rule selector. */
  method:  string;
  /** Templated path, e.g. `/v1/payments/:id`. */
  path:    string;
  /** Best-effort handler identifier (class.method, function name, etc.). */
  handler?: string;
  /** Best-effort field shape. Absent / empty when the framework can't supply it. */
  fields?: {
    body?:    ManifestField[];
    headers?: ManifestField[];
    params?:  ManifestField[];
    query?:   ManifestField[];
  };
}

/** Wire-format payload. Frozen contract — matches `docs/schema-drift-detection.md`. */
export interface ManifestPayload {
  reportedAt:  string;     // ISO 8601 UTC
  instanceKey: string;
  framework:   string;
  hash:        string;     // sha256 of routes (stable order, hex)
  routes:      ManifestRoute[];
}

/**
 * What an operator passes to the SDK. Each framework adapter ships its
 * own helper (e.g. `fastifyManifestSource(app)`) that returns a source.
 */
export interface ManifestSource {
  framework: string;
  /** Returns the current route list. Called at init and on `recheck()`. */
  collect:   () => ManifestRoute[] | Promise<ManifestRoute[]>;
}

export interface ManifestReporterOptions {
  url:         string;
  headers:     Record<string, string>;
  source:      ManifestSource;
  instanceKey: string;
  logger:      Logger;
  /** Per-request timeout for the POST. Defaults to 5_000ms. */
  timeoutMs?:  number;
  /** Inject for tests. */
  fetchImpl?:  FetchLike;
}

/** Snapshot of the reporter's health, exposed via `client.manifestStatus()`. */
export interface ManifestStatus {
  enabled:        boolean;
  framework:      string | null;
  lastReportedAt: string | null;
  lastHash:       string | null;
  lastPushOk:     boolean | null;
  lastPushError:  string | null;
  routeCount:     number;
  pushAttempts:   number;
  pushesSucceeded: number;
  pushesFailed:   number;
}

export const DEFAULT_MANIFEST_TIMEOUT_MS = 5_000;

export class ManifestReporter implements ManifestControl {
  private lastHash: string | null = null;
  private lastReportedAt: string | null = null;
  private lastPushOk: boolean | null = null;
  private lastPushError: string | null = null;
  private routeCount = 0;
  private pushAttempts = 0;
  private pushesSucceeded = 0;
  private pushesFailed = 0;
  private stopped = false;

  constructor(private readonly opts: ManifestReporterOptions) {}

  /**
   * Run discovery once and push if the hash has changed (or there's no
   * prior hash). Safe to call multiple times — backend dedups by hash
   * so a steady-state registry → no traffic. Returns true on success
   * (push landed OR no-op because hash unchanged); false on push failure.
   */
  async report(): Promise<boolean> {
    if (this.stopped) return false;
    let routes: ManifestRoute[];
    try {
      routes = await this.opts.source.collect();
    } catch (err) {
      this.lastPushError = `manifest source threw: ${String(err)}`;
      this.opts.logger.warn("manifest source threw; skipping report", {
        framework: this.opts.source.framework,
        error:     String(err),
      });
      this.pushAttempts++;
      this.pushesFailed++;
      return false;
    }
    const normalised = normaliseRoutes(routes);
    const hash = computeHash(normalised);
    this.routeCount = normalised.length;

    if (this.lastHash === hash) {
      // No change since last push — backend already has it, no-op.
      return true;
    }

    this.pushAttempts++;
    const ok = await this.push(normalised, hash);
    if (ok) {
      this.lastHash = hash;
      this.lastReportedAt = new Date().toISOString();
      this.lastPushOk = true;
      this.lastPushError = null;
      this.pushesSucceeded++;
    } else {
      this.lastPushOk = false;
      this.pushesFailed++;
    }
    return ok;
  }

  /** Mark the reporter stopped. After this, `report()` is a no-op. Idempotent. */
  stop(): void {
    this.stopped = true;
  }

  /** Snapshot for `client.manifestStatus()`. */
  status(): ManifestStatus {
    return {
      enabled:         true,
      framework:       this.opts.source.framework,
      lastReportedAt:  this.lastReportedAt,
      lastHash:        this.lastHash,
      lastPushOk:      this.lastPushOk,
      lastPushError:   this.lastPushError,
      routeCount:      this.routeCount,
      pushAttempts:    this.pushAttempts,
      pushesSucceeded: this.pushesSucceeded,
      pushesFailed:    this.pushesFailed,
    };
  }

  // ── internals ──────────────────────────────────────────────────────────

  private async push(routes: ManifestRoute[], hash: string): Promise<boolean> {
    const doFetch = this.opts.fetchImpl ?? (globalThis.fetch as unknown as FetchLike);
    if (!doFetch) {
      this.lastPushError = "no fetch implementation available";
      return false;
    }

    const payload: ManifestPayload = {
      reportedAt:  new Date().toISOString(),
      instanceKey: this.opts.instanceKey,
      framework:   this.opts.source.framework,
      hash,
      routes,
    };

    const controller = new AbortController();
    const timeoutMs = this.opts.timeoutMs ?? DEFAULT_MANIFEST_TIMEOUT_MS;
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await doFetch(this.opts.url, {
        method:  "POST",
        headers: { ...this.opts.headers, "Content-Type": "application/json" },
        body:    JSON.stringify(payload),
        signal:  controller.signal,
      });
      if (!response.ok) {
        this.lastPushError = `manifest POST returned status ${response.status}`;
        this.opts.logger.warn("manifest POST rejected", {
          url:        this.opts.url,
          status:     response.status,
          routeCount: routes.length,
        });
        return false;
      }
      this.opts.logger.info("manifest pushed", {
        framework:  this.opts.source.framework,
        routeCount: routes.length,
        hash,
      });
      return true;
    } catch (err) {
      this.lastPushError = String(err);
      this.opts.logger.warn("manifest POST failed", {
        url:   this.opts.url,
        error: String(err),
      });
      return false;
    } finally {
      clearTimeout(timeout);
    }
  }
}

/** "Disabled" status block — returned when no manifest source is configured. */
export function disabledManifestStatus(): ManifestStatus {
  return {
    enabled:         false,
    framework:       null,
    lastReportedAt:  null,
    lastHash:        null,
    lastPushOk:      null,
    lastPushError:   null,
    routeCount:      0,
    pushAttempts:    0,
    pushesSucceeded: 0,
    pushesFailed:    0,
  };
}

/**
 * Stable hash over the route list — sorts by `${method} ${path}` first so
 * the same registry produces the same hash regardless of registration
 * order, then sha256s the canonical JSON.
 */
function computeHash(routes: ManifestRoute[]): string {
  // Already normalised on the way in.
  return createHash("sha256")
    .update(JSON.stringify(routes))
    .digest("hex");
}

/**
 * `{id}` and `{id:int}` → `:id`. The engine speaks one dialect.
 *
 * <p>Applied here rather than in each adapter because the adapters disagreed: Spring and FastAPI
 * converted, gorilla/mux and the hand-written sources did not, and five frameworks published
 * `/api/items/{id}` while eight published `/api/items/:id`. A rule written from the console's
 * route picker then matched nothing, because evaluation normalises to the colon form.
 *
 * <p>Central means a custom {@link ManifestSource} cannot get it wrong either — the escape hatch
 * for frameworks with no route registry is exactly where someone types a path by hand.
 * Idempotent, so an adapter that already converts is unaffected. A converter spec is dropped:
 * `{id:int}` is a routing constraint, not part of the path.
 */
export function canonicalisePath(path: string): string {
  if (!path.includes("{")) return path;
  return path.replace(/\{([^:{}]+)(?::[^{}]+)?\}/g, (_m, name: string) => `:${name}`);
}

function normaliseRoutes(routes: ManifestRoute[]): ManifestRoute[] {
  // Upper-case the method, fill defaults, sort for hash stability.
  return routes
    .map((r) => ({
      method:  r.method.toUpperCase(),
      path:    canonicalisePath(r.path),
      ...(r.handler ? { handler: r.handler } : {}),
      ...(r.fields  ? { fields:  r.fields  } : {}),
    }))
    .sort((a, b) => {
      const ka = `${a.method} ${a.path}`;
      const kb = `${b.method} ${b.path}`;
      return ka < kb ? -1 : ka > kb ? 1 : 0;
    });
}
