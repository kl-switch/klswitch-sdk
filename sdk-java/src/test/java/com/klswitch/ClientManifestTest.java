package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end manifest wiring via the real Client. Uses an in-process
 * {@link HttpServer} as the backend so the manifest POST actually fires
 * across the network.
 *
 * <p>A custom {@link RulesFetcher} is injected to keep the tests off the
 * network for polling; the manifest reporter is built regardless once a
 * token + source are present.
 */
class ClientManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":7,\"rules\":[{"
                    + "\"id\":\"r\",\"name\":\"R\","
                    + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"enabled\":true,\"priority\":100,"
                    + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}]},"
                    + "\"action\":{\"type\":\"block\"}}]}";

    private HttpServer srv;
    private final List<JsonNode> manifestBodies = new ArrayList<>();
    private final List<String> seenAuthHeaders = new ArrayList<>();
    private String manifestUrl;

    @BeforeEach
    void up() throws IOException {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/manifest", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (manifestBodies) {
                manifestBodies.add(MAPPER.readTree(body));
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) seenAuthHeaders.add(auth);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        srv.start();
        manifestUrl = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/manifest";
    }

    @AfterEach
    void down() {
        if (srv != null) srv.stop(0);
    }

    private ManifestSource source(List<ManifestRoute> routes) {
        return ManifestSource.of("jax-rs", () -> routes);
    }

    @Test
    void clientPushesManifestAtInit() throws Exception {
        Config cfg = Config.builder()
                .token("ks_live_test")
                .manifestUrl(manifestUrl)
                .fetcher(() -> RULES)                  // skip the default HTTP path
                .refreshIntervalMs(0)
                .manifest(source(List.of(new ManifestRoute("POST", "/v1/payments"))))
                .build();
        try (Client ks = Client.init(cfg)) {
            // Initial push is fire-and-forget — wait until the recorder sees it.
            waitForBody();
            JsonNode body;
            synchronized (manifestBodies) {
                body = manifestBodies.get(0);
            }
            assertEquals("jax-rs", body.get("framework").asText());
            JsonNode routes = body.get("routes");
            assertEquals(1, routes.size());
            assertEquals("POST",         routes.get(0).get("method").asText());
            assertEquals("/v1/payments", routes.get(0).get("path").asText());
            synchronized (manifestBodies) {
                assertEquals("Bearer ks_live_test", seenAuthHeaders.get(0));
            }

            ManifestStatus st = ks.manifestStatus();
            assertTrue(st.enabled());
            assertEquals("jax-rs", st.framework());
            assertEquals(1, st.routeCount());
            assertEquals(Boolean.TRUE, st.lastPushOk());
        }
    }

    @Test
    void clientManifestDisabledWhenNoSourceSupplied() {
        Config cfg = Config.builder().rules(RULES).build();
        try (Client ks = Client.init(cfg)) {
            ManifestStatus st = ks.manifestStatus();
            assertFalse(st.enabled());
            assertNull(st.framework());
        }
    }

    @Test
    void reportManifestFalseOptsOutEvenWithSource() throws Exception {
        Config cfg = Config.builder()
                .token("ks_live_test")
                .manifestUrl(manifestUrl)
                .fetcher(() -> RULES)
                .refreshIntervalMs(0)
                .manifest(source(List.of(new ManifestRoute("POST", "/v1/payments"))))
                .reportManifest(false)
                .build();
        try (Client ks = Client.init(cfg)) {
            // Give any rogue push thread a beat to (not) fire.
            Thread.sleep(150);
            assertFalse(ks.manifestStatus().enabled());
            synchronized (manifestBodies) {
                assertEquals(0, manifestBodies.size());
            }
        }
    }

    @Test
    void reportManifestExplicitCallRePushesWhenRoutesChange() throws Exception {
        List<ManifestRoute> live = new ArrayList<>();
        live.add(new ManifestRoute("POST", "/v1/payments"));
        Config cfg = Config.builder()
                .token("ks_live_test")
                .manifestUrl(manifestUrl)
                .fetcher(() -> RULES)
                .refreshIntervalMs(0)
                .manifest(ManifestSource.of("jax-rs", () -> List.copyOf(live)))
                .build();
        try (Client ks = Client.init(cfg)) {
            waitForBody();
            waitForReporterSettled(ks);
            int before;
            synchronized (manifestBodies) {
                before = manifestBodies.size();
            }
            // Same routes → no extra POST.
            ks.reportManifest();
            synchronized (manifestBodies) {
                assertEquals(before, manifestBodies.size());
            }
            // Mutate the registry → next call pushes again.
            live.add(new ManifestRoute("POST", "/v1/refunds"));
            assertTrue(ks.reportManifest());
            // Wait briefly for the (synchronous) report to register.
            synchronized (manifestBodies) {
                assertEquals(before + 1, manifestBodies.size());
                assertEquals(2,
                        manifestBodies.get(manifestBodies.size() - 1).get("routes").size());
            }
        }
    }

    @Test
    void firstEvaluateRePushesRoutesRegisteredAfterInit() throws Exception {
        // The Dropwizard ordering, which is forced rather than chosen: the
        // resources need the client in order to construct the guard, so they
        // can only register after Client.init has already fired its push.
        List<ManifestRoute> live = new ArrayList<>();
        Config cfg = Config.builder()
                .token("ks_live_test")
                .manifestUrl(manifestUrl)
                .fetcher(() -> RULES)
                .refreshIntervalMs(0)
                .manifest(ManifestSource.of("jax-rs", () -> List.copyOf(live)))
                .build();
        try (Client ks = Client.init(cfg)) {
            waitForBody();
            waitForReporterSettled(ks);
            synchronized (manifestBodies) {
                assertEquals(0, manifestBodies.get(0).get("routes").size(),
                        "init pushes before any resource has registered");
            }

            // Resources register — the console still has nothing to suggest.
            live.add(new ManifestRoute("POST", "/v1/payments/:id"));
            live.add(new ManifestRoute("GET", "/v1/users/:id"));

            // First request proves routing is live; discovery re-runs.
            ks.evaluate("{\"selector\":{\"kind\":\"api\",\"keys\":"
                    + "{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"facts\":{\"body\":{\"amount\":1}}}");

            long deadline = System.currentTimeMillis() + 2_000L;
            JsonNode latest = null;
            while (System.currentTimeMillis() < deadline) {
                synchronized (manifestBodies) {
                    if (manifestBodies.size() > 1) {
                        latest = manifestBodies.get(manifestBodies.size() - 1);
                        break;
                    }
                }
                Thread.sleep(20);
            }
            assertNotNull(latest, "first evaluate should re-push the manifest");
            assertEquals(2, latest.get("routes").size());

            // Only once — subsequent requests must not re-push.
            int after;
            synchronized (manifestBodies) {
                after = manifestBodies.size();
            }
            for (int i = 0; i < 5; i++) {
                ks.evaluate("{\"selector\":{\"kind\":\"api\",\"keys\":"
                        + "{\"method\":\"POST\",\"path\":\"/p\"}},"
                        + "\"facts\":{\"body\":{\"amount\":1}}}");
            }
            Thread.sleep(200);
            synchronized (manifestBodies) {
                assertEquals(after, manifestBodies.size(),
                        "the re-push is one-shot, not per request");
            }
        }
    }

    private void waitForBody() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (manifestBodies) {
                if (!manifestBodies.isEmpty()) return;
            }
            Thread.sleep(20);
        }
        synchronized (manifestBodies) {
            if (manifestBodies.isEmpty()) {
                throw new AssertionError("manifest POST never fired");
            }
        }
    }

    /**
     * Wait until the manifest reporter has fully settled — its {@code lastHash}
     * is only set after the POST returns, so this guards against races where
     * the request body has been recorded but the reporter hasn't yet
     * committed the hash (a later report() would then re-send).
     */
    private void waitForReporterSettled(Client ks) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (ks.manifestStatus().lastHash() != null) return;
            Thread.sleep(20);
        }
        throw new AssertionError("manifest reporter never recorded a successful push");
    }
}
