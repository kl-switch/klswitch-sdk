package com.klswitch;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Instance-key resolution.
 *
 * The instance key answers "which runtime is this?". The token answers
 * "which service is this?". They're complementary: one token is shared
 * across every pod of a service; each pod has a distinct instance key.
 *
 * Resolution order (first valid wins):
 *
 *   1. config.instanceId               — operator passed it explicitly
 *   2. KS_INSTANCE_ID env var          — env-only config
 *   3. POD_NAME env var                — k8s downward API convention
 *   4. {@code hostname-pid-startMs}    — deterministic per-process fallback
 *
 * Invalid values (charset / length) are skipped with a warning and the
 * chain continues — never crash, the operator is trying to give a hint
 * not block their app boot.
 */
public final class InstanceId {

    public enum Source { CONFIG, ENV, POD, DERIVED }

    public record Resolution(String value, Source source) {}

    /** Max accepted instance-id length. Tracks the cross-language contract. */
    public static final int MAX_LEN = 128;
    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9_\\-.:/]+$");

    private InstanceId() {}

    /** True iff the input is non-empty and matches the soft-validation rules. */
    public static boolean isValid(String v) {
        if (v == null) {
            return false;
        }
        String trimmed = v.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LEN) {
            return false;
        }
        return VALID.matcher(trimmed).matches();
    }

    /**
     * Resolve the instance key, walking the fallback chain. The result and
     * the source it came from are returned together so the caller can log
     * which mechanism won — useful when an operator's k8s-downward
     * POD_NAME isn't arriving in production.
     */
    public static Resolution resolve(String configValue, java.util.function.Function<String, String> env, Logger logger) {
        Resolution[] candidates = new Resolution[]{
                new Resolution(configValue,                Source.CONFIG),
                new Resolution(env.apply("KS_INSTANCE_ID"), Source.ENV),
                new Resolution(env.apply("POD_NAME"),       Source.POD),
        };

        for (Resolution c : candidates) {
            if (c.value() == null || c.value().isEmpty()) {
                continue;
            }
            if (isValid(c.value())) {
                return new Resolution(c.value().trim(), c.source());
            }
            if (logger != null) {
                logger.warn(
                        "instanceId candidate rejected — falling through",
                        java.util.Map.of("source", c.source().name(),
                                         "reason", "value violates charset/length rules"));
            }
        }
        return new Resolution(derive(), Source.DERIVED);
    }

    /** Convenience overload that pulls env from {@link System#getenv}. */
    public static Resolution resolve(String configValue, Logger logger) {
        return resolve(configValue, System::getenv, logger);
    }

    /**
     * Deterministic per-process derivation when the operator hasn't
     * supplied a value. The pid + startTime distinguish restarts on
     * the same host within the same second.
     */
    private static String derive() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            host = "unknown";
        }
        // Hostnames can contain characters outside our validation charset
        // (rarely, but possible). Replace anything we don't allow with `-`.
        host = host.replaceAll("[^a-zA-Z0-9_\\-.:/]+", "-");
        if (host.isEmpty()) {
            host = "unknown";
        }
        long pid = ManagementFactory.getRuntimeMXBean().getPid();
        long startMs = System.currentTimeMillis();
        return host + "-" + pid + "-" + startMs;
    }
}
