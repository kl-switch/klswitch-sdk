package com.klswitch.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.EvaluationResult;
import com.klswitch.jaxrs.KLSwitchBinding;
import com.klswitch.jaxrs.KLSwitchFilter;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the SDK's JAX-RS {@link KLSwitchFilter} (Phase 13) —
 * the resource carries no guard logic; the name-bound filter evaluates the
 * request, blocks on a {@code block} action, and otherwise attaches the result.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class KLSwitchFilterTest {

    private static final Client KS = Client.init(Config.builder().rules(DemoRules.json()).build());

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(Jackson.newObjectMapper())
            .addProvider(new KLSwitchFilter(KS))
            .addResource(new GuardedPayments())
            .addResource(new GuardedNoBodyBind())
            .build();

    @Path("/v1/payments")
    @Produces(MediaType.APPLICATION_JSON)
    public static class GuardedPayments {
        @POST
        @Path("/{id}")
        @Consumes(MediaType.APPLICATION_JSON)
        @KLSwitchBinding(path = "/v1/payments/:id") // canonical path the rules target
        public Response collect(
                @PathParam("id") String id,
                @Context ContainerRequestContext ctx,
                Map<String, Object> body) {
            EvaluationResult result = (EvaluationResult) ctx.getProperty(KLSwitchFilter.RESULT_PROPERTY);
            return Response.ok(Map.of(
                            "id", id,
                            "amount", body.get("amount"),
                            "evaluated", result == null ? -1 : result.evaluated()))
                    .build();
        }
    }

    /** Guards the same path but never binds the body — isolates the filter's body handling. */
    @Path("/v1/raw-payments")
    @Produces(MediaType.APPLICATION_JSON)
    public static class GuardedNoBodyBind {
        @POST
        @Path("/{id}")
        @Consumes(MediaType.APPLICATION_JSON)
        @KLSwitchBinding(path = "/v1/payments/:id")
        public Response collect(@PathParam("id") String id, @Context ContainerRequestContext ctx) {
            EvaluationResult result = (EvaluationResult) ctx.getProperty(KLSwitchFilter.RESULT_PROPERTY);
            return Response.ok(Map.of("id", id, "evaluated", result == null ? -1 : result.evaluated())).build();
        }
    }

    @Test
    void filterBlocksLargePayment() {
        try (Response r = EXT.target("/v1/payments/abc").request().post(Entity.json(Map.of("amount", 70000)))) {
            assertEquals(429, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("\"blocked\":true"));
        }
    }

    @Test
    void filterAllowsAndAttachesResultAndPreservesBody() {
        try (Response r = EXT.target("/v1/payments/abc").request().post(Entity.json(Map.of("amount", 50)))) {
            assertEquals(200, r.getStatus());
            String body = r.readEntity(String.class);
            assertTrue(body.contains("\"id\":\"abc\""), body);
            assertTrue(body.contains("\"amount\":50"), body); // body survived the filter's read+reset
            assertTrue(body.contains("\"evaluated\":2"), body); // both payment rules were considered
        }
    }

    @Test
    void filterFailsSafeOnMalformedJsonBody() {
        // A malformed body must not 500 in the filter; rules run with no body facts → pass through.
        try (Response r = EXT.target("/v1/raw-payments/abc")
                .request()
                .post(Entity.entity("{not valid json", MediaType.APPLICATION_JSON))) {
            assertEquals(200, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("\"evaluated\":2"));
        }
    }
}
