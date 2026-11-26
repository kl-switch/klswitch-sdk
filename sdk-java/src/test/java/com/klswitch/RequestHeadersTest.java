package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RequestHeadersTest {

    private static Function<String, String> stubEnv(Map<String, String> env) {
        return env::get;
    }

    @Test
    void coreHeadersAlwaysSet() {
        Map<String, String> h = RequestHeaders.build(
                "ks_live_test", "pod-7f4a", "0.1.0",
                null, null, stubEnv(Map.of()), null);
        assertEquals("Bearer ks_live_test", h.get("Authorization"));
        assertEquals("pod-7f4a",            h.get("X-Instance-Key"));
        assertEquals("klswitch-java/0.1.0", h.get("User-Agent"));
        assertEquals("gzip",                h.get("Accept-Encoding"));
    }

    @Test
    void extraHeadersAreMergedButCannotOverrideReserved() {
        Map<String, String> extras = new HashMap<>();
        extras.put("X-Custom", "value");
        extras.put("Authorization", "Bearer ATTACK");
        extras.put("x-instance-key", "ATTACK");

        Map<String, String> h = RequestHeaders.build(
                "ks_live_test", "i", "0.1.0", extras, null, stubEnv(Map.of()), null);
        assertEquals("value",               h.get("X-Custom"));
        assertEquals("Bearer ks_live_test", h.get("Authorization"));
        assertEquals("i",                   h.get("X-Instance-Key"));
    }

    @Test
    void envHintIncludedWhenKsEnvSet() {
        Map<String, String> h = RequestHeaders.build(
                "t", "i", "0", null, null,
                stubEnv(Map.of("KS_ENV", "staging")), null);
        assertEquals("staging", h.get("X-Env-Hint"));
    }

    @Test
    void envHintIncludedWhenOnlyPodNamespaceSet() {
        Map<String, String> h = RequestHeaders.build(
                "t", "i", "0", null, null,
                stubEnv(Map.of("POD_NAMESPACE", "prod")), null);
        assertEquals("prod", h.get("X-Env-Hint"));
    }

    @Test
    void envHintOmittedWhenNothingResolvable() {
        Map<String, String> h = RequestHeaders.build(
                "t", "i", "0", null, null, stubEnv(Map.of()), null);
        assertFalse(h.containsKey("X-Env-Hint"));
    }

    @Test
    void explicitEnvHintWinsOverEnvVars() {
        Map<String, String> h = RequestHeaders.build(
                "t", "i", "0", null, "explicit",
                stubEnv(Map.of("KS_ENV", "from-env")), null);
        assertEquals("explicit", h.get("X-Env-Hint"));
    }

    @Test
    void resolveEnvHintChainOrder() {
        assertEquals("a", RequestHeaders.resolveEnvHint(
                stubEnv(Map.of("KS_ENV", "a", "POD_NAMESPACE", "b", "K_SERVICE_REVISION", "c"))));
        assertEquals("b", RequestHeaders.resolveEnvHint(
                stubEnv(Map.of("POD_NAMESPACE", "b", "K_SERVICE_REVISION", "c"))));
        assertEquals("c", RequestHeaders.resolveEnvHint(
                stubEnv(Map.of("K_SERVICE_REVISION", "c"))));
        assertNull(RequestHeaders.resolveEnvHint(stubEnv(Map.of())));
    }

    @Test
    void resolveEnvHintSkipsEmpty() {
        assertEquals("b", RequestHeaders.resolveEnvHint(
                stubEnv(Map.of("KS_ENV", "", "POD_NAMESPACE", "b"))));
        assertEquals("b", RequestHeaders.resolveEnvHint(
                stubEnv(Map.of("KS_ENV", "   ", "POD_NAMESPACE", "b"))));
    }

    @Test
    void emptyEnvMapStillProducesCoreHeaders() {
        Map<String, String> h = RequestHeaders.build(
                "t", "i", "0", null, null, stubEnv(Map.of()), null);
        assertTrue(h.containsKey("Authorization"));
        assertTrue(h.containsKey("X-Instance-Key"));
        assertTrue(h.containsKey("User-Agent"));
    }
}
