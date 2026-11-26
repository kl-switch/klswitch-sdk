import type { Logger } from "./logger.js";
import type { PollingControl } from "./collaborators.js";
import type { RuleSetPayload } from "./types.js";

/**
 * A `fetch`-compatible function; injectable for tests / custom transports.
 *
 * `text()` is optional so existing mocks keep compiling; when present it's used
 * to enrich non-2xx error messages with the response body (capped to 4096 chars).
 * `headers.get()` is also optional — used to read
 * the server-driven cadence headers (`ETag` / `Cache-Control`).
 */
export type FetchLike = (
  url: string,
  init?: {
    method?:  string;
    headers?: Record<string, string>;
    body?:    string;
    signal?:  AbortSignal;
  },
) => Promise<{
  ok:     boolean;
  status: number;
  json(): Promise<unknown>;
  text?(): Promise<string>;
  headers?: { get(name: string): string | null };
}>;

const ERROR_BODY_SNIPPET_CHARS = 4096;

/** Floor on the server-driven poll cadence. Stops a server bug returning
 *  `max-age=0` from turning every SDK pod into a tight DDoS loop. */
export const MIN_POLL_MS = 5_000;
/** Ceiling on the server-driven poll cadence. Keeps a misconfigured server
 *  from silently parking pods for days at a time. */
export const MAX_POLL_MS = 3_600_000;

/** Hard ceiling on the exponential-backoff delay after consecutive fetch
 *  failures. Per B2 acceptance criteria. Effective cap is
 *  `max(MAX_BACKOFF_MS, currentInterval)` so backoff never makes the
 *  wrapper poll *more* frequently than the success cadence. */
export const MAX_BACKOFF_MS = 5 * 60 * 1000;
/** 2^MAX_BACKOFF_EXPONENT * currentInterval is the unclamped peak — caps
 *  the multiplier so consecutive failures don't overflow into nonsense. */
const MAX_BACKOFF_EXPONENT = 6;

export interface PollingOptions {
  /** Pre-built rules URL (caller resolves baseUrl + overrides). */
  rulesUrl: string;
  /** Pre-built headers (caller injects auth + instance-key + user-agent). */
  headers: Record<string, string>;
  timeoutMs?: number;
  /** Override the fetch implementation (defaults to global `fetch`). */
  fetchImpl?: FetchLike;
}

/** Observability snapshot for {@link PollingManager.status}. */
export interface PollingStatus {
  /** Last seen `ETag` header value (quoted form). `null` until first response. */
  lastEtag: string | null;
  /** Next scheduled wake-up time as ISO 8601 UTC; `null` when not started. */
  nextPollAt: string | null;
  /** Current effective poll interval in ms (server-driven if available). */
  currentIntervalMs: number;
}

/**
 * Wrapper-owned polling of the remote rules API. Lives entirely outside WASM:
 * it fetches a ruleset payload and hands it to `onFetched`; the client loads it
 * into the engine. A fetch failure is logged and surfaced via `onError` — it
 * never disturbs the active ruleset (the engine keeps last-known-good).
 *
 * **v1.1 server-driven polling.** The very first poll is a plain `GET`
 * (no cached ETag yet); subsequent polls are `HEAD` probes that compare
 * the server's `ETag` against the last one we saw — only when it flips
 * do we issue a `GET` (with `If-None-Match` for race safety, server
 * may reply `304`). Every response's `Cache-Control: max-age` retunes
 * the next-wake delay (clamped to `[MIN_POLL_MS, MAX_POLL_MS]`).
 *
 * If the backend omits `ETag` / `Cache-Control` (older mock, self-hosted)
 * the manager gracefully degrades to plain `GET` every `intervalMs`.
 *
 * The URL + headers are resolved by the caller (see `url-builder.ts` and
 * `request-headers.ts`) so this module is purely transport.
 */
export class PollingManager implements PollingControl {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private inflight: Promise<void> | null = null;

  private lastEtag: string | null = null;
  /** The bootstrap interval the caller passed to `start()`. Used as the
   *  fallback when the server doesn't supply `Cache-Control: max-age`. */
  private bootstrapIntervalMs: number | null = null;
  /** Currently effective interval. Updated from each response's max-age. */
  private currentIntervalMs: number | null = null;
  private nextPollAtMs: number | null = null;
  /** Set by `stop()` so an in-flight `pollOnce` doesn't reschedule itself. */
  private stopped = false;
  /** Consecutive fetch failures since the last success. Drives the B2
   *  exponential-backoff schedule; reset to 0 on every successful poll. */
  private consecutiveFailures = 0;

  constructor(
    private readonly opts: PollingOptions,
    private readonly logger: Logger,
    private readonly onFetched: (payload: RuleSetPayload) => void,
    private readonly onError: (error: unknown) => void,
  ) {}

  /**
   * Fetch the ruleset once and hand it to `onFetched`; report failures to
   * `onError`. Coalesced: if a poll is already in flight the same promise is
   * returned, so concurrent polls (timer + manual `refresh`) never overlap and
   * a slow fetch cannot apply an older version after a newer one.
   *
   * Calling this explicitly (e.g. user-triggered refresh) does NOT reset the
   * next-wake timer — the next scheduled fire still happens. The schedule IS
   * updated from the response's `max-age` so a manual refresh that surfaces a
   * tier change takes effect from the next tick.
   */
  pollOnce(): Promise<void> {
    if (this.inflight) return this.inflight;
    this.inflight = this.doPoll().finally(() => {
      this.inflight = null;
    });
    return this.inflight;
  }

  private async doPoll(): Promise<void> {
    try {
      const outcome = await this.fetchRules();
      // outcome is null when the HEAD probe showed nothing changed (or the
      // GET race produced a 304). Either way no engine work to do, but the
      // response's Cache-Control may still have retuned `currentIntervalMs`.
      this.consecutiveFailures = 0;
      if (outcome !== null) this.onFetched(outcome);
    } catch (error) {
      this.consecutiveFailures++;
      this.logger.warn("rules refresh failed; keeping last known good", {
        url: this.opts.rulesUrl,
        error: String(error),
        consecutiveFailures: this.consecutiveFailures,
      });
      this.onError(error);
    }
  }

  /**
   * Start the recurring poll loop. `intervalMs` is the bootstrap cadence
   * used until the first server response sets `currentIntervalMs` (via
   * `Cache-Control: max-age`). The timer is unref'd so it never blocks
   * Node from exiting.
   */
  start(intervalMs: number): void {
    if (this.timer || this.stopped) return;
    this.bootstrapIntervalMs = intervalMs;
    if (this.currentIntervalMs === null) {
      this.currentIntervalMs = clampInterval(intervalMs);
    }
    this.scheduleNext();
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.nextPollAtMs = null;
  }

  status(): PollingStatus {
    return {
      lastEtag:          this.lastEtag,
      nextPollAt:        this.nextPollAtMs !== null
        ? new Date(this.nextPollAtMs).toISOString()
        : null,
      currentIntervalMs: this.currentIntervalMs ?? this.bootstrapIntervalMs ?? 0,
    };
  }

  /** Schedules the next wake from `nextDelayMs`. Idempotent: a pending
   *  timer is cleared before re-scheduling so server-driven retuning takes
   *  effect on the next tick rather than the one after. */
  private scheduleNext(): void {
    if (this.stopped) return;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const delayMs = this.nextDelayMs();
    this.nextPollAtMs = Date.now() + delayMs;
    this.timer = setTimeout(() => {
      void this.pollOnce().finally(() => {
        if (!this.stopped) this.scheduleNext();
      });
    }, delayMs);
    this.timer.unref?.();
  }

  /** Compute the next-wake delay. On success it's `currentIntervalMs`
   *  (server-driven via Cache-Control, or the bootstrap default). On
   *  consecutive failures it's `currentInterval * 2^n` with ±25 % jitter,
   *  capped at `max(MAX_BACKOFF_MS, currentInterval)` — so a 5-minute
   *  server cadence never gets retried *more* often than the success
   *  schedule. */
  private nextDelayMs(): number {
    const base = this.currentIntervalMs ?? this.bootstrapIntervalMs ?? 30_000;
    if (this.consecutiveFailures <= 0) return base;
    const exp = Math.min(this.consecutiveFailures, MAX_BACKOFF_EXPONENT);
    const cap = Math.max(MAX_BACKOFF_MS, base);
    const grown = Math.min(base * (1 << exp), cap);
    // ±25 % jitter: spread retries across instances to avoid synchronised
    // thundering-herd waves against a recovering upstream.
    const jitter = (Math.random() - 0.5) * (grown / 2);
    const delay = grown + jitter;
    return Math.max(0, Math.floor(delay));
  }

  // ── Transport ─────────────────────────────────────────────────────────────

  /**
   * The HEAD-then-GET dance. Returns:
   *   - a parsed RuleSetPayload when a fresh GET succeeded
   *   - null when the server confirmed no change (HEAD ETag match OR 304 GET)
   * Throws on transport / non-2xx error — caller routes that to `onError`.
   */
  private async fetchRules(): Promise<RuleSetPayload | null> {
    const doFetch = this.opts.fetchImpl ?? (globalThis.fetch as unknown as FetchLike);
    if (!doFetch) {
      throw new Error("no fetch implementation available (provide config.fetchImpl on older runtimes)");
    }

    // Cold start: no cached ETag → straight to GET.
    if (this.lastEtag === null) {
      return await this.doGet(doFetch, /*conditional=*/ false);
    }

    // Warm cache: HEAD first to detect change cheaply.
    const headResponse = await this.doFetch(doFetch, "HEAD");
    if (!headResponse.ok) {
      await this.failFromBody(headResponse, "HEAD");
      // unreachable — failFromBody throws — but TS doesn't know that:
      return null;
    }
    this.applyCacheControl(headResponse);
    const headEtag = readEtag(headResponse);
    if (headEtag !== null && headEtag === this.lastEtag) {
      // Backend confirms we have the current version. No GET, no apply.
      return null;
    }
    // ETag flipped (or server omitted it — fall back to a real GET so we
    // don't silently miss a change). The conditional If-None-Match is a
    // race-safety net: if the version flipped back between HEAD and GET
    // the server replies 304 and we drop the result.
    return await this.doGet(doFetch, /*conditional=*/ true);
  }

  private async doGet(doFetch: FetchLike, conditional: boolean): Promise<RuleSetPayload | null> {
    const requestHeaders: Record<string, string> = { ...this.opts.headers };
    if (conditional && this.lastEtag !== null) {
      requestHeaders["If-None-Match"] = this.lastEtag;
    }
    const response = await this.doFetch(doFetch, "GET", requestHeaders);
    this.applyCacheControl(response);

    if (response.status === 304) {
      // Race: ETag flipped between HEAD and GET, then flipped back. Or the
      // server reconsidered. Either way: we already have the current version.
      return null;
    }
    if (!response.ok) {
      await this.failFromBody(response, "GET");
      return null; // unreachable
    }
    const etag = readEtag(response);
    if (etag !== null) this.lastEtag = etag;
    return (await response.json()) as RuleSetPayload;
  }

  /** Issue a single HEAD or GET with the configured timeout + headers. */
  private async doFetch(
    doFetch: FetchLike,
    method: "HEAD" | "GET",
    extraHeaders?: Record<string, string>,
  ) {
    const controller = new AbortController();
    const timeout =
      this.opts.timeoutMs !== undefined
        ? setTimeout(() => controller.abort(), this.opts.timeoutMs)
        : undefined;
    try {
      return await doFetch(this.opts.rulesUrl, {
        method,
        headers: extraHeaders ?? this.opts.headers,
        signal:  controller.signal,
      });
    } finally {
      if (timeout !== undefined) clearTimeout(timeout);
    }
  }



  /** Parses `Cache-Control: max-age=N` (and tolerates extra directives like
   *  `public, max-age=30`). Updates `currentIntervalMs` clamped to bounds. */
  private applyCacheControl(response: { headers?: { get(name: string): string | null } }): void {
    if (!response.headers) return;
    const cc = response.headers.get("Cache-Control") ?? response.headers.get("cache-control");
    if (!cc) return;
    const maxAge = parseMaxAge(cc);
    if (maxAge === null) return;
    this.currentIntervalMs = clampInterval(maxAge * 1000);
  }

  /** Build a status-coded error with a body snippet (if available). Throws. */
  private async failFromBody(
    response: { status: number; text?(): Promise<string> },
    method: string,
  ): Promise<never> {
    let snippet = "";
    if (typeof response.text === "function") {
      try {
        const body = await response.text();
        snippet = body.length > ERROR_BODY_SNIPPET_CHARS
          ? `${body.slice(0, ERROR_BODY_SNIPPET_CHARS)}…`
          : body;
      } catch {
        /* body unreadable — fall back to status only */
      }
    }
    throw new Error(
      `rules API ${method} responded with status ${response.status}` +
      (snippet ? `: ${snippet}` : ""),
    );
  }
}

/** Normalise the server-driven `max-age` into a sane wrapper-side bound. */
function clampInterval(ms: number): number {
  if (!Number.isFinite(ms) || ms <= 0) return MIN_POLL_MS;
  return Math.max(MIN_POLL_MS, Math.min(MAX_POLL_MS, Math.floor(ms)));
}

function readEtag(response: { headers?: { get(name: string): string | null } }): string | null {
  if (!response.headers) return null;
  const raw = response.headers.get("ETag") ?? response.headers.get("etag");
  return raw && raw.length > 0 ? raw : null;
}

/** Returns `N` from `Cache-Control: ... max-age=N ...`, or null if absent. */
function parseMaxAge(headerValue: string): number | null {
  for (const directive of headerValue.split(",")) {
    const trimmed = directive.trim();
    if (trimmed.toLowerCase().startsWith("max-age")) {
      const eq = trimmed.indexOf("=");
      if (eq < 0) continue;
      const n = Number(trimmed.slice(eq + 1).trim());
      if (Number.isFinite(n) && n >= 0) return n;
    }
  }
  return null;
}
