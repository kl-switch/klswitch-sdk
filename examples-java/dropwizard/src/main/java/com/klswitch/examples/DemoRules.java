package com.klswitch.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the demo ruleset JSON from the classpath. */
public final class DemoRules {

    private DemoRules() {}

    public static String json() {
        try (InputStream in = DemoRules.class.getResourceAsStream("/demo-rules.json")) {
            if (in == null) {
                throw new IllegalStateException("demo-rules.json not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
