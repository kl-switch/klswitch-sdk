package com.klswitch.examples;

import com.klswitch.Client;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import com.klswitch.RequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the engine's evaluation input from request parts and applies a simple
 * block policy. This is the JAX-RS analogue of the Node adapter / Phase 13's
 * RequestContext — the resource calls it; the engine never interprets actions.
 */
public final class KLSwitchGuard {

    private final Client ks;

    public KLSwitchGuard(Client ks) {
        this.ks = ks;
    }

    public EvaluationResult evaluate(
            String method,
            String templatedPath,
            Map<String, Object> headers,
            Map<String, Object> query,
            Map<String, Object> params,
            Map<String, Object> body) {
        // Input construction lives in the SDK (RequestContext); the demo only
        // supplies the request parts and applies its own block policy below.
        return ks.evaluate(RequestContext.toEvaluationInput(method, templatedPath, headers, query, params, body));
    }

    /** First matched rule whose action is {@code block}, if any. */
    public Optional<MatchedRule> blocked(EvaluationResult result) {
        return result.matched().stream().filter(m -> "block".equals(m.action().type())).findFirst();
    }

    /** JAX-RS response for a blocking match; status comes from the action payload. */
    public Response blockResponse(MatchedRule rule) {
        int status = 403;
        Map<String, Object> payload = rule.action().payload();
        if (payload != null && payload.get("statusCode") instanceof Number n) {
            status = n.intValue();
        }
        return Response.status(status)
                .entity(Map.<String, Object>of("blocked", true, "ruleId", rule.id(), "action", rule.action()))
                .build();
    }

    /** Compact view of matched rules for response bodies. */
    public static List<Map<String, String>> matchedSummary(EvaluationResult result) {
        return result.matched().stream()
                .map(m -> Map.of("id", m.id(), "action", m.action().type()))
                .toList();
    }

    /** Lowercased single-valued header map (the engine matches lowercase keys, like Node). */
    public static Map<String, Object> headers(HttpHeaders headers) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : headers.getRequestHeaders().entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isEmpty() ? null : e.getValue().get(0));
        }
        return out;
    }

    /** First value of each multi-valued entry (for query params). */
    public static Map<String, Object> firstValues(MultivaluedMap<String, String> mv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : mv.entrySet()) {
            out.put(e.getKey(), e.getValue().isEmpty() ? null : e.getValue().get(0));
        }
        return out;
    }
}
