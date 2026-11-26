package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InstanceIdTest {

    @Test
    void validAcceptsAllowedCharset() {
        assertTrue(InstanceId.isValid("pod-7f4a"));
        assertTrue(InstanceId.isValid("payments-api-prod-0"));
        assertTrue(InstanceId.isValid("arn:aws:ecs:us-east-1:123/cluster/task"));
        assertTrue(InstanceId.isValid("a"));
        assertTrue(InstanceId.isValid("A_B.C-D:E/F"));
    }

    @Test
    void validRejectsEmptyAndBadChars() {
        assertFalse(InstanceId.isValid(null));
        assertFalse(InstanceId.isValid(""));
        assertFalse(InstanceId.isValid("   "));
        assertFalse(InstanceId.isValid("has spaces"));
        assertFalse(InstanceId.isValid("with#special"));
        assertFalse(InstanceId.isValid("a".repeat(InstanceId.MAX_LEN + 1)));
    }

    @Test
    void explicitConfigWinsOverEnv() {
        InstanceId.Resolution r = InstanceId.resolve(
                "from-config",
                stubEnv(Map.of("KS_INSTANCE_ID", "from-env", "POD_NAME", "from-pod")),
                null);
        assertEquals("from-config", r.value());
        assertEquals(InstanceId.Source.CONFIG, r.source());
    }

    @Test
    void ksInstanceIdEnvBeatsPodName() {
        InstanceId.Resolution r = InstanceId.resolve(
                null,
                stubEnv(Map.of("KS_INSTANCE_ID", "from-env", "POD_NAME", "from-pod")),
                null);
        assertEquals("from-env", r.value());
        assertEquals(InstanceId.Source.ENV, r.source());
    }

    @Test
    void podNameUsedWhenOnlyItIsSet() {
        InstanceId.Resolution r = InstanceId.resolve(
                null, stubEnv(Map.of("POD_NAME", "from-pod")), null);
        assertEquals("from-pod", r.value());
        assertEquals(InstanceId.Source.POD, r.source());
    }

    @Test
    void derivedFallbackWhenNothingSet() {
        InstanceId.Resolution r = InstanceId.resolve(null, stubEnv(Map.of()), null);
        assertEquals(InstanceId.Source.DERIVED, r.source());
        assertTrue(r.value().matches("[\\w\\-.:]+-\\d+-\\d+"));
    }

    @Test
    void invalidExplicitValueFallsThrough() {
        InstanceId.Resolution r = InstanceId.resolve(
                "has spaces", stubEnv(Map.of("KS_INSTANCE_ID", "from-env")), null);
        assertEquals("from-env", r.value());
        assertEquals(InstanceId.Source.ENV, r.source());
    }

    @Test
    void allInvalidFallsToDerived() {
        InstanceId.Resolution r = InstanceId.resolve(
                "bad!",
                stubEnv(Map.of("KS_INSTANCE_ID", "bad@", "POD_NAME", "bad#")),
                null);
        assertEquals(InstanceId.Source.DERIVED, r.source());
    }

    @Test
    void trimsSurroundingWhitespaceOnValidValue() {
        InstanceId.Resolution r = InstanceId.resolve("  pod-7f4a  ", stubEnv(Map.of()), null);
        assertEquals("pod-7f4a", r.value());
        assertEquals(InstanceId.Source.CONFIG, r.source());
    }

    private static java.util.function.Function<String, String> stubEnv(Map<String, String> env) {
        return env::get;
    }
}
