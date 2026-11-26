package com.klswitch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Header construction for every backend call (rules poll,
 * decisions).
 *
 * Built centrally so the wire-level shape stays consistent across
 * pollers / reporters and so SDK-controlled headers (auth, instance
 * key, user agent) can't be accidentally overridden by the operator's
 * {@code extraHeaders}.
 *
 * {@code X-Env-Hint} is observational — the backend derives the
 * authoritative env from the token. The hint exists so a misconfigured
 * deploy (prod token distributed to staging pods) is loud rather
 * than silent.
 */
public final class RequestHeaders {

    private static final List<String> ENV_HINT_VARS = List.of(
            "KS_ENV",
            "POD_NAMESPACE",
            "K_SERVICE_REVISION");

    private static final List<String> RESERVED = Arrays.asList(
            "authorization",
            "x-instance-key",
            "user-agent");

    private RequestHeaders() {}

    /**
     * Build the headers for one outbound call. {@code envHint} may be
     * null — in that case the resolver walks {@code env} for a value.
     */
    public static Map<String, String> build(
            String token,
            String instanceKey,
            String sdkVersion,
            Map<String, String> extraHeaders,
            String envHint,
            Function<String, String> env,
            Logger logger) {

        Map<String, String> headers = new LinkedHashMap<>();

        if (extraHeaders != null) {
            warnIfReservedOverridden(extraHeaders, logger);
            headers.putAll(extraHeaders);
        }

        // SDK-controlled headers go last so they always win over operator
        // extras (even if extras tried to set them).
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Instance-Key", instanceKey);
        headers.put("User-Agent", "klswitch-java/" + sdkVersion);
        headers.put("Accept-Encoding", "gzip");

        String resolvedHint = envHint != null ? envHint : resolveEnvHint(env);
        if (resolvedHint != null && !resolvedHint.isEmpty()) {
            headers.put("X-Env-Hint", resolvedHint);
        }
        return headers;
    }

    /** First non-empty value from the standard env-hint variables. */
    public static String resolveEnvHint(Function<String, String> env) {
        if (env == null) {
            return null;
        }
        for (String v : ENV_HINT_VARS) {
            String value = env.apply(v);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void warnIfReservedOverridden(Map<String, String> extras, Logger logger) {
        if (logger == null) {
            return;
        }
        for (String key : extras.keySet()) {
            if (RESERVED.contains(key.toLowerCase(Locale.ROOT))) {
                logger.warn(
                        "extraHeaders includes a reserved header — SDK value wins",
                        Map.of("header", key));
            }
        }
    }
}
