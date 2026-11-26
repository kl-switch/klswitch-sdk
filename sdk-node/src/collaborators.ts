import type { AnalyticsEvent, AnalyticsStatus } from "./analytics.js";
import type { ManifestStatus } from "./manifest.js";
import type { PollingStatus } from "./polling-manager.js";

/**
 * What the client needs from its collaborators — and nothing more.
 *
 * The client used to hold `PollingManager`, `AnalyticsReporter` and
 * `ManifestReporter` by their concrete types, which meant none of them could be
 * substituted in a test without the real thing: a real HTTP transport, a real
 * timer, a real route walk.
 *
 * These interfaces are deliberately narrow. The client calls three methods on
 * each at most, so that is what each interface declares. A single fat
 * `Reporter` type would force a fake to implement batching, flushing and
 * single-flight logic that the client never asks for.
 *
 * TypeScript is structural, so the concrete classes already satisfy these
 * without an `implements` clause; the clause is added anyway so that breaking
 * one is a compile error in the class rather than a surprise at the call site.
 */

/** The recording half of analytics — needed by the observer, not the client. */
export interface AnalyticsRecorder {
  record(event: AnalyticsEvent): void;
}

/**
 * The lifecycle half of analytics — needed by the client, not the observer.
 *
 * Split from {@link AnalyticsRecorder} on purpose: the observer records and
 * never closes, the client closes and never records. One interface carrying
 * both would hand each of them a method it has no business calling.
 */
export interface AnalyticsControl {
  status(): AnalyticsStatus;
  close(): void;
}

/** What the client needs from manifest reporting. */
export interface ManifestControl {
  report(): Promise<boolean>;
  status(): ManifestStatus;
  stop(): void;
}

/** What the client needs from the polling loop. */
export interface PollingControl {
  start(intervalMs: number): void;
  pollOnce(): Promise<void>;
  status(): PollingStatus;
  stop(): void;
}
