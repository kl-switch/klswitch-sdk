package com.klswitch;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wrapper-owned polling of the remote rules API — entirely outside WASM. It
 * fetches a ruleset payload and hands it to {@code onFetched}; the client loads
 * it into the engine. A fetch failure is logged and surfaced via {@code onError}
 * and never disturbs the active ruleset (the engine keeps last-known-good).
 *
 * <p>{@link #pollOnce} is {@code synchronized} so a manual refresh and the
 * recurring poll never overlap — a slow fetch cannot apply an older version
 * after a newer one. The recurring poll runs on a daemon thread that does not
 * block JVM exit.
 *
 * <h2>v1.1 server-driven cadence</h2>
 *
 * <p>{@link #start(long)} now schedules each tick from the last response's
 * {@code Cache-Control: max-age} (clamped to {@link #MIN_POLL_MS} /
 * {@link #MAX_POLL_MS}), falling back to the supplied {@code intervalMs} when
 * the server doesn't supply a hint. The schedule chain is {@code schedule()},
 * not {@code scheduleAtFixedRate()} — each tick reschedules itself from the
 * effective interval after running.
 */
public final class PollingManager implements AutoCloseable, Collaborators.PollingControl {

    /** Floor on the server-driven poll cadence. */
    public static final long MIN_POLL_MS = 5_000L;
    /** Ceiling on the server-driven poll cadence. */
    public static final long MAX_POLL_MS = 3_600_000L;
    /**
     * Hard ceiling on the exponential-backoff delay after consecutive fetch
     * failures (per B2 acceptance criteria). Effective cap is
     * {@code max(MAX_BACKOFF_MS, currentIntervalMs)} so backoff never makes
     * the wrapper poll more frequently than the success cadence (e.g. a
     * 5-min server cadence keeps a 5-min retry, not 60 s).
     */
    public static final long MAX_BACKOFF_MS = 5L * 60L * 1000L;
    /** Caps the doubling exponent so a flapping server can't push delays
     *  into nonsense territory. {@code 2^6 = 64x}. */
    private static final int  MAX_BACKOFF_EXPONENT = 6;

    private final RulesFetcher fetcher;
    private final Logger logger;
    private final Consumer<String> onFetched;
    private final Consumer<Throwable> onError;

    private ScheduledExecutorService scheduler;
    private volatile boolean stopped = false;
    private volatile long bootstrapIntervalMs = 0L;
    private final AtomicLong currentIntervalMs = new AtomicLong(0L);
    private final AtomicLong nextPollAtMs = new AtomicLong(0L);
    private final AtomicReference<String> lastEtag = new AtomicReference<>();
    /** Bumped on every consecutive failure; reset on the next success.
     *  Drives the exponential backoff schedule. */
    private final AtomicLong consecutiveFailures = new AtomicLong(0L);

    public PollingManager(
            RulesFetcher fetcher, Logger logger, Consumer<String> onFetched, Consumer<Throwable> onError) {
        this.fetcher = fetcher;
        this.logger = logger;
        this.onFetched = onFetched;
        this.onError = onError;
    }

    /** Fetch the ruleset once and hand it to {@code onFetched}; report failures to {@code onError}.
     *  Updates the server-driven cadence + cached ETag from the response (when present). */
    public synchronized void pollOnce() {
        try {
            RulesFetcher.FetchResult result = fetcher.fetchWithMeta();
            result.etag().ifPresent(lastEtag::set);
            result.nextDelayMs().ifPresent(this::applyServerDelay);
            consecutiveFailures.set(0L);
            Optional<String> body = result.body();
            if (body.isPresent()) {
                onFetched.accept(body.get());
            }
            // Empty body → server confirmed no change (HEAD match OR 304 GET);
            // schedule keeps ticking, just no engine work to do.
        } catch (Throwable t) {
            long fails = consecutiveFailures.incrementAndGet();
            logger.warn("rules refresh failed; keeping last known good", Map.of(
                    "error", String.valueOf(t),
                    "consecutiveFailures", fails));
            onError.accept(t);
        }
    }

    /** Start recurring polling on a daemon thread. The first wake fires at
     *  {@code intervalMs}; subsequent wakes use whatever the most recent
     *  server response asked for (falling back to {@code intervalMs}). */
    public void start(long intervalMs) {
        if (scheduler != null || stopped) {
            return;
        }
        bootstrapIntervalMs = intervalMs;
        currentIntervalMs.compareAndSet(0L, clamp(intervalMs));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "klswitch-poll");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
    }

    /** Snapshot of the polling state for {@code Client.getStatus()}. */
    public PollingStatus status() {
        long next = nextPollAtMs.get();
        long current = currentIntervalMs.get();
        if (current == 0L) current = bootstrapIntervalMs;
        return new PollingStatus(
                lastEtag.get(),
                next == 0L ? null : java.time.Instant.ofEpochMilli(next).toString(),
                current);
    }

    @Override
    public void close() {
        stopped = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        nextPollAtMs.set(0L);
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void scheduleNext() {
        if (stopped || scheduler == null) return;
        long delay = nextDelayMs();
        nextPollAtMs.set(System.currentTimeMillis() + delay);
        scheduler.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }

    /** Compute the next-wake delay. On success it's {@code currentIntervalMs}
     *  (server-driven or bootstrap). On consecutive failures it's
     *  {@code currentInterval × 2^n} with ±25 % jitter, capped at
     *  {@code max(MAX_BACKOFF_MS, currentInterval)} so a large server
     *  cadence never gets retried *more* aggressively than its success
     *  schedule. */
    long nextDelayMs() {
        long base = currentIntervalMs.get();
        if (base <= 0L) base = bootstrapIntervalMs > 0 ? bootstrapIntervalMs : 30_000L;
        long fails = consecutiveFailures.get();
        if (fails <= 0L) return base;
        int exp = (int) Math.min(fails, MAX_BACKOFF_EXPONENT);
        long cap = Math.max(MAX_BACKOFF_MS, base);
        long grown = Math.min(base * (1L << exp), cap);
        // ±25 % jitter — break thundering-herd retries across instances.
        double offset = (ThreadLocalRandom.current().nextDouble() - 0.5) * (grown / 2.0);
        long delay = grown + (long) offset;
        return Math.max(0L, delay);
    }

    /** Each tick runs one poll, then reschedules from the (possibly updated)
     *  cadence. We don't use {@code scheduleAtFixedRate} because the interval
     *  changes between ticks. */
    private void tick() {
        try {
            pollOnce();
        } finally {
            if (!stopped) scheduleNext();
        }
    }

    private void applyServerDelay(long maxAgeMs) {
        currentIntervalMs.set(clamp(maxAgeMs));
    }

    private static long clamp(long ms) {
        if (ms <= 0L) return MIN_POLL_MS;
        return Math.max(MIN_POLL_MS, Math.min(MAX_POLL_MS, ms));
    }

    /** Snapshot of the poll manager's last-seen ETag + next scheduled fire. */
    public record PollingStatus(String lastEtag, String nextPollAt, long currentIntervalMs) {}
}
