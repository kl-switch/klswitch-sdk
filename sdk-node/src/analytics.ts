import { randomUUID } from "node:crypto";
import type { AnalyticsRecorder, AnalyticsControl } from "./collaborators.js";
import { gzipSync } from "node:zlib";

import type { Logger } from "./logger.js";
import type { FetchLike } from "./polling-manager.js";

/**
 * Wrapper-owned analytics.
 *
 * One event is recorded per `evaluate()` call (including no-match cases).
 * Events live in a bounded ring buffer; a single background flush pipeline
 * gzips + POSTs the batch to `/v1/decisions`. The hot path
 * (`record`) is allocation-light and never blocks.
 *
 * Overflow policy: drop-oldest. When the buffer fills (e.g. backend outage),
 * the worker keeps the most-recent activity rather than the historical
 * record — operators reach for the dashboard during incidents, not last
 * week's events.
 *
 * Failure policy: drop-batch, no retry. A failed POST loses that batch;
 * the next flush picks up newer events. Retry-without-idempotency would
 * create duplicates if the original landed but the response was lost.
 *
 * Custom sink: pass `opts.sink` to bypass the HTTP POST entirely (useful
 * for piping events into the operator's own analytics pipeline). Sink
 * receives a list of events; SDK-side ring/flush plumbing still applies.
 */

export const LATENCY_BUCKETS = ["lt_50us", "lt_500us", "lt_5ms", "ge_5ms"] as const;
export type LatencyBucket = (typeof LATENCY_BUCKETS)[number];

/**
 * Discriminator for the polymorphic analytics ingest (`POST /v1/analytics`).
 * The backend dispatches on this; only `"evaluation"` exists today (future:
 * poll-failure / health events on the same endpoint).
 */
export const ANALYTICS_EVENT_TYPE = "evaluation" as const;
export type AnalyticsEventType = typeof ANALYTICS_EVENT_TYPE;

/**
 * Wire-format event shape. The backend derives `serviceId`, `env`, `orgId`,
 * `teamId` from the API key and `instanceId` from the `X-Instance-Key`
 * header before persisting — none of those are sent by the SDK.
 */
export interface AnalyticsEvent {
  /** Polymorphic discriminator — always `"evaluation"` for now. */
  type:          AnalyticsEventType;
  /**
   * Client-generated idempotency key (UUID v4), minted once when the event
   * is recorded and stable for its lifetime. The backend enforces
   * `UNIQUE(eventId)` so a redelivered batch (proxy/LB retry) collapses to a
   * single stored decision instead of a duplicate.
   */
  eventId:       string;
  /** ISO 8601 UTC timestamp when the evaluation completed. */
  occurredAt:    string;
  /** Active ruleset version at evaluation time. */
  rulesVersion:  number;
  /** Templated request shape — `{ method: "POST", path: "/v1/payments/:id" }`. */
  request:       { method: string; path: string };
  /** One entry per matched rule. Empty when no rule matched. */
  matched:       Array<{ ruleId: string; actionType: string }>;
  /** Candidate rules whose condition ran. */
  evaluated:     number;
  /** Disabled candidate rules skipped. */
  skipped:       number;
  /** Coarse latency bucket for the engine eval. */
  latencyBucket: LatencyBucket;
  /**
   * Schema-drift Layer 3: deduplicated, sorted list of field paths that
   * matched rules referenced but the request input didn't carry.
   * Omitted (empty) when no matched rule referenced a missing field;
   * always present on the wire so console queries can filter on
   * `missingFields IS NOT NULL` without a special-case.
   */
  missingFields: string[];
}

/**
 * Optional escape hatch — receives drained batches instead of the SDK
 * shipping them itself. When set, no HTTP POST is performed.
 */
export type AnalyticsSink = (events: AnalyticsEvent[]) => void | Promise<void>;

export interface AnalyticsOptions {
  /** Custom sink. When set, bypasses the HTTP POST entirely. */
  sink?: AnalyticsSink;
  /** Flush when the queue reaches this many events (default 100). */
  batchSize?: number;
  /** Periodic flush interval in ms (default 5000). */
  flushIntervalMs?: number;
  /** Max queued events before the oldest are dropped (default 10000). */
  maxQueueSize?: number;
  /** Per-request timeout for the POST when the default HTTP sink is used (default 5000 ms). */
  postTimeoutMs?: number;
}

/** Counters exposed via `client.getStatus().analytics`. */
export interface AnalyticsStatus {
  enabled:             boolean;
  bufferSize:          number;
  bufferCapacity:      number;
  eventsRecorded:      number;
  eventsDropped:       number;
  eventsSent:          number;
  batchesFailed:       number;
  lastFlushAt:         string | null;
  lastFlushOk:         boolean | null;
  lastFlushError:      string | null;
  consecutiveFailures: number;
}

/**
 * Mint a fresh idempotency key for an analytics event. UUID v4; generated at
 * event birth so it stays stable if the batch is ever redelivered.
 */
export function newEventId(): string {
  return randomUUID();
}

/** Map an elapsed-ms duration to the cross-language bucket enum. */
export function latencyBucket(ms: number): LatencyBucket {
  if (ms < 0.05) return "lt_50us";
  if (ms < 0.5)  return "lt_500us";
  if (ms < 5)    return "lt_5ms";
  return "ge_5ms";
}

const DEFAULT_BATCH_SIZE       = 100;
const DEFAULT_FLUSH_INTERVAL_MS = 5_000;
const DEFAULT_MAX_QUEUE_SIZE    = 10_000;
const DEFAULT_POST_TIMEOUT_MS   = 5_000;
const MAX_BACKOFF_MS            = 5 * 60_000;

/**
 * Constructor options for the HTTP shipping path. When `sink` is set on
 * `AnalyticsOptions`, the URL/headers/fetch can be omitted — the sink
 * receives batches directly.
 */
export interface ReporterTransport {
  url:         string;
  headers:     Record<string, string>;
  sdkLang:     "node";
  sdkVersion:  string;
  instanceKey: string;
  fetchImpl?:  FetchLike;
}

export class AnalyticsReporter implements AnalyticsRecorder, AnalyticsControl {
  /** Ring buffer head/tail indices over a fixed-size events array. */
  private buffer: (AnalyticsEvent | undefined)[];
  private head = 0;
  private tail = 0;
  private size = 0;
  private flushing = false;
  /** The promise that backs the in-flight flush, so concurrent flush() calls await it. */
  private flushingPromise: Promise<void> | null = null;
  private stopped = false;
  private consecutiveFailures = 0;
  private readonly batchSize: number;
  private readonly maxQueueSize: number;
  private readonly postTimeoutMs: number;
  private readonly timer: ReturnType<typeof setInterval>;

  // Lifetime counters.
  private eventsRecorded = 0;
  private eventsDropped  = 0;
  private eventsSent     = 0;
  private batchesFailed  = 0;
  private lastFlushAt:    string | null = null;
  private lastFlushOk:    boolean | null = null;
  private lastFlushError: string | null = null;

  constructor(
    private readonly opts: AnalyticsOptions,
    private readonly transport: ReporterTransport | null,
    private readonly logger: Logger,
  ) {
    this.batchSize     = opts.batchSize       ?? DEFAULT_BATCH_SIZE;
    this.maxQueueSize  = opts.maxQueueSize    ?? DEFAULT_MAX_QUEUE_SIZE;
    this.postTimeoutMs = opts.postTimeoutMs   ?? DEFAULT_POST_TIMEOUT_MS;
    this.buffer        = new Array(this.maxQueueSize);
    const interval     = opts.flushIntervalMs ?? DEFAULT_FLUSH_INTERVAL_MS;
    this.timer         = setInterval(() => void this.flush(), interval);
    this.timer.unref?.();
  }

  /**
   * Enqueue an event. Synchronous and non-blocking. On overflow, evicts
   * the oldest event in the buffer (drop-oldest) and bumps the dropped
   * counter — the buffer retains the most-recent N events.
   */
  record(event: AnalyticsEvent): void {
    if (this.stopped) return;
    this.eventsRecorded++;
    if (this.size === this.maxQueueSize) {
      // Drop oldest: advance head, decrement size, then push tail.
      this.head = (this.head + 1) % this.maxQueueSize;
      this.size--;
      this.eventsDropped++;
    }
    this.buffer[this.tail] = event;
    this.tail = (this.tail + 1) % this.maxQueueSize;
    this.size++;

    if (this.size >= this.batchSize) {
      void this.flush();
    }
  }

  /**
   * Drain ONE batch (up to batchSize) and ship it. Single-flight: a
   * concurrent call returns immediately and the next scheduled flush
   * picks up where this one left off.
   *
   * Never throws — failures bump counters and the batch is dropped.
   */
  async flush(): Promise<void> {
    // Single-flight: a concurrent call awaits the in-flight one rather than
    // returning early. Without this, an auto-flush triggered by `record()`
    // would make a parallel `await flush()` return before the actual work
    // completes — which breaks tests + graceful close semantics.
    if (this.flushingPromise) return this.flushingPromise;
    if (this.stopped)          return;
    if (this.size === 0)       return;
    this.flushing = true;
    this.flushingPromise = this.doFlush().finally(() => {
      this.flushing = false;
      this.flushingPromise = null;
    });
    return this.flushingPromise;
  }

  private async doFlush(): Promise<void> {
    const batch = this.drain(this.batchSize);
    if (batch.length === 0) return;
    const ok = await this.ship(batch);
    const now = new Date().toISOString();
    this.lastFlushAt = now;
    this.lastFlushOk = ok;
    if (ok) {
      this.eventsSent += batch.length;
      this.consecutiveFailures = 0;
      this.lastFlushError = null;
    } else {
      this.batchesFailed++;
      this.consecutiveFailures++;
    }
  }

  /** Force a single-batch flush; returns when complete. Mostly for tests + graceful close. */
  async flushNow(): Promise<void> {
    await this.flush();
  }

  /** Stop the timer. Best-effort final flush (non-blocking). */
  close(): void {
    if (this.stopped) return;
    this.stopped = true;
    clearInterval(this.timer);
    // Fire-and-forget — caller doesn't await close().
    void this.flush();
  }

  /** Snapshot of the reporter's health. Surfaced via `client.getStatus().analytics`. */
  status(): AnalyticsStatus {
    return {
      enabled:             true,
      bufferSize:          this.size,
      bufferCapacity:      this.maxQueueSize,
      eventsRecorded:      this.eventsRecorded,
      eventsDropped:       this.eventsDropped,
      eventsSent:          this.eventsSent,
      batchesFailed:       this.batchesFailed,
      lastFlushAt:         this.lastFlushAt,
      lastFlushOk:         this.lastFlushOk,
      lastFlushError:      this.lastFlushError,
      consecutiveFailures: this.consecutiveFailures,
    };
  }

  // ── internals ──────────────────────────────────────────────────────────

  private drain(max: number): AnalyticsEvent[] {
    const count = Math.min(max, this.size);
    const out: AnalyticsEvent[] = new Array(count);
    for (let i = 0; i < count; i++) {
      out[i] = this.buffer[this.head] as AnalyticsEvent;
      this.buffer[this.head] = undefined;     // help GC
      this.head = (this.head + 1) % this.maxQueueSize;
    }
    this.size -= count;
    return out;
  }

  /** Returns true on 2xx; false on any failure (sink throw, non-2xx, timeout). */
  private async ship(batch: AnalyticsEvent[]): Promise<boolean> {
    // Operator-supplied sink wins — bypass HTTP entirely.
    if (this.opts.sink) {
      try {
        await this.opts.sink(batch);
        return true;
      } catch (err) {
        this.lastFlushError = String(err);
        this.logger.warn("analytics sink threw; dropping batch", {
          error:   String(err),
          dropped: batch.length,
        });
        return false;
      }
    }
    if (!this.transport) {
      // No HTTP transport configured AND no sink → no destination. Drop.
      this.lastFlushError = "no analytics transport configured";
      return false;
    }
    return this.shipHttp(batch, this.transport);
  }

  private async shipHttp(batch: AnalyticsEvent[], t: ReporterTransport): Promise<boolean> {
    const doFetch = t.fetchImpl ?? (globalThis.fetch as unknown as FetchLike);
    if (!doFetch) {
      this.lastFlushError = "no fetch implementation available";
      return false;
    }

    const envelope = {
      sdkLang:     t.sdkLang,
      sdkVersion:  t.sdkVersion,
      instanceKey: t.instanceKey,
      events:      batch,
    };
    const json = JSON.stringify(envelope);
    let body:  string | Buffer = json;
    const headers: Record<string, string> = {
      ...t.headers,
      "Content-Type": "application/json",
    };
    try {
      body = gzipSync(Buffer.from(json));
      headers["Content-Encoding"] = "gzip";
    } catch {
      // gzip is best-effort — fall through to uncompressed JSON.
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.postTimeoutMs);
    try {
      // Node's fetch accepts Buffer/Uint8Array for body; the FetchLike type
      // narrows to string for compatibility with test mocks. Cast through
      // unknown to keep the public type small.
      const response = await doFetch(t.url, {
        method:  "POST",
        headers,
        body:    body as unknown as string,
        signal:  controller.signal,
      });
      if (!response.ok) {
        this.lastFlushError = `analytics POST returned status ${response.status}`;
        this.logger.warn("analytics POST rejected", {
          url:     t.url,
          status:  response.status,
          dropped: batch.length,
        });
        return false;
      }
      return true;
    } catch (err) {
      this.lastFlushError = String(err);
      this.logger.warn("analytics POST failed; dropping batch", {
        url:                 t.url,
        error:               String(err),
        dropped:             batch.length,
        consecutiveFailures: this.consecutiveFailures + 1,
      });
      return false;
    } finally {
      clearTimeout(timeout);
    }
  }
}

/** Helper for callers that want a "no analytics" status block. */
export function disabledAnalyticsStatus(): AnalyticsStatus {
  return {
    enabled:             false,
    bufferSize:          0,
    bufferCapacity:      0,
    eventsRecorded:      0,
    eventsDropped:       0,
    eventsSent:          0,
    batchesFailed:       0,
    lastFlushAt:         null,
    lastFlushOk:         null,
    lastFlushError:      null,
    consecutiveFailures: 0,
  };
}

// Re-exported as constants for callers that want to reference the defaults.
export {
  DEFAULT_BATCH_SIZE,
  DEFAULT_FLUSH_INTERVAL_MS,
  DEFAULT_MAX_QUEUE_SIZE,
  DEFAULT_POST_TIMEOUT_MS,
  MAX_BACKOFF_MS as ANALYTICS_MAX_BACKOFF_MS,
};
