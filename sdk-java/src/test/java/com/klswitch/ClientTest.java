package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClientTest {

    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":7,\"rules\":[{"
                    + "\"id\":\"block\",\"name\":\"Block\","
                    + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"enabled\":true,\"priority\":100,"
                    + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":100}]},"
                    + "\"action\":{\"type\":\"block\",\"payload\":{\"code\":\"RULE_BLOCKED\"}}}]}";

    private static final String INPUT =
            "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":500},\"meta\":{}}}";

    @Test
    void initEvaluateStatusClose() {
        try (Client ks = Client.init(Config.builder().rules(RULES).build())) {
            Status status = ks.getStatus();
            assertTrue(status.ready());
            assertTrue(status.initialized());
            assertEquals(Long.valueOf(7), status.activeRulesVersion());
            assertEquals(1, status.ruleCount());
            assertTrue(status.lastLoadOk());

            EvaluationResult result = ks.evaluate(INPUT);
            assertTrue(result.ok());
            assertEquals(1, result.matched().size());
            assertEquals("block", result.matched().get(0).id());
            assertEquals("block", result.matched().get(0).action().type());
        }
    }

    @Test
    void initFailsClearlyOnInvalidRules() {
        String bad =
                "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[{"
                        + "\"id\":\"bad\",\"name\":\"B\","
                        + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                        + "\"enabled\":true,"
                        + "\"condition\":{\"all\":[{\"field\":\"body.x\",\"operator\":\"bogus_op\",\"value\":1}]},"
                        + "\"action\":{\"type\":\"block\"}}]}";
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> Client.init(Config.builder().rules(bad).build()));
        assertTrue(ex.getMessage().toLowerCase().contains("failed to load"));
    }

    @Test
    void initFailsWithNoRulesSourceAndNoToken() {
        // No inline rules + no token + no custom fetcher → token is required.
        // The validator catches this at builder.build() time.
        assertThrows(IllegalArgumentException.class, () -> Config.builder().build());
    }

    @Test
    void instanceIdIsExposedAfterInit() {
        try (Client ks = Client.init(Config.builder().rules(RULES).instanceId("pod-7f4a").build())) {
            assertEquals("pod-7f4a", ks.instanceId());
        }
    }

    @Test
    void urlsAndSdkVersionAreExposed() {
        try (Client ks = Client.init(Config.builder().rules(RULES).build())) {
            assertEquals("https://api.klswitch.dev/v1/ruleset", ks.urls().rules());
            assertEquals("https://api.klswitch.dev/v1/analytics", ks.urls().decisions());
            assertTrue(ks.sdkVersion().matches("\\d+\\.\\d+\\.\\d+"));
        }
    }

    @Test
    void baseUrlOverridesEveryEndpoint() {
        try (Client ks = Client.init(Config.builder()
                .rules(RULES).baseUrl("http://self-hosted:8080").build())) {
            assertEquals("http://self-hosted:8080/v1/ruleset", ks.urls().rules());
            assertEquals("http://self-hosted:8080/v1/analytics", ks.urls().decisions());
        }
    }

    @Test
    void evaluateAfterCloseThrows() {
        Client ks = Client.init(Config.builder().rules(RULES).build());
        ks.close();
        assertThrows(IllegalStateException.class, () -> ks.evaluate(INPUT));
    }

    @Test
    void getStatusAfterCloseReportsNotInitialized() {
        Client ks = Client.init(Config.builder().rules(RULES).build());
        ks.close();
        Status status = ks.getStatus(); // must not throw (parity with Node)
        assertFalse(status.ready());
        assertFalse(status.initialized());
    }

    @Test
    void initFailureReleasesEngineEvenWithAnalyticsConfigured() {
        // Regression contract for the analytics-leak fix: when init fails after the
        // engine is allocated, close() must run — even with analytics configured.
        // The Java AnalyticsReporter constructor can't be made to throw without a
        // mocking framework, so this test triggers the failure earlier (invalid
        // rules) but exercises the surrounding catch+close pattern that the fix
        // hardened. Observed via the "client closed" log line emitted by close().
        List<String> events = new ArrayList<>();
        Logger recorder = new Logger() {
            @Override public void debug(String m, Map<String, Object> f) { events.add(m); }
            @Override public void info(String m, Map<String, Object> f) { events.add(m); }
            @Override public void warn(String m, Map<String, Object> f) { events.add(m); }
            @Override public void error(String m, Map<String, Object> f) { events.add(m); }
        };
        String invalidRules =
                "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[{"
                        + "\"id\":\"bad\",\"name\":\"B\","
                        + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                        + "\"enabled\":true,"
                        + "\"condition\":{\"all\":[{\"field\":\"x\",\"operator\":\"bogus_op\",\"value\":1}]},"
                        + "\"action\":{\"type\":\"block\"}}]}";

        assertThrows(IllegalStateException.class, () -> Client.init(
                Config.builder()
                        .rules(invalidRules)
                        .analytics(batch -> {}) // configured but never reached
                        .logger(recorder)
                        .build()));

        assertTrue(events.contains("client closed"),
                "close() must run on init failure; events=" + events);
    }

    @Test
    void evaluateIsThreadSafeUnderConcurrency() throws Exception {
        // A shared Client hit by many threads (as a Spring singleton would be) must
        // serialize engine access and never corrupt the single WASM instance.
        try (Client ks = Client.init(Config.builder().rules(RULES).build())) {
            int threads = 8;
            int perThread = 250;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            EvaluationResult r = ks.evaluate(INPUT);
                            if (!r.ok() || r.matched().size() != 1 || !r.matched().get(0).id().equals("block")) {
                                throw new AssertionError("unexpected result: " + r);
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "threads should finish");
            pool.shutdownNow();
            assertTrue(errors.isEmpty(), "concurrent evaluate errors: " + errors);
        }
    }
}
