package com.klswitch.examples;

import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Map;
import java.util.Optional;

@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
public class UsersResource {

    private final KLSwitchGuard guard;
    private final UserService service;

    public UsersResource(KLSwitchGuard guard, UserService service) {
        this.guard = guard;
        this.service = service;
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id, @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        Map<String, Object> query = KLSwitchGuard.firstValues(uriInfo.getQueryParameters());
        EvaluationResult result = guard.evaluate(
                "GET", "/v1/users/:id", KLSwitchGuard.headers(headers), query, Map.of("id", id), Map.of());

        Optional<MatchedRule> block = guard.blocked(result);
        if (block.isPresent()) {
            return guard.blockResponse(block.get());
        }
        return Response.ok(Map.<String, Object>of(
                        "result", service.fetch(id),
                        "matched", KLSwitchGuard.matchedSummary(result)))
                .build();
    }
}
