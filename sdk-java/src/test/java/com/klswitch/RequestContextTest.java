package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContextTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void buildsInputWithUppercaseMethodAndAllBuckets() throws Exception {
        String json = RequestContext.toEvaluationInput(
                "post", "/v1/payments/:id",
                Map.of("x-client-id", "a"),
                Map.of("q", "1"),
                Map.of("id", "9"),
                Map.of("amount", 100));
        JsonNode n = M.readTree(json);

        assertEquals("POST", n.at("/selector/keys/method").asText());
        assertEquals("/v1/payments/:id", n.at("/selector/keys/path").asText());
        assertTrue(n.at("/facts/headers").isObject());
        assertTrue(n.at("/facts/query").isObject());
        assertTrue(n.at("/facts/params").isObject());
        assertTrue(n.at("/facts/meta").isObject());
        assertEquals(100, n.at("/facts/body/amount").asInt());
    }

    @Test
    void nonObjectOrNullBodyBecomesEmptyObject() throws Exception {
        JsonNode str = M.readTree(RequestContext.toEvaluationInput("GET", "/p", null, null, null, "nope"));
        assertTrue(str.at("/facts/body").isObject());
        assertEquals(0, str.at("/facts/body").size());

        JsonNode nil = M.readTree(RequestContext.toEvaluationInput("GET", "/p", null, null, null, null));
        assertTrue(nil.at("/facts/body").isObject());
    }
}
