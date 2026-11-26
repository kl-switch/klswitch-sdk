package com.klswitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Wrapper-owned analytics.
 *
 * One event is recorded per {@code evaluate()} call (including no-match
 * cases). Events live in a bounded ring buffer; a single daemon thread
 * flushes batches as gzipped POSTs to {@code /v1/decisions}.
 *
 * <p>Overflow policy: drop-oldest. When the buffer fills (backend outage,
 * etc.), the reporter keeps the most-recent activity — operators reach
 * for the dashboard during incidents, not last week's events.
 *
 * <p>Failure policy: drop-batch, no retry. A failed POST loses that
 * batch; the next flush picks up newer events. Retry without an
 * idempotency key would create duplicates if the original landed but
 * the response was lost.
 *
 * <p>Custom sink: {@link Transport#sink} (when non-null) bypasses the
 * HTTP POST entirely — useful for piping into the operator's own
 * analytics pipeline. SDK-side ring/flush plumbing still applies.
 */
public final class AnalyticsReporter implements AutoCloseable, Collaborators.AnalyticsRecorder, Collaborators.AnalyticsControl {

    /** Default cap on per-batch event count. */
    public static final int  DEFAULT_BATCH_SIZE       = 100;
    /** Default periodic flush interval. */
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 5_000L;
    /** Default ring-buffer capacity. */
    public static final int  DEFAULT_MAX_QUEUE_SIZE   = 10_000;
    /** Default per-request timeout for the analytics POST. */
    public static final long DEFAULT_POST_TIMEOUT_MS  = 5_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Transport describes where flushed batches go. Either {@code sink}
     * (custom in-process consumer) OR {@code url}+headers (default HTTP
     * POST) — the constructor enforces "at least one of."
     */
    public record Transport(
            String url,                  // null when sink is used
            Map<String, String> headers, // null when sink is used
            String sdkLang,              // envelope field
            String sdkVersion,           // envelope field
            String instanceKey,          // envelope field
            AnalyticsSink sink,          // null when HTTP is used
            HttpClient httpClient,       // null → build fresh
            long postTimeoutMs) {
        public static Transport http(String url, Map<String, String> headers,
                                     String sdkVersion, String instanceKey,
                                     long postTimeoutMs) {
            return new Transport(url, headers, "java", sdkVersion, instanceKey,
                    null, null, postTimeoutMs);
        }
        public static Transport sink(AnalyticsSink sink) {
            return new Transport(null, null, "java", "0", "i",
                    Objects.requireNonNull(sink, "sink"), null, DEFAULT_POST_TIMEOUT_MS);
        }
    }

    private final Transport transport;
    private final HttpClient httpClient;
    private final int batchSize;
    private final int maxQueueSize;
    private final long postTimeoutMs;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;

    /**
     * Ring buffer over a fixed-size array. {@code head} reads, {@code tail}
     * writes; {@code size} is the canonical count (cheap modulo arithmetic).
     */
    private final AnalyticsEvent[] buffer;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final Object bufferLock = new Object();

    // Single-flight latch. Access serialised via the scheduler thread.
    private volatile boolean stopped = false;

    // Counters — written only on the scheduler thread (flush) or guarded.
    private long eventsRecorded = 0;
    private long eventsDropped  = 0;
    private long eventsSent     = 0;
    private long batchesFailed  = 0;
    private String  lastFlushAt    = null;
    private Boolean lastFlushOk    = null;
    private String  lastFlushError = null;
    private int     consecutiveFailures = 0;

    public AnalyticsReporter(Transport transport, int batchSize, long flushIntervalMs,
                             int maxQueueSize, long postTimeoutMs, Logger logger) {
        this.transport     = Objects.requireNonNull(transport, "transport");
        this.batchSize     = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.maxQueueSize  = maxQueueSize > 0 ? maxQueueSize : DEFAULT_MAX_QUEUE_SIZE;
        this.postTimeoutMs = postTimeoutMs > 0 ? postTimeoutMs : DEFAULT_POST_TIMEOUT_MS;
        this.logger        = logger != null ? logger : LoggerAdapter.NOOP;
        this.buffer        = new AnalyticsEvent[this.maxQueueSize];
        this.httpClient = transport.httpClient != null
                ? transport.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.postTimeoutMs)).build();
        long interval = flushIntervalMs > 0 ? flushIntervalMs : DEFAULT_FLUSH_INTERVAL_MS;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "klswitch-analytics");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::flush, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Enqueue an event. Synchronous and non-blocking. On overflow, evicts
     * the oldest event and bumps the dropped counter (drop-oldest policy).
     */
    public void record(AnalyticsEvent event) {
        if (stopped) return;
        synchronized (bufferLock) {
            eventsRecorded++;
            if (size == maxQueueSize) {
                // Drop oldest: advance head, decrement size, then write tail.
                head = (head + 1) % maxQueueSize;
                size--;
                eventsDropped++;
            }
            buffer[tail] = event;
            tail = (tail + 1) % maxQueueSize;
            size++;
            if (size >= batchSize) {
                try {
                    scheduler.execute(this::flush);
                } catch (RejectedExecutionException ignored) {
                    // closed; final flush in close() handles the remainder
                }
            }
        }
    }

    /** Drain ONE batch (up to batchSize) and ship it. Runs on the scheduler thread. */
    public void flush() {
        if (stopped) return;
        List<AnalyticsEvent> batch;
        synchronized (bufferLock) {
            if (size == 0) return;
            batch = drainLocked(batchSize);
        }
        boolean ok = ship(batch);
        lastFlushAt = Instant.now().toString();
        lastFlushOk = ok;
        if (ok) {
            eventsSent += batch.size();
            consecutiveFailures = 0;
            lastFlushError = null;
        } else {
            batchesFailed++;
            consecutiveFailures++;
        }
    }

    /** Best-effort final flush; returns when complete. Synchronous (mostly for tests). */
    public void flushNow() {
        flush();
    }

    @Override
    public void close() {
        if (stopped) return;
        stopped = true;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // One last drain on the closing thread (best-effort, ignores stopped flag).
        List<AnalyticsEvent> remaining;
        synchronized (bufferLock) {
            remaining = drainLocked(maxQueueSize);
        }
        if (!remaining.isEmpty()) {
            ship(remaining);
        }
    }

    public AnalyticsStatus status() {
        synchronized (bufferLock) {
            return new AnalyticsStatus(
                    true,
                    size,
                    maxQueueSize,
                    eventsRecorded,
                    eventsDropped,
                    eventsSent,
                    batchesFailed,
                    lastFlushAt,
                    lastFlushOk,
                    lastFlushError,
                    consecutiveFailures);
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Caller must hold {@link #bufferLock}. */
    private List<AnalyticsEvent> drainLocked(int max) {
        int count = Math.min(max, size);
        List<AnalyticsEvent> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(buffer[head]);
            buffer[head] = null;          // help GC
            head = (head + 1) % maxQueueSize;
        }
        size -= count;
        return out;
    }

    /** Returns true on 2xx; false on any failure (sink throw, non-2xx, network, timeout). */
    private boolean ship(List<AnalyticsEvent> batch) {
        if (transport.sink != null) {
            try {
                transport.sink.accept(batch);
                return true;
            } catch (Throwable t) {
                lastFlushError = String.valueOf(t);
                logger.warn("analytics sink threw; dropping batch",
                        Map.of("error", String.valueOf(t), "dropped", batch.size()));
                return false;
            }
        }
        if (transport.url == null) {
            lastFlushError = "no analytics transport configured";
            return false;
        }
        return shipHttp(batch);
    }

    private boolean shipHttp(List<AnalyticsEvent> batch) {
        Map<String, Object> envelope = Map.of(
                "sdkLang",     transport.sdkLang,
                "sdkVersion",  transport.sdkVersion,
                "instanceKey", transport.instanceKey,
                "events",      batch);
        byte[] body;
        boolean gzipped;
        try {
            byte[] json = MAPPER.writeValueAsBytes(envelope);
            body = gzip(json);
            gzipped = true;
        } catch (Exception e) {
            lastFlushError = "serialise failed: " + e;
            logger.warn("analytics serialise failed", Map.of("error", String.valueOf(e)));
            return false;
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(transport.url))
                .timeout(Duration.ofMillis(postTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (gzipped) {
            request.header("Content-Encoding", "gzip");
        }
        Optional.ofNullable(transport.headers).orElse(Map.of()).forEach(request::header);

        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                lastFlushError = "analytics POST returned status " + response.statusCode();
                logger.warn("analytics POST rejected", Map.of(
                        "url",     transport.url,
                        "status",  response.statusCode(),
                        "dropped", batch.size()));
                return false;
            }
            return true;
        } catch (Exception e) {
            lastFlushError = String.valueOf(e);
            logger.warn("analytics POST failed; dropping batch", Map.of(
                    "url",                 transport.url,
                    "error",               String.valueOf(e),
                    "dropped",             batch.size(),
                    "consecutiveFailures", consecutiveFailures + 1));
            return false;
        }
    }

    private static byte[] gzip(byte[] in) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(in);
        }
        return baos.toByteArray();
    }

    /** Map an elapsed-ms duration to the cross-language bucket enum. */
    public static LatencyBucket latencyBucket(double ms) {
        return LatencyBucket.forMillis(ms);
    }
}
