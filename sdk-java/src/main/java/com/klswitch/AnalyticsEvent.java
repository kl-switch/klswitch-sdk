package com.klswitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Per-evaluation analytics event. Wire-format frozen by the console
 * contract ({@code klswitch-console/src/lib/zod/analytics.ts}).
 *
 * The backend enriches the event with {@code id}, {@code orgId},
 * {@code serviceId}, {@code envId}, and {@code instanceId} (the last from
 * the {@code X-Instance-Key} header) before persisting; the wrapper sends
 * the fields below verbatim.
 *
 * Never includes raw facts — only the templated request shape +
 * matched-rule outcomes.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AnalyticsEvent(
        /* Polymorphic discriminator for the analytics ingest
         * (POST /v1/analytics). Only "evaluation" exists today. */
        @JsonProperty("type")          String type,
        /* Client-generated UUID v4 idempotency key, minted once at event
         * birth and stable for its lifetime. The backend enforces
         * UNIQUE(eventId) so a redelivered batch collapses to a single
         * stored decision. */
        @JsonProperty("eventId")       String eventId,
        @JsonProperty("occurredAt")    String occurredAt,         // ISO 8601 UTC
        @JsonProperty("rulesVersion")  long rulesVersion,
        @JsonProperty("request")       Request request,
        @JsonProperty("matched")       List<MatchedAction> matched,
        @JsonProperty("evaluated")     long evaluated,
        @JsonProperty("skipped")       long skipped,
        @JsonProperty("latencyBucket") LatencyBucket latencyBucket,
        /* Schema-drift Layer 3: deduplicated, sorted list of field paths
         * that matched rules referenced but the request input didn't
         * carry. Always present on the wire (empty list when none) so
         * console queries don't have to special-case null. */
        @JsonProperty("missingFields") List<String> missingFields) {

    /** Discriminator value for evaluation events. */
    public static final String TYPE = "evaluation";

    /** Mint a fresh UUID v4 idempotency key. */
    public static String newEventId() {
        return UUID.randomUUID().toString();
    }

    /** Convenience overload: auto-fills {@code type} + a fresh idempotency key. */
    public AnalyticsEvent(
            String occurredAt, long rulesVersion, Request request,
            List<MatchedAction> matched, long evaluated, long skipped,
            LatencyBucket latencyBucket, List<String> missingFields) {
        this(TYPE, newEventId(), occurredAt, rulesVersion, request, matched, evaluated,
                skipped, latencyBucket, missingFields);
    }

    /** Convenience overload — most call sites in tests omit missingFields. */
    public AnalyticsEvent(
            String occurredAt, long rulesVersion, Request request,
            List<MatchedAction> matched, long evaluated, long skipped,
            LatencyBucket latencyBucket) {
        this(occurredAt, rulesVersion, request, matched, evaluated, skipped,
                latencyBucket, List.of());
    }

    /** Templated request shape — `{ method: "POST", path: "/v1/payments/:id" }`. */
    public record Request(
            @JsonProperty("method") String method,
            @JsonProperty("path")   String path) {}

    /** One per matched rule. Denormalised so the console can render badges without joining. */
    public record MatchedAction(
            @JsonProperty("ruleId")     String ruleId,
            @JsonProperty("actionType") String actionType) {}
}
