package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The regression: a host integrating without an adapter emitted Spring/FastAPI-style {@code {id}}
 * templates. Rules whose selectors carried no path parameter still matched, so the integration
 * looked healthy — only the parameterised rules silently missed. Six of eight checks passed,
 * which is exactly the shape that hides a broken integration.
 */
class TemplateCanonicalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsEveryCommonDialect() {
        assertEquals("/api/items/:id", RequestContext.canonicalizeTemplate("/api/items/{id}"));
        assertEquals("/api/items/:id", RequestContext.canonicalizeTemplate("/api/items/<id>"));
        assertEquals("/api/items/:id", RequestContext.canonicalizeTemplate("/api/items/<int:id>"));
        assertEquals("/api/items/:id", RequestContext.canonicalizeTemplate("/api/items/:id"));
    }

    @Test
    void dropsConverterPrefixesAndSuffixesKeepingTheName() {
        assertEquals("/static/:file_path",
                RequestContext.canonicalizeTemplate("/static/{file_path:path}"));
        assertEquals("/static/:filename",
                RequestContext.canonicalizeTemplate("/static/<path:filename>"));
    }

    @Test
    void handlesSeveralParametersInOnePath() {
        assertEquals("/api/items/:id/reviews/:reviewId",
                RequestContext.canonicalizeTemplate("/api/items/{id}/reviews/{reviewId}"));
    }

    @Test
    void leavesPathsWithoutParametersAlone() {
        assertEquals("/api/items", RequestContext.canonicalizeTemplate("/api/items"));
        assertEquals("/api/payments/*/refund",
                RequestContext.canonicalizeTemplate("/api/payments/*/refund"));
        assertEquals("/api/items/**", RequestContext.canonicalizeTemplate("/api/items/**"));
    }

    @Test
    void isAppliedByToEvaluationInputNotJustAvailable() throws Exception {
        // Being available was the old state — the caller had to know the engine's dialect.
        String json = RequestContext.toEvaluationInput(
                "post", "/api/payments/{id}/refund", Map.of(), Map.of(), Map.of(), null);
        JsonNode keys = MAPPER.readTree(json).path("selector").path("keys");
        assertEquals("/api/payments/:id/refund", keys.path("path").asText());
        assertEquals("POST", keys.path("method").asText());
    }
}
