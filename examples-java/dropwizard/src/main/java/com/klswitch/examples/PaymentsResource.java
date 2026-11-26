package com.klswitch.examples;

import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;

@Path("/v1/payments")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentsResource {

    private final KLSwitchGuard guard;
    private final PaymentService service;

    public PaymentsResource(KLSwitchGuard guard, PaymentService service) {
        this.guard = guard;
        this.service = service;
    }

    @POST
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response collect(@PathParam("id") String id, @Context HttpHeaders headers, Map<String, Object> body) {
        EvaluationResult result = guard.evaluate(
                "POST", "/v1/payments/:id", KLSwitchGuard.headers(headers), Map.of(), Map.of("id", id), body);

        Optional<MatchedRule> block = guard.blocked(result);
        if (block.isPresent()) {
            return guard.blockResponse(block.get());
        }
        return Response.ok(Map.<String, Object>of(
                        "result", service.collect(id, body),
                        "matched", KLSwitchGuard.matchedSummary(result)))
                .build();
    }
}
