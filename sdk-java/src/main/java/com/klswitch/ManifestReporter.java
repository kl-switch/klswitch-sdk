package com.klswitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API manifest reporter.
 *
 * <p>At init each SDK instance asks its host framework "what routes do
 * you have registered?" via a {@link ManifestSource} the operator
 * configures, then POSTs the discovered route list to {@code /v1/manifest}.
 * The backend uses this as the live truth of what's running, powering:
 *
 * <ul>
 *   <li>the route picker on {@code /rules/new} (autocomplete from real routes)
 *   <li>field autocomplete in the condition editor
 *   <li>"stale route" warnings on the rules list
 *   <li>schema-drift Layer 4 (always-on detection)
 * </ul>
 *
 * <p>Push policy:
 * <ul>
 *   <li>One push at client init (fire-and-forget; never blocks startup).
 *   <li>On-demand re-push via {@code client.reportManifest()} — backend
 *       dedups by content hash, so calling this on a steady-state app
 *       is free.
 * </ul>
 *
 * <p>Failure policy: log + counter bump. A failed push is an
 * operator-visibility regression, not an incident.
 */
public final class ManifestReporter implements AutoCloseable, Collaborators.ManifestControl {

    /** Default per-request timeout. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pluggable HTTP seam — exposed so tests can mock the transport
     * without spinning up a server. Returns the HTTP status code; a
     * thrown exception is treated as a transport failure.
     */
    @FunctionalInterface
    public interface Sender {
        int send(String url, Map<String, String> headers, byte[] body, long timeoutMs) throws Exception;
    }

    private final String url;
    private final Map<String, String> headers;
    private final ManifestSource source;
    private final String instanceKey;
    private final long timeoutMs;
    private final Logger logger;
    private final Sender sender;

    // Status counters — read by status() concurrently with report().
    private final AtomicReference<String>  lastHash         = new AtomicReference<>();
    private final AtomicReference<String>  lastReportedAt   = new AtomicReference<>();
    private final AtomicReference<Boolean> lastPushOk       = new AtomicReference<>();
    private final AtomicReference<String>  lastPushError    = new AtomicReference<>();
    private final AtomicLong               routeCount       = new AtomicLong();
    private final AtomicLong               pushAttempts     = new AtomicLong();
    private final AtomicLong               pushesSucceeded  = new AtomicLong();
    private final AtomicLong               pushesFailed     = new AtomicLong();
    private final AtomicBoolean            stopped          = new AtomicBoolean(false);

    private ManifestReporter(Builder b) {
        this.url          = Objects.requireNonNull(b.url, "url");
        this.headers      = b.headers != null ? Map.copyOf(b.headers) : Map.of();
        this.source       = Objects.requireNonNull(b.source, "source");
        this.instanceKey  = Objects.requireNonNull(b.instanceKey, "instanceKey");
        this.timeoutMs    = b.timeoutMs > 0 ? b.timeoutMs : DEFAULT_TIMEOUT_MS;
        this.logger       = b.logger != null ? b.logger : LoggerAdapter.NOOP;
        this.sender       = b.sender != null ? b.sender : defaultSender();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Run discovery once and push if the hash has changed. Safe to call
     * repeatedly — backend dedups by hash, so a steady-state registry
     * produces no traffic. Returns true on success (push landed OR
     * no-op because the hash was unchanged); false on push failure.
     *
     * <p>Synchronous — operators that want fire-and-forget startup
     * behaviour should dispatch this on a daemon thread (Client does
     * exactly that for the initial push).
     */
    public boolean report() {
        if (stopped.get()) return false;

        List<ManifestRoute> raw;
        try {
            raw = source.collect();
        } catch (RuntimeException e) {
            pushAttempts.incrementAndGet();
            pushesFailed.incrementAndGet();
            lastPushError.set("manifest source threw: " + e);
            lastPushOk.set(Boolean.FALSE);
            logger.warn("manifest source threw; skipping report", Map.of(
                    "framework", source.framework(),
                    "error",     String.valueOf(e)));
            return false;
        }

        List<ManifestRoute> normalised = normalise(raw);
        routeCount.set(normalised.size());
        String hash;
        try {
            hash = hashRoutes(normalised);
        } catch (Exception e) {
            pushAttempts.incrementAndGet();
            pushesFailed.incrementAndGet();
            lastPushError.set("manifest hash failed: " + e);
            lastPushOk.set(Boolean.FALSE);
            return false;
        }

        if (Objects.equals(lastHash.get(), hash)) {
            // Backend already has this exact set — no-op.
            return true;
        }

        pushAttempts.incrementAndGet();
        boolean ok = push(normalised, hash);
        if (ok) {
            lastHash.set(hash);
            lastReportedAt.set(Instant.now().toString());
            lastPushOk.set(Boolean.TRUE);
            lastPushError.set(null);
            pushesSucceeded.incrementAndGet();
        } else {
            lastPushOk.set(Boolean.FALSE);
            pushesFailed.incrementAndGet();
        }
        return ok;
    }

    /** After this, {@link #report()} is a no-op. Idempotent. */
    @Override
    public void close() {
        stopped.set(true);
    }

    /** Mirror of {@code stop()} in the sibling wrappers — kept for symmetry. */
    public void stop() {
        close();
    }

    public ManifestStatus status() {
        return new ManifestStatus(
                true,
                source.framework(),
                lastReportedAt.get(),
                lastHash.get(),
                lastPushOk.get(),
                lastPushError.get(),
                (int) routeCount.get(),
                pushAttempts.get(),
                pushesSucceeded.get(),
                pushesFailed.get());
    }

    // ── internals ──────────────────────────────────────────────────────────

    private boolean push(List<ManifestRoute> routes, String hash) {
        Payload payload = new Payload(
                Instant.now().toString(), instanceKey, source.framework(), hash, routes);
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            lastPushError.set("manifest serialise failed: " + e);
            logger.warn("manifest serialise failed", Map.of("error", String.valueOf(e)));
            return false;
        }

        int status;
        try {
            status = sender.send(url, headers, body, timeoutMs);
        } catch (Exception e) {
            lastPushError.set(String.valueOf(e));
            logger.warn("manifest POST failed", Map.of(
                    "url",   url,
                    "error", String.valueOf(e)));
            return false;
        }
        if (status >= 400) {
            lastPushError.set("manifest POST returned status " + status);
            logger.warn("manifest POST rejected", Map.of(
                    "url",        url,
                    "status",     status,
                    "routeCount", routes.size()));
            return false;
        }
        logger.info("manifest pushed", Map.of(
                "framework",  source.framework(),
                "routeCount", routes.size(),
                "hash",       hash));
        return true;
    }

    /** Uppercase methods, drop nulls, sort by {@code "METHOD path"} for hash stability. */
    /** Matches {@code {id}} and {@code {id:int}} — the JAX-RS / Spring / mux dialect. */
    private static final java.util.regex.Pattern BRACE_PARAM =
            java.util.regex.Pattern.compile("\\{([^:{}]+)(?::[^{}]+)?\\}");

    /**
     * Rewrites {@code {id}} and {@code {id:int}} to {@code :id}.
     *
     * <p>The engine speaks one dialect. This lives in the reporter rather than in each adapter
     * because the adapters disagreed: Spring and FastAPI converted, gorilla/mux and the
     * hand-written sources did not, so five frameworks published {@code /api/items/{id}} while
     * eight published {@code /api/items/:id}. A rule written from the console's route picker
     * then matched nothing, because evaluation normalises to the colon form.
     *
     * <p>Central also means a custom {@code ManifestSource} cannot get it wrong — the escape
     * hatch for frameworks with no route registry is exactly where someone types a path by hand.
     * Idempotent; a converter spec like {@code {id:int}} is a routing constraint and is dropped.
     */
    static String canonicalisePath(String path) {
        if (path == null || path.indexOf('{') < 0) return path;
        return BRACE_PARAM.matcher(path).replaceAll(":$1");
    }

    static List<ManifestRoute> normalise(List<ManifestRoute> in) {
        List<ManifestRoute> out = new ArrayList<>(in.size());
        for (ManifestRoute r : in) {
            if (r == null || r.method() == null || r.path() == null) continue;
            String m = r.method().toUpperCase(java.util.Locale.ROOT);
            out.add(new ManifestRoute(m, canonicalisePath(r.path()), r.handler(), r.fields()));
        }
        out.sort(Comparator
                .comparing(ManifestRoute::method)
                .thenComparing(ManifestRoute::path));
        return out;
    }

    private static String hashRoutes(List<ManifestRoute> routes) throws Exception {
        // sha256 over the JSON-serialised normalised list. The hash is a
        // per-instance dedup key — the backend only compares it against the
        // last hash *this* instance sent, so cross-language byte-identity
        // isn't required; stability within one wrapper is.
        byte[] body = MAPPER.writeValueAsBytes(routes);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest(body);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static Sender defaultSender() {
        return (url, headers, body, timeoutMs) -> {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(req::header);
            HttpResponse<Void> resp = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        };
    }

    // ── builder + payload ──────────────────────────────────────────────────

    public static final class Builder {
        private String url;
        private Map<String, String> headers;
        private ManifestSource source;
        private String instanceKey;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private Logger logger;
        private Sender sender;

        public Builder url(String v)                  { this.url = v;          return this; }
        public Builder headers(Map<String, String> v) { this.headers = v;      return this; }
        public Builder source(ManifestSource v)       { this.source = v;       return this; }
        public Builder instanceKey(String v)          { this.instanceKey = v;  return this; }
        public Builder timeoutMs(long v)              { this.timeoutMs = v;    return this; }
        public Builder logger(Logger v)               { this.logger = v;       return this; }
        public Builder sender(Sender v)               { this.sender = v;       return this; }

        public ManifestReporter build() { return new ManifestReporter(this); }
    }

    /** Wire-format payload. Frozen contract across wrappers. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Payload(
            @JsonProperty("reportedAt")  String reportedAt,
            @JsonProperty("instanceKey") String instanceKey,
            @JsonProperty("framework")   String framework,
            @JsonProperty("hash")        String hash,
            @JsonProperty("routes")      List<ManifestRoute> routes) {}
}
