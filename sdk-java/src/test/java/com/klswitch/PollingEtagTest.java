package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B1 — ETag + Cache-Control polling tests. Drives the real
 * {@link HttpRulesFetcher} against a JDK {@link HttpServer} so the HEAD/GET
 * dance, conditional-GET, and Cache-Control parsing all see real HTTP
 * semantics.
 */
class PollingEtagTest {

    private static final String RULES_V1 =
            "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[{"
                    + "\"id\":\"r\",\"name\":\"R\","
                    + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"enabled\":true,\"priority\":100,"
                    + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}]},"
                    + "\"action\":{\"type\":\"block\"}}]}";

    private static final String RULES_V2 =
            "{\"schema_version\":\"1.0\",\"rules_version\":2,\"rules\":[]}";

    /** Per-reply controls for the mock server (set fresh in each test). */
    private static final class Reply {
        int status = 200;
        String etag;          // sent on the response when non-null
        String cacheControl;  // sent on the response when non-null
        String body = "";     // GET only
    }

    /** Records every incoming request + sequences replies. */
    private static final class MockServer {
        HttpServer srv;
        String url;
        final List<String> methods = new ArrayList<>();
        final List<String> ifNoneMatch = new ArrayList<>();
        final List<Reply> replies = new ArrayList<>();
        final AtomicInteger callIndex = new AtomicInteger(0);

        Reply currentReply() {
            int i = callIndex.get();
            return replies.get(Math.min(i, replies.size() - 1));
        }

        void start() throws IOException {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/v1/ruleset", exchange -> {
                String method = exchange.getRequestMethod();
                String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
                synchronized (methods) {
                    methods.add(method);
                    ifNoneMatch.add(inm == null ? "" : inm);
                }
                Reply r = currentReply();
                callIndex.incrementAndGet();
                if (r.etag != null) exchange.getResponseHeaders().add("ETag", r.etag);
                if (r.cacheControl != null) exchange.getResponseHeaders().add("Cache-Control", r.cacheControl);
                if ("HEAD".equals(method) || r.status == 304) {
                    exchange.sendResponseHeaders(r.status, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = r.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(r.status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            srv.start();
            url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/ruleset";
        }

        void stop() { srv.stop(0); }
    }

    private MockServer mock;

    @BeforeEach
    void setUp() throws IOException {
        mock = new MockServer();
    }

    @AfterEach
    void tearDown() {
        if (mock != null && mock.srv != null) mock.stop();
    }

    private void addReply(int status, String etag, String cacheControl, String body) {
        Reply r = new Reply();
        r.status = status;
        r.etag = etag;
        r.cacheControl = cacheControl;
        r.body = body == null ? "" : body;
        mock.replies.add(r);
    }

    private RulesFetcher fetcher() {
        return RulesFetcher.http(mock.url, Map.of("Authorization", "Bearer t"), 5_000L);
    }

    // ─── HEAD-first / ETag dedup ─────────────────────────────────────────

    @Test
    void firstFetchIsGet_thenSubsequentFetchesAreHead() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);  // cold GET
        addReply(200, "\"v1\"", "max-age=30", null);      // HEAD probe matches

        RulesFetcher f = fetcher();
        RulesFetcher.FetchResult r1 = f.fetchWithMeta();
        RulesFetcher.FetchResult r2 = f.fetchWithMeta();

        synchronized (mock.methods) {
            assertEquals(List.of("GET", "HEAD"), mock.methods);
        }
        assertTrue(r1.body().isPresent(), "cold GET delivers body");
        assertFalse(r2.body().isPresent(), "HEAD match → no body");
        assertEquals("\"v1\"", r2.etag().orElseThrow());
    }

    @Test
    void headEtagFlipTriggersConditionalGet() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);  // cold GET
        addReply(200, "\"v2\"", "max-age=30", null);      // HEAD shows flip
        addReply(200, "\"v2\"", "max-age=30", RULES_V2);  // conditional GET

        RulesFetcher f = fetcher();
        f.fetchWithMeta();
        RulesFetcher.FetchResult r2 = f.fetchWithMeta();

        synchronized (mock.methods) {
            assertEquals(List.of("GET", "HEAD", "GET"), mock.methods);
            assertEquals("\"v1\"", mock.ifNoneMatch.get(2),
                    "conditional GET carries If-None-Match: <lastEtag>");
        }
        assertTrue(r2.body().isPresent());
        assertEquals(RULES_V2, r2.body().get());
        assertEquals("\"v2\"", r2.etag().orElseThrow());
    }

    @Test
    void conditionalGet304IsNoOp() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);  // cold GET
        addReply(200, "\"v2\"", "max-age=30", null);      // HEAD says new
        addReply(304, "\"v1\"", "max-age=30", null);      // race: server reverted

        RulesFetcher f = fetcher();
        f.fetchWithMeta();
        RulesFetcher.FetchResult r2 = f.fetchWithMeta();

        synchronized (mock.methods) {
            assertEquals(List.of("GET", "HEAD", "GET"), mock.methods);
        }
        assertFalse(r2.body().isPresent(), "304 → no body");
    }

    // ─── Cache-Control max-age tuning ────────────────────────────────────

    @Test
    void cacheControlPopulatesNextDelayMs() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=120", RULES_V1);

        RulesFetcher.FetchResult r = fetcher().fetchWithMeta();
        assertEquals(120_000L, r.nextDelayMs().orElseThrow());
    }

    @Test
    void extraCacheControlDirectivesAreTolerated() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "public, max-age=45, must-revalidate", RULES_V1);

        RulesFetcher.FetchResult r = fetcher().fetchWithMeta();
        assertEquals(45_000L, r.nextDelayMs().orElseThrow());
    }

    @Test
    void missingCacheControlMeansEmptyNextDelayMs() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", null, RULES_V1);

        RulesFetcher.FetchResult r = fetcher().fetchWithMeta();
        assertTrue(r.nextDelayMs().isEmpty());
    }

    // ─── PollingManager — server-driven schedule ─────────────────────────

    @Test
    void pollingManagerStatusReflectsLastEtagAndCurrentInterval() throws Exception {
        mock.start();
        addReply(200, "\"v9\"", "max-age=120", RULES_V1);

        AtomicInteger fetched = new AtomicInteger();
        PollingManager pm = new PollingManager(
                fetcher(),
                LoggerAdapter.NOOP,
                body -> fetched.incrementAndGet(),
                err -> {});
        pm.pollOnce();

        PollingManager.PollingStatus st = pm.status();
        assertEquals("\"v9\"", st.lastEtag());
        assertEquals(120_000L, st.currentIntervalMs(),
                "server max-age sets the wrapper's next-poll cadence");
        assertEquals(1, fetched.get());
        pm.close();
    }

    @Test
    void pollingManagerClampsMaxAgeZeroToMin() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=0", RULES_V1);

        PollingManager pm = new PollingManager(
                fetcher(), LoggerAdapter.NOOP, body -> {}, err -> {});
        pm.pollOnce();
        assertEquals(PollingManager.MIN_POLL_MS, pm.status().currentIntervalMs(),
                "max-age=0 must clamp to MIN_POLL_MS — self-DDoS guard");
        pm.close();
    }

    @Test
    void pollingManagerClampsEnormousMaxAgeToMax() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=999999", RULES_V1);

        PollingManager pm = new PollingManager(
                fetcher(), LoggerAdapter.NOOP, body -> {}, err -> {});
        pm.pollOnce();
        assertEquals(PollingManager.MAX_POLL_MS, pm.status().currentIntervalMs());
        pm.close();
    }

    @Test
    void pollingManagerSkipsOnFetchedWhenServerSaysNoChange() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);  // cold GET
        addReply(200, "\"v1\"", "max-age=30", null);      // HEAD match

        AtomicInteger fetched = new AtomicInteger();
        PollingManager pm = new PollingManager(
                fetcher(), LoggerAdapter.NOOP, body -> fetched.incrementAndGet(), err -> {});
        pm.pollOnce();
        pm.pollOnce();
        assertEquals(1, fetched.get(),
                "HEAD match → no onFetched on the second poll");
        pm.close();
    }

    // ─── Graceful degradation (server omits headers) ─────────────────────

    @Test
    void noEtagOnResponseMeansEveryPollIsAColdGet() throws Exception {
        mock.start();
        addReply(200, null, null, RULES_V1);  // sticks — no ETag, no Cache-Control

        RulesFetcher f = fetcher();
        f.fetchWithMeta();
        f.fetchWithMeta();
        f.fetchWithMeta();
        synchronized (mock.methods) {
            assertEquals(List.of("GET", "GET", "GET"), mock.methods,
                    "no cached ETag → no HEAD-first optimisation");
        }
    }

    @Test
    void legacyFetchAlwaysIssuesPlainGet() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);
        addReply(200, "\"v1\"", "max-age=30", RULES_V1);

        RulesFetcher f = fetcher();
        f.fetch();
        f.fetch();
        synchronized (mock.methods) {
            assertEquals(List.of("GET", "GET"), mock.methods,
                    "legacy fetch() never goes via HEAD — keeps single-call semantics");
        }
    }

    // ─── Status surface ──────────────────────────────────────────────────

    @Test
    void pollingStatusBeforeAnyFetchHasNullEtagAndZeroNext() {
        PollingManager pm = new PollingManager(
                () -> RULES_V1, LoggerAdapter.NOOP, body -> {}, err -> {});
        PollingManager.PollingStatus st = pm.status();
        assertNull(st.lastEtag());
        assertNull(st.nextPollAt());
        pm.close();
    }

    @Test
    void pollingStatusNextPollAtSetAfterStart() throws Exception {
        mock.start();
        addReply(200, "\"v1\"", "max-age=60", RULES_V1);

        PollingManager pm = new PollingManager(
                fetcher(), LoggerAdapter.NOOP, body -> {}, err -> {});
        pm.pollOnce();          // populates lastEtag + currentInterval from the response
        pm.start(30_000L);      // schedules next tick from currentInterval (60s, not 30s)
        PollingManager.PollingStatus st = pm.status();
        assertNotNull(st.nextPollAt(), "nextPollAt set after start()");
        pm.close();
    }
}
