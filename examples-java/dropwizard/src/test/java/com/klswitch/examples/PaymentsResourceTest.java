package com.klswitch.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.Client;
import com.klswitch.Config;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Resource-layer test: runs PaymentsResource in an in-memory Jersey container
 * (no Jetty) and drives the KLSwitch policy through real HTTP semantics.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class PaymentsResourceTest {

    private static final Client KS = Client.init(Config.builder().rules(DemoRules.json()).build());

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(Jackson.newObjectMapper())
            .addResource(new PaymentsResource(new KLSwitchGuard(KS), new PaymentService()))
            .build();

    @Test
    void allowsNormalPayment() {
        try (Response r = EXT.target("/v1/payments/abc").request().post(Entity.json(Map.of("amount", 100)))) {
            assertEquals(200, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("\"matched\":[]"));
        }
    }

    @Test
    void blocksLargePayment() {
        try (Response r = EXT.target("/v1/payments/abc").request().post(Entity.json(Map.of("amount", 70000)))) {
            assertEquals(429, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("block_large_payment"));
        }
    }

    @Test
    void blocksRestrictedRegion() {
        try (Response r = EXT.target("/v1/payments/abc")
                .request()
                .post(Entity.json(Map.of("amount", 10, "country", "KP")))) {
            assertEquals(451, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("block_restricted_region"));
        }
    }
}
