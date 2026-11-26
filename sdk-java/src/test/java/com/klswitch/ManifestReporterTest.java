package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ManifestReporter}. Uses the injectable {@link
 * ManifestReporter.Sender} seam to capture the wire payload without
 * standing up a real HTTP server.
 */
class ManifestReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Captures every send() call, replies with a configurable status. */
    private static final class Recorder implements ManifestReporter.Sender {
        final List<JsonNode> bodies = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final AtomicInteger reply = new AtomicInteger(204);

        @Override
        public int send(String url, Map<String, String> headers, byte[] body, long timeoutMs) throws Exception {
            this.bodies.add(MAPPER.readTree(body));
            this.headers.add(Map.copyOf(headers));
            return reply.get();
        }
    }

    private static ManifestReporter.Builder baseBuilder(Recorder rec, ManifestSource source) {
        return ManifestReporter.builder()
                .url("http://api/v1/manifest")
                .headers(Map.of("Authorization", "Bearer t", "X-Instance-Key", "pod-1"))
                .source(source)
                .instanceKey("pod-1")
                .sender(rec);
    }

    private static ManifestSource sourceOf(ManifestRoute... routes) {
        return ManifestSource.of("fastify", () -> List.of(routes));
    }

    @Test
    void reportPostsNormalisedRoutes() {
        Recorder rec = new Recorder();
        ManifestReporter rep = baseBuilder(rec, ManifestSource.of("fastify", () -> List.of(
                new ManifestRoute("get",  "/v1/healthz"),
                new ManifestRoute("POST", "/v1/payments"),
                new ManifestRoute("get",  "/v1/orders/:id")
        ))).build();

        assertTrue(rep.report());
        assertEquals(1, rec.bodies.size());

        JsonNode body = rec.bodies.get(0);
        assertEquals("pod-1",   body.get("instanceKey").asText());
        assertEquals("fastify", body.get("framework").asText());
        JsonNode routes = body.get("routes");
        assertEquals(3, routes.size());
        // Sorted alphabetically by `${METHOD} ${path}`.
        assertEquals("GET",            routes.get(0).get("method").asText());
        assertEquals("/v1/healthz",    routes.get(0).get("path").asText());
        assertEquals("GET",            routes.get(1).get("method").asText());
        assertEquals("/v1/orders/:id", routes.get(1).get("path").asText());
        assertEquals("POST",           routes.get(2).get("method").asText());
        assertEquals("/v1/payments",   routes.get(2).get("path").asText());
        // sha256 hex is 64 chars.
        assertEquals(64, body.get("hash").asText().length());
        // Forwarded headers reach the sender.
        assertEquals("Bearer t", rec.headers.get(0).get("Authorization"));
    }

    @Test
    void reReportWithUnchangedRoutesIsNoOp() {
        Recorder rec = new Recorder();
        ManifestReporter rep = baseBuilder(rec, sourceOf(
                new ManifestRoute("POST", "/v1/payments"))).build();
        assertTrue(rep.report());
        assertEquals(1, rec.bodies.size());
        // Same source on a re-report → no extra POST.
        assertTrue(rep.report());
        assertEquals(1, rec.bodies.size());
    }

    @Test
    void reReportWithChangedRoutesPushesAgain() {
        Recorder rec = new Recorder();
        List<ManifestRoute> live = new ArrayList<>();
        live.add(new ManifestRoute("POST", "/v1/payments"));
        ManifestReporter rep = baseBuilder(rec,
                ManifestSource.of("fastify", () -> List.copyOf(live))).build();
        assertTrue(rep.report());
        assertEquals(1, rec.bodies.size());
        // Operator added a runtime route.
        live.add(new ManifestRoute("POST", "/v1/refunds"));
        assertTrue(rep.report());
        assertEquals(2, rec.bodies.size());
        assertEquals(2, rec.bodies.get(1).get("routes").size());
    }

    @Test
    void non2xxResponseCountsAsFailure() {
        Recorder rec = new Recorder();
        rec.reply.set(500);
        ManifestReporter rep = baseBuilder(rec, sourceOf(
                new ManifestRoute("POST", "/v1/payments"))).build();
        assertFalse(rep.report());
        ManifestStatus st = rep.status();
        assertEquals(Boolean.FALSE, st.lastPushOk());
        assertNotNull(st.lastPushError());
        assertTrue(st.lastPushError().contains("500"));
        assertEquals(1, st.pushesFailed());
        assertEquals(0, st.pushesSucceeded());
    }

    @Test
    void thrownSourceCountsAsFailureAndNeverPosts() {
        Recorder rec = new Recorder();
        ManifestSource throwing = ManifestSource.of("fastify", () -> {
            throw new RuntimeException("walk boom");
        });
        ManifestReporter rep = baseBuilder(rec, throwing).build();
        assertFalse(rep.report());
        assertEquals(0, rec.bodies.size());
        ManifestStatus st = rep.status();
        assertEquals(1, st.pushesFailed());
        assertNotNull(st.lastPushError());
        assertTrue(st.lastPushError().contains("walk boom"));
    }

    @Test
    void statusReflectsCounters() {
        Recorder rec = new Recorder();
        ManifestReporter rep = baseBuilder(rec, sourceOf(
                new ManifestRoute("POST", "/v1/payments"))).build();
        ManifestStatus st0 = rep.status();
        assertTrue(st0.enabled());
        assertNull(st0.lastReportedAt());
        assertEquals(0, st0.pushAttempts());

        rep.report();
        ManifestStatus st1 = rep.status();
        assertEquals(1, st1.pushAttempts());
        assertEquals(1, st1.pushesSucceeded());
        assertEquals(Boolean.TRUE, st1.lastPushOk());
        assertEquals(1, st1.routeCount());
        assertNotNull(st1.lastReportedAt());
    }

    @Test
    void stopMakesReportNoOp() {
        Recorder rec = new Recorder();
        ManifestReporter rep = baseBuilder(rec, sourceOf(
                new ManifestRoute("POST", "/v1/payments"))).build();
        rep.stop();
        assertFalse(rep.report());
        assertEquals(0, rec.bodies.size());
    }

    @Test
    void disabledStatusBlock() {
        ManifestStatus st = ManifestStatus.disabled();
        assertFalse(st.enabled());
        assertNull(st.framework());
        assertEquals(0, st.routeCount());
    }

    @Test
    void routeFieldsRideAlong() {
        Recorder rec = new Recorder();
        ManifestRoute.Fields fields = new ManifestRoute.Fields(
                List.of(new ManifestRoute.Field("amount", "number")),
                null, null, null);
        ManifestReporter rep = baseBuilder(rec, sourceOf(
                new ManifestRoute("POST", "/v1/payments", "createPayment", fields))).build();
        rep.report();
        JsonNode route = rec.bodies.get(0).get("routes").get(0);
        assertEquals("createPayment", route.get("handler").asText());
        assertEquals("amount", route.get("fields").get("body").get(0).get("name").asText());
        assertEquals("number", route.get("fields").get("body").get(0).get("type").asText());
        // Other locations serialised as empty lists.
        assertEquals(0, route.get("fields").get("query").size());
    }

    @Test
    @DisplayName("one path dialect reaches the backend")
    void canonicalisesBracePathsToColonStyle() {
        // Five frameworks published /api/items/{id} and eight /api/items/:id, so a rule written
        // from the console's route picker matched nothing — evaluation normalises to the colon
        // form. Doing it here means a hand-written custom source cannot get it wrong either.
        assertEquals("/api/items/:id", ManifestReporter.canonicalisePath("/api/items/{id}"));
        assertEquals("/api/items/:id/reviews/:reviewId",
                ManifestReporter.canonicalisePath("/api/items/{id}/reviews/{reviewId}"));
        // A converter spec is a routing constraint, not part of the path.
        assertEquals("/api/items/:id", ManifestReporter.canonicalisePath("/api/items/{id:int}"));
        assertEquals("/api/items/:id", ManifestReporter.canonicalisePath("/api/items/:id"));
        assertEquals("/health", ManifestReporter.canonicalisePath("/health"));
    }
}
