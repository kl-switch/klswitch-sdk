package com.klswitch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Fetches the current ruleset payload as a JSON string. Injectable so tests (and
 * custom transports) can supply rules without HTTP; {@link #http} is the default.
 *
 * <h2>v1.1 server-driven polling</h2>
 *
 * <p>{@link #http} now does HEAD-first when an ETag has been cached, and only
 * GETs when the version flipped. Each response's {@code Cache-Control: max-age}
 * surfaces via {@link FetchResult#nextDelayMs()} so {@link PollingManager} can
 * retune its schedule.
 *
 * <p>Operator-injected fetchers implementing only the legacy {@link #fetch()}
 * method continue to work unchanged — the default {@link #fetchWithMeta()}
 * wraps them with an empty cadence hint, so PollingManager falls back to its
 * configured interval.
 */
public interface RulesFetcher {

    /** Cap on the response-body snippet included in non-2xx error messages. */
    int ERROR_BODY_SNIPPET_CHARS = 4096;

    /** Legacy single-call API. Returns the body or throws. Always issues a full
     *  fetch (no ETag / 304 optimisation) — that lives on {@link #fetchWithMeta()}. */
    String fetch() throws Exception;

    /**
     * v1.1 fetch surface. Returns:
     * <ul>
     *   <li>{@link FetchResult#body()} present + new {@code etag} when the
     *       server delivered a fresh body
     *   <li>{@link FetchResult#body()} empty when nothing changed (HEAD ETag
     *       match, or GET returned 304) — PollingManager skips
     *       {@code onFetched}
     *   <li>{@link FetchResult#nextDelayMs()} carrying the server's
     *       {@code Cache-Control: max-age} when present
     * </ul>
     *
     * <p>The default implementation forwards to {@link #fetch()} so operator
     * fetchers don't have to opt in to v1.1 — they just lose the polling-cost
     * optimisation.
     */
    default FetchResult fetchWithMeta() throws Exception {
        return new FetchResult(Optional.of(fetch()), Optional.empty(), OptionalLong.empty());
    }

    /**
     * Outcome of one poll cycle. {@code body} is empty when the backend
     * confirmed no change; {@code nextDelayMs} is empty when the backend
     * sent no {@code Cache-Control: max-age} (PollingManager then keeps its
     * current schedule).
     */
    record FetchResult(
            Optional<String> body,
            Optional<String> etag,
            OptionalLong nextDelayMs) {}

    /** A fetcher that GETs {@code url} with optional headers and a per-request timeout. */
    static RulesFetcher http(String url, Map<String, String> headers, long timeoutMs) {
        return new HttpRulesFetcher(url, headers, timeoutMs);
    }
}

/**
 * Default HTTP implementation. Stateful — tracks {@code lastEtag} so HEAD
 * probes can dedup against it. Not exported; consumers reach it via
 * {@link RulesFetcher#http}.
 */
final class HttpRulesFetcher implements RulesFetcher {

    private final String url;
    private final Map<String, String> headers;
    private final long timeoutMs;
    private final HttpClient client;
    private final AtomicReference<String> lastEtag = new AtomicReference<>();

    HttpRulesFetcher(String url, Map<String, String> headers, long timeoutMs) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeoutMs = timeoutMs;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    /** Forces a plain GET — the legacy path doesn't track ETag. */
    @Override
    public String fetch() throws Exception {
        HttpResponse<String> response = sendGet(/*conditional=*/ false);
        ensureSuccess(response, "GET");
        return response.body() == null ? "" : response.body();
    }

    @Override
    public FetchResult fetchWithMeta() throws Exception {
        String cached = lastEtag.get();

        // Cold start: no ETag → straight to GET.
        if (cached == null) {
            HttpResponse<String> response = sendGet(/*conditional=*/ false);
            ensureSuccess(response, "GET");
            return resultFrom(response, /*haveBody=*/ true);
        }

        // Warm: HEAD probe.
        HttpResponse<Void> head = sendHead();
        ensureSuccess(head, "HEAD");
        String headEtag = headerValue(head, "ETag").orElse(null);
        OptionalLong nextDelay = cacheControlDelayMs(head);

        if (headEtag != null && headEtag.equals(cached)) {
            // No change — backend already has our version. Skip the GET.
            return new FetchResult(Optional.empty(), Optional.of(cached), nextDelay);
        }

        // ETag flipped (or server omitted it). Do a conditional GET — server
        // may reply 304 if the version flipped back during the HEAD→GET
        // window (race safety).
        HttpResponse<String> get = sendGet(/*conditional=*/ true);
        if (get.statusCode() == 304) {
            // Race — backend reverted. Keep the cached ETag, no body to deliver.
            return new FetchResult(Optional.empty(), Optional.of(cached),
                    cacheControlDelayMs(get));
        }
        ensureSuccess(get, "GET");
        return resultFrom(get, /*haveBody=*/ true);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private HttpResponse<String> sendGet(boolean conditional) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
        headers.forEach(req::header);
        if (conditional) {
            String cached = lastEtag.get();
            if (cached != null) req.header("If-None-Match", cached);
        }
        return client.send(req.build(), gzipAwareStringHandler());
    }

    /**
     * Body handler that gunzips when the server honoured our {@code Accept-Encoding: gzip}.
     *
     * <p>We set that header ourselves ({@link RequestHeaders}), and {@link HttpClient} — unlike
     * Node's {@code fetch} — never decompresses transparently. Without this, {@code ofString()}
     * decodes deflate bytes as text, the ruleset fails to parse, and the client silently falls
     * back to whatever rules it already had. The Go wrapper does the same for the same reason.
     */
    private static HttpResponse.BodyHandler<String> gzipAwareStringHandler() {
        return info -> {
            boolean gzipped = info.headers().firstValue("Content-Encoding")
                    .map(v -> v.trim().equalsIgnoreCase("gzip"))
                    .orElse(false);
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArray(),
                    bytes -> decodeBody(bytes, gzipped));
        };
    }

    private static String decodeBody(byte[] bytes, boolean gzipped) {
        if (bytes == null || bytes.length == 0) return "";
        if (!gzipped) return new String(bytes, StandardCharsets.UTF_8);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("rules API response could not be gunzipped", e);
        }
    }

    private HttpResponse<Void> sendHead() throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        headers.forEach(req::header);
        return client.send(req.build(), HttpResponse.BodyHandlers.discarding());
    }

    /** Update {@code lastEtag} from a fresh body response and return a
     *  FetchResult carrying the body + delay hint. */
    private <T> FetchResult resultFrom(HttpResponse<T> response, boolean haveBody) {
        Optional<String> etag = headerValue(response, "ETag");
        etag.ifPresent(lastEtag::set);
        OptionalLong delay = cacheControlDelayMs(response);
        Optional<String> body = Optional.empty();
        if (haveBody && response.body() instanceof String s) {
            body = Optional.of(s);
        }
        return new FetchResult(body, etag, delay);
    }

    private <T> void ensureSuccess(HttpResponse<T> response, String method) throws IOException {
        int status = response.statusCode();
        if (status < 400) return;
        String body = "";
        if (response.body() instanceof String s) {
            body = s == null ? "" : s;
        }
        String snippet = body.length() > ERROR_BODY_SNIPPET_CHARS
                ? body.substring(0, ERROR_BODY_SNIPPET_CHARS) + "…"
                : body;
        throw new IOException(
                "rules API " + method + " responded with status " + status
                        + (snippet.isEmpty() ? "" : ": " + snippet));
    }

    private static <T> Optional<String> headerValue(HttpResponse<T> response, String name) {
        Optional<String> raw = response.headers().firstValue(name);
        if (raw.isPresent()) return raw;
        // HTTP header lookup is case-insensitive but case-fold the probe too
        // for safety against bespoke client implementations.
        return response.headers().firstValue(name.toLowerCase(Locale.ROOT));
    }

    /** Returns {@code max-age * 1000} from the response's Cache-Control header
     *  (tolerating extra directives like {@code "public, max-age=30"}). */
    private static <T> OptionalLong cacheControlDelayMs(HttpResponse<T> response) {
        Optional<String> cc = headerValue(response, "Cache-Control");
        if (cc.isEmpty()) return OptionalLong.empty();
        for (String directive : cc.get().split(",")) {
            String trimmed = directive.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("max-age")) {
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                try {
                    long n = Long.parseLong(trimmed.substring(eq + 1).trim());
                    if (n >= 0) return OptionalLong.of(n * 1000L);
                } catch (NumberFormatException ignore) {
                    /* fall through */
                }
            }
        }
        return OptionalLong.empty();
    }
}
