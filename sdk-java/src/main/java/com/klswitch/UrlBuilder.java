package com.klswitch;

/**
 * URL derivation from the config's {@code baseUrl} (with per-endpoint
 * overrides as escape hatches for self-hosted setups).
 *
 * Centralised so the polling manager and analytics reporter (A3) all
 * build their URLs identically.
 */
public final class UrlBuilder {

    public static final String DEFAULT_BASE_URL = "https://api.klswitch.dev";

    public record Resolved(String rules, String decisions, String manifest) {}

    private UrlBuilder() {}

    public static Resolved resolve(
            String baseUrl,
            String rulesUrl,
            String decisionsUrl,
            String manifestUrl) {
        String base = stripTrailingSlash(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        return new Resolved(
                rulesUrl     != null ? rulesUrl     : base + "/v1/ruleset",
                decisionsUrl != null ? decisionsUrl : base + "/v1/analytics",
                manifestUrl  != null ? manifestUrl  : base + "/v1/manifest");
    }

    /** Backwards-compatible overload — defaults the manifest URL from {@code baseUrl}. */
    public static Resolved resolve(String baseUrl, String rulesUrl, String decisionsUrl) {
        return resolve(baseUrl, rulesUrl, decisionsUrl, /*manifestUrl=*/ null);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
