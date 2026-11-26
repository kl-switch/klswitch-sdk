package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Phase 14: drive the REAL default HTTP fetcher ({@link RulesFetcher#http}) via
 * {@code Config.rulesUrl} against a JDK {@link HttpServer} (no new deps) serving
 * the canonical {@code backend/examples/} fixtures — the same fixtures the Node
 * suite uses. Proves init/refresh/keep-last-known-good over a real socket.
 */
class MockRulesApiTest {

    private static final Path EXAMPLES =
            Path.of(System.getProperty("user.dir")).getParent().resolve("backend/examples");

    private static String fixture(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new RuntimeException("missing backend fixture: " + name, e);
        }
    }

    private static final String V1 = fixture("mock-rules-response.json");
    private static final String V2 = fixture("mock-rules-response-v2.json");
    private static final String INVALID = fixture("mock-rules-invalid.json");

    /** Mutable response so a single server can be swapped between requests. */
    private static final class MockState {
        volatile int status = 200;
        volatile String body;
    }

    /** Starts an ephemeral-port mock rules endpoint; returns the server (caller stops it). */
    private static HttpServer startMock(MockState state, String[] urlOut) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ruleset", exchange -> {
            byte[] bytes = state.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(state.status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        urlOut[0] = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/ruleset";
        return server;
    }

    private static String transferInput(long amount) {
        return "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/transfers\"}},"
                + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":" + amount
                + "},\"meta\":{}}}";
    }

    /** A small-amount transfer from a restricted country: below both thresholds, so it
     * isolates the v2-only block_restricted_country rule from the threshold change. */
    private static final String RESTRICTED_INPUT =
            "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/transfers\"}},"
                    + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},"
                    + "\"body\":{\"amount\":100,\"country\":\"KP\"},\"meta\":{}}}";

    private static int blockedTransfer(Client ks, long amount) {
        return ks.evaluate(transferInput(amount)).matched().size();
    }

    private static java.util.List<String> restrictedIds(Client ks) {
        return ks.evaluate(RESTRICTED_INPUT).matched().stream().map(MatchedRule::id).toList();
    }

    @Test
    void initFetchesV1OverRealHttp() throws Exception {
        MockState state = new MockState();
        state.body = V1;
        String[] url = new String[1];
        HttpServer server = startMock(state, url);
        try (Client ks = Client.init(Config.builder().token("ks_live_test").rulesUrl(url[0]).build())) {
            Status status = ks.getStatus();
            assertTrue(status.ready());
            assertEquals(Long.valueOf(1), status.activeRulesVersion());
            assertEquals(Boolean.TRUE, status.lastRefreshOk());
            assertEquals(1, blockedTransfer(ks, 100_001), "v1 blocks transfers over 100000");
            assertEquals(0, blockedTransfer(ks, 60_000), "v1 allows 60000");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshPicksUpNewVersionOverRealHttp() throws Exception {
        MockState state = new MockState();
        state.body = V1;
        String[] url = new String[1];
        HttpServer server = startMock(state, url);
        try (Client ks = Client.init(Config.builder().token("ks_live_test").rulesUrl(url[0]).build())) {
            assertEquals(Long.valueOf(1), ks.getStatus().activeRulesVersion());
            assertEquals(0, blockedTransfer(ks, 60_000));
            assertEquals(java.util.List.of(), restrictedIds(ks), "v1 has no restricted-country rule");

            state.body = V2; // backend now serves the tightened ruleset
            ks.refresh();
            assertEquals(Long.valueOf(2), ks.getStatus().activeRulesVersion());
            assertEquals(Boolean.TRUE, ks.getStatus().lastRefreshOk());
            assertEquals(1, blockedTransfer(ks, 60_000), "v2 lowered the threshold to 50000");
            assertEquals(
                    java.util.List.of("block_restricted_country"),
                    restrictedIds(ks),
                    "v2's new rule now fires");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidPayloadIsRejectedAndLastKnownGoodKept() throws Exception {
        MockState state = new MockState();
        state.body = V1;
        String[] url = new String[1];
        HttpServer server = startMock(state, url);
        try (Client ks = Client.init(Config.builder().token("ks_live_test").rulesUrl(url[0]).build())) {
            assertEquals(Long.valueOf(1), ks.getStatus().activeRulesVersion());

            state.body = INVALID; // 200, but the body is not a ruleset
            ks.refresh();
            Status status = ks.getStatus();
            assertEquals(Long.valueOf(1), status.activeRulesVersion(), "kept last known good");
            assertEquals(Boolean.FALSE, status.lastRefreshOk());
            assertEquals(1, blockedTransfer(ks, 100_001), "still evaluates against v1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorKeepsLastKnownGoodAndMarksRefreshFailed() throws Exception {
        MockState state = new MockState();
        state.body = V1;
        String[] url = new String[1];
        HttpServer server = startMock(state, url);
        try (Client ks = Client.init(Config.builder().token("ks_live_test").rulesUrl(url[0]).build())) {
            assertEquals(Long.valueOf(1), ks.getStatus().activeRulesVersion());

            state.status = 500;
            state.body = "{\"error\":\"boom\"}";
            ks.refresh();
            Status status = ks.getStatus();
            assertEquals(Long.valueOf(1), status.activeRulesVersion());

            assertEquals(Boolean.FALSE, status.lastRefreshOk());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void customFetcherPathConstructsCleanlyWithoutABackendUrl() {
        // The custom fetcher bypasses the default HTTP fetcher entirely — the
        // operator owns their own transport, and no backend URL is required.
        try (Client ks = Client.init(Config.builder()
                .fetcher(() -> V1)   // no token + no real backend URL
                .build())) {
            assertTrue(ks.getStatus().ready());
        }
    }

    /**
     * Regression: the SDK sets {@code Accept-Encoding: gzip} ({@code RequestHeaders}) and the JDK
     * {@link java.net.http.HttpClient} — unlike Node's {@code fetch} — never decompresses
     * transparently. Before the fix {@code BodyHandlers.ofString()} decoded the deflate bytes as
     * text, the ruleset failed to parse, every fetch failed, and the client silently ran on
     * inline / last-known-good rules — so a published ruleset could never reach a Java service.
     */
    @Test
    void gzippedRulesetIsDecoded() throws Exception {
        byte[] gzipped;
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(V1.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            gzipped = bos.toByteArray();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gzipped.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(gzipped);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try (Client ks = Client.init(Config.builder().token("ks_live_test").rulesUrl(url).build())) {
            Status status = ks.getStatus();
            assertTrue(status.ready(), "gzipped ruleset must load");
            assertEquals(Boolean.TRUE, status.lastRefreshOk(), "gzipped fetch must succeed");
            assertEquals(Long.valueOf(1), status.activeRulesVersion());
            assertEquals(1, blockedTransfer(ks, 100_001), "rules from the gzipped body are active");
        } finally {
            server.stop(0);
        }
    }

}
