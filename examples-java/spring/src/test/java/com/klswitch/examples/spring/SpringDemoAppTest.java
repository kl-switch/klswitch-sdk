package com.klswitch.examples.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.Client;
import com.klswitch.ManifestRoute;
import com.klswitch.spring.SpringManifestSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Boots the real app on a real port and drives it over HTTP — embedded Tomcat,
 * the real filter chain, the real DispatcherServlet. Nothing here is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringDemoAppTest {

    @Autowired TestRestTemplate http;
    @Autowired RequestMappingHandlerMapping requestMappings;
    @Autowired Client ks;

    private ResponseEntity<String> postPayment(String id, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/v1/payments/" + id, HttpMethod.POST,
                new HttpEntity<>(json, h), String.class);
    }

    @Test
    void largePaymentIsBlockedOnATemplatedRoute() {
        // The demo rule selects /v1/payments/:id. Without the Spring resolver the
        // engine would see /v1/payments/8123 and this would return 200.
        ResponseEntity<String> res = postPayment("8123", "{\"amount\":70000}");

        assertEquals(429, res.getStatusCode().value());
        assertTrue(res.getBody().contains("\"blocked\":true"), res.getBody());
        assertTrue(res.getBody().contains("block_large_payment"), res.getBody());
    }

    @Test
    void restrictedRegionWinsOnPriority() {
        // Both payment rules match; block_restricted_region has priority 110
        // against 100, and the filter acts on the first matched rule.
        ResponseEntity<String> res = postPayment("8123", "{\"amount\":70000,\"country\":\"KP\"}");

        assertEquals(451, res.getStatusCode().value());
        assertTrue(res.getBody().contains("block_restricted_region"), res.getBody());
    }

    @Test
    void smallPaymentReachesTheControllerWithItsBodyIntact() {
        ResponseEntity<String> res = postPayment("8123", "{\"amount\":10,\"country\":\"GB\"}");

        assertEquals(200, res.getStatusCode().value());
        // Proves the filter's body buffering replayed the stream: the controller
        // deserialised the same JSON the engine read for facts.
        assertTrue(res.getBody().contains("\"paymentId\":\"8123\""), res.getBody());
        assertTrue(res.getBody().contains("\"amount\":10"), res.getBody());
        assertTrue(res.getBody().contains("\"country\":\"GB\""), res.getBody());
    }

    @Test
    void flagActionDoesNotBlockAndIsReadableInTheController() {
        ResponseEntity<String> flagged =
                http.getForEntity("/v1/users/7?role=admin", String.class);
        assertEquals(200, flagged.getStatusCode().value());
        assertTrue(flagged.getBody().contains("flag_admin_access"), flagged.getBody());

        ResponseEntity<String> plain = http.getForEntity("/v1/users/7", String.class);
        assertEquals(200, plain.getStatusCode().value());
        assertFalse(plain.getBody().contains("flag_admin_access"), plain.getBody());
    }

    @Test
    void headerConditionAlsoFlags() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Impersonate", "ops@example.com");
        ResponseEntity<String> res = http.exchange("/v1/users/7", HttpMethod.GET,
                new HttpEntity<>(h), String.class);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().contains("flag_admin_access"), res.getBody());
    }

    @Test
    void unmappedRouteStillReaches404RatherThanFailingInTheFilter() {
        assertEquals(404, http.getForEntity("/nothing/here", String.class).getStatusCode().value());
    }

    @Test
    void manifestReportsTheControllerRoutesTheRulesTarget() {
        List<ManifestRoute> routes = SpringManifestSource.discover(requestMappings);
        List<String> keys = routes.stream().map(r -> r.method() + " " + r.path()).toList();

        assertTrue(keys.contains("POST /v1/payments/:id"), keys.toString());
        assertTrue(keys.contains("GET /v1/users/:id"), keys.toString());
        // Not the DispatcherServlet at "/", which is all ServletManifestSource
        // would have found in a Spring Boot app.
        assertFalse(keys.stream().anyMatch(k -> k.endsWith(" /")), keys.toString());
    }

    @Test
    void engineIsInitialisedAndServingTheDemoRuleset() {
        var status = ks.getStatus();
        assertTrue(status.initialized());
        assertEquals(3, status.ruleCount(), "demo-rules.json carries three rules");
    }

    @Test
    void guardIsMountedExactlyOnce() {
        // Two guards would double every analytics decision count. Mounting a
        // second one on the app's client must fail loudly.
        assertThrowsIllegalState(() -> new com.klswitch.servlet.KLSwitchFilter(ks));
    }

    private static void assertThrowsIllegalState(Runnable r) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, r::run);
    }
}
