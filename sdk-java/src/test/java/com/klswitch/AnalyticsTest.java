package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class AnalyticsTest {

    private static AnalyticsEvent event() {
        return new AnalyticsEvent(
                Instant.now().toString(),
                1L,
                new AnalyticsEvent.Request("POST", "/p"),
                List.of(),
                0L,
                0L,
                LatencyBucket.LT_500US);
    }

    private static AnalyticsEvent eventTagged(String occurredAt) {
        return new AnalyticsEvent(
                occurredAt,
                1L,
                new AnalyticsEvent.Request("POST", "/p"),
                List.of(),
                0L,
                0L,
                LatencyBucket.LT_500US);
    }

    @Test
    void latencyBucketsMapCorrectly() {
        assertEquals(LatencyBucket.LT_50US,  LatencyBucket.forMillis(0.01));
        assertEquals(LatencyBucket.LT_500US, LatencyBucket.forMillis(0.1));
        assertEquals(LatencyBucket.LT_5MS,   LatencyBucket.forMillis(2));
        assertEquals(LatencyBucket.GE_5MS,   LatencyBucket.forMillis(50));
        // Static helper on the reporter is preserved for backward compatibility.
        assertEquals(LatencyBucket.LT_5MS, AnalyticsReporter.latencyBucket(2));
    }

    @Test
    void flushDeliversQueuedEvents() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<AnalyticsEvent>>();
        AnalyticsReporter reporter = new AnalyticsReporter(
                AnalyticsReporter.Transport.sink(batches::add),
                100, 1_000_000L, 10_000, 5_000L, LoggerAdapter.NOOP);
        reporter.record(event());
        reporter.record(event());
        reporter.flushNow();
        reporter.close();

        int total = batches.stream().mapToInt(List::size).sum();
        assertEquals(2, total);
    }

    @Test
    void overflowDropsOldestKeepsMostRecent() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<AnalyticsEvent>>();
        // batchSize huge so nothing auto-flushes; maxQueueSize 2 → first 3 dropped.
        AnalyticsReporter reporter = new AnalyticsReporter(
                AnalyticsReporter.Transport.sink(batches::add),
                1000, 1_000_000L, 2, 5_000L, LoggerAdapter.NOOP);
        for (int i = 0; i < 5; i++) {
            reporter.record(eventTagged("evt-" + i));
        }
        reporter.flushNow();
        reporter.close();

        List<AnalyticsEvent> all = batches.stream().flatMap(List::stream).toList();
        assertEquals(2, all.size(), "buffer capped at maxQueueSize");
        // The two latest events (3, 4) should survive; oldest (0, 1, 2) dropped.
        assertEquals("evt-3", all.get(0).occurredAt());
        assertEquals("evt-4", all.get(1).occurredAt());
        assertEquals(3, reporter.status().eventsDropped());
    }

    @Test
    void throwingSinkIsLoggedNotPropagated() {
        AtomicInteger warnCount = new AtomicInteger();
        Logger logger = new Logger() {
            public void debug(String m, Map<String, Object> f) {}
            public void info(String m, Map<String, Object> f) {}
            public void warn(String m, Map<String, Object> f) { warnCount.incrementAndGet(); }
            public void error(String m, Map<String, Object> f) {}
        };
        AnalyticsReporter reporter = new AnalyticsReporter(
                AnalyticsReporter.Transport.sink(b -> { throw new RuntimeException("boom"); }),
                100, 1_000_000L, 10_000, 5_000L, logger);
        reporter.record(event());
        reporter.flushNow(); // must not throw
        AnalyticsStatus st = reporter.status();
        reporter.close();
        assertTrue(warnCount.get() >= 1, "sink-threw warning expected");
        assertEquals(Boolean.FALSE, st.lastFlushOk());
        assertEquals(1L, st.batchesFailed());
        assertEquals(1, st.consecutiveFailures());
    }

    @Test
    void statusSnapshotsCounters() throws Exception {
        var batches = new ConcurrentLinkedQueue<List<AnalyticsEvent>>();
        AnalyticsReporter reporter = new AnalyticsReporter(
                AnalyticsReporter.Transport.sink(batches::add),
                100, 1_000_000L, 10_000, 5_000L, LoggerAdapter.NOOP);
        for (int i = 0; i < 3; i++) reporter.record(event());
        AnalyticsStatus before = reporter.status();
        assertEquals(3, before.bufferSize());
        assertEquals(3L, before.eventsRecorded());
        assertEquals(0L, before.eventsSent());

        reporter.flushNow();
        AnalyticsStatus after = reporter.status();
        assertEquals(0, after.bufferSize());
        assertEquals(3L, after.eventsSent());
        assertEquals(Boolean.TRUE, after.lastFlushOk());
        assertEquals(0, after.consecutiveFailures());
        reporter.close();
    }

    @Test
    void httpTransportPostsGzippedEnvelope() throws Exception {
        ConcurrentLinkedQueue<byte[]> seenBodies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Map<String, List<String>>> seenHeaders = new ConcurrentLinkedQueue<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/decisions", ex -> {
            seenHeaders.add(Map.copyOf(ex.getRequestHeaders()));
            seenBodies.add(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        srv.start();
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/decisions";
            AnalyticsReporter reporter = new AnalyticsReporter(
                    AnalyticsReporter.Transport.http(
                            url,
                            Map.of("Authorization", "Bearer t", "X-Instance-Key", "pod-1"),
                            "0.1.0", "pod-1", 5_000L),
                    2, 1_000_000L, 10_000, 5_000L, LoggerAdapter.NOOP);
            reporter.record(new AnalyticsEvent(
                    Instant.now().toString(), 1L,
                    new AnalyticsEvent.Request("POST", "/p"),
                    List.of(new AnalyticsEvent.MatchedAction("r1", "block")),
                    1L, 0L, LatencyBucket.LT_500US));
            reporter.record(new AnalyticsEvent(
                    Instant.now().toString(), 1L,
                    new AnalyticsEvent.Request("POST", "/p"),
                    List.of(new AnalyticsEvent.MatchedAction("r2", "notify")),
                    1L, 0L, LatencyBucket.LT_500US));
            // Auto-flush at batchSize=2; give the scheduler a moment.
            for (int i = 0; i < 20 && seenBodies.isEmpty(); i++) Thread.sleep(20);
            reporter.flushNow();
            reporter.close();

            assertEquals(1, seenBodies.size(), "expected one POST");
            byte[] raw = seenBodies.peek();
            assertNotNull(raw);

            // Verify gzip header presence.
            Map<String, List<String>> headers = seenHeaders.peek();
            assertNotNull(headers);
            List<String> encHeader = headers.get("Content-encoding");
            assertNotNull(encHeader);
            assertTrue(encHeader.contains("gzip"));

            // Decompress + parse envelope.
            byte[] inflated;
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                inflated = gz.readAllBytes();
            }
            JsonNode env = new ObjectMapper().readTree(inflated);
            assertEquals("java",  env.get("sdkLang").asText());
            assertEquals("0.1.0", env.get("sdkVersion").asText());
            assertEquals("pod-1", env.get("instanceKey").asText());
            assertEquals(2, env.get("events").size());
            assertEquals("r1", env.get("events").get(0).get("matched").get(0).get("ruleId").asText());
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void httpNon2xxCountsAsFailure() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/decisions", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        srv.start();
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/decisions";
            AnalyticsReporter reporter = new AnalyticsReporter(
                    AnalyticsReporter.Transport.http(url, Map.of(), "0.1.0", "pod-1", 5_000L),
                    1, 1_000_000L, 10_000, 5_000L, LoggerAdapter.NOOP);
            reporter.record(event());
            // Wait briefly for the auto-flush fired by batchSize=1.
            for (int i = 0; i < 20 && reporter.status().eventsSent() == 0
                    && reporter.status().batchesFailed() == 0; i++) {
                Thread.sleep(20);
            }
            reporter.flushNow();
            AnalyticsStatus st = reporter.status();
            reporter.close();
            assertEquals(0L, st.eventsSent());
            assertTrue(st.batchesFailed() >= 1, "expected ≥1 failed batch");
            assertEquals(Boolean.FALSE, st.lastFlushOk());
            assertTrue(st.lastFlushError() != null && st.lastFlushError().contains("500"));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void clientEvaluateEmitsNewContractShape() throws Exception {
        String rules = "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[{"
                + "\"id\":\"block\",\"name\":\"Block\","
                + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                + "\"enabled\":true,\"priority\":100,"
                + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":100}]},"
                + "\"action\":{\"type\":\"block\"}}]}";
        String input = "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":500},\"meta\":{}}}";

        var received = new ConcurrentLinkedQueue<AnalyticsEvent>();
        CountDownLatch latch = new CountDownLatch(1);
        AnalyticsSink sink = batch -> {
            received.addAll(batch);
            latch.countDown();
        };
        try (Client ks = Client.init(
                Config.builder().rules(rules).analytics(sink).analyticsBatchSize(1).build())) {
            ks.evaluate(input);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "event delivered");
            AnalyticsEvent e = received.peek();
            assertNotNull(e);
            assertEquals(1L, e.rulesVersion());
            assertEquals(1L, e.evaluated());
            assertEquals(0L, e.skipped());
            assertEquals("POST", e.request().method());
            assertEquals("/p",   e.request().path());
            assertEquals(1, e.matched().size());
            assertEquals("block", e.matched().get(0).actionType());
            assertEquals("block", e.matched().get(0).ruleId());
            assertEquals("evaluation", e.type());
            // ISO 8601
            assertTrue(e.occurredAt().matches("\\d{4}-\\d{2}-\\d{2}T.*"));
        }
    }

    @Test
    void noMatchEvaluationStillEmitsEvent() throws Exception {
        String rules = "{\"schema_version\":\"1.0\",\"rules_version\":2,\"rules\":[{"
                + "\"id\":\"r\",\"name\":\"R\","
                + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                + "\"enabled\":true,\"priority\":100,"
                + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":1000}]},"
                + "\"action\":{\"type\":\"block\"}}]}";
        // Below threshold → candidate runs but does not match.
        String input = "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":50},\"meta\":{}}}";

        var received = new ConcurrentLinkedQueue<AnalyticsEvent>();
        CountDownLatch latch = new CountDownLatch(1);
        try (Client ks = Client.init(Config.builder().rules(rules)
                .analytics(b -> { received.addAll(b); latch.countDown(); })
                .analyticsBatchSize(1).build())) {
            ks.evaluate(input);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "event delivered");
            AnalyticsEvent e = received.peek();
            assertNotNull(e);
            assertTrue(e.matched().isEmpty(), "matched should be empty for no-match");
            assertEquals(1L, e.evaluated(), "candidate ran");
        }
    }

    @Test
    void clientAnalyticsStatusReturnsDisabledWhenNotConfigured() throws Exception {
        String rules = "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[]}";
        try (Client ks = Client.init(Config.builder().rules(rules).build())) {
            AnalyticsStatus st = ks.analyticsStatus();
            assertFalse(st.enabled());
            assertEquals(0, st.bufferSize());
        }
    }
}
