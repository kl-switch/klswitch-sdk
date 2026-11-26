package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UrlBuilderTest {

    @Test
    void defaultBaseUrlWhenNothingSupplied() {
        UrlBuilder.Resolved u = UrlBuilder.resolve(null, null, null);
        assertEquals(UrlBuilder.DEFAULT_BASE_URL + "/v1/ruleset", u.rules());
        assertEquals(UrlBuilder.DEFAULT_BASE_URL + "/v1/analytics", u.decisions());
        assertEquals(UrlBuilder.DEFAULT_BASE_URL + "/v1/manifest",  u.manifest());
    }

    @Test
    void manifestUrlOverrideTakesPrecedence() {
        UrlBuilder.Resolved u = UrlBuilder.resolve(
                "http://api", null, null, "http://custom/manifest");
        assertEquals("http://custom/manifest", u.manifest());
        // Other endpoints still derived from baseUrl.
        assertEquals("http://api/v1/ruleset", u.rules());
    }

    @Test
    void customBaseUrlFlowsToEveryEndpoint() {
        UrlBuilder.Resolved u = UrlBuilder.resolve("http://self-hosted:8080", null, null);
        assertEquals("http://self-hosted:8080/v1/ruleset", u.rules());
        assertEquals("http://self-hosted:8080/v1/analytics", u.decisions());
    }

    @Test
    void trailingSlashOnBaseUrlIsNormalized() {
        UrlBuilder.Resolved u = UrlBuilder.resolve("http://api/", null, null);
        assertEquals("http://api/v1/ruleset", u.rules());
    }

    @Test
    void perEndpointOverridesTakePrecedence() {
        UrlBuilder.Resolved u = UrlBuilder.resolve(
                "http://ignored",
                "http://custom/rules",
                "http://custom/dec");
        assertEquals("http://custom/rules", u.rules());
        assertEquals("http://custom/dec", u.decisions());
    }

    @Test
    void mixOfBaseAndSingleOverride() {
        UrlBuilder.Resolved u = UrlBuilder.resolve(
                "http://api.example.com",
                "http://separate-cdn/rules",
                null);
        assertEquals("http://separate-cdn/rules", u.rules());
        assertEquals("http://api.example.com/v1/analytics", u.decisions());
    }
}
