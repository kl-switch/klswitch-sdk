package com.klswitch.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klswitch.Client;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import com.klswitch.RequestContext;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared implementation behind {@link KLSwitchFilter} (opt-in, name-bound)
 * and {@link KLSwitchGlobalFilter} (every resource). The only difference
 * between the two is which resources JAX-RS applies them to, which is decided
 * by whether the concrete class carries {@link KLSwitchBinding} — so that
 * annotation must live on the subclass, never here.
 *
 * <p>Thin by design: it maps the request to the engine's input via
 * {@link RequestContext} and applies a block policy. It does not interpret
 * actions beyond "block → abort". Lives in the wrapper, not WASM.
 */
public abstract class AbstractKLSwitchFilter implements ContainerRequestFilter {

    /** Request property holding the {@link EvaluationResult} for downstream code. */
    public static final String RESULT_PROPERTY = "com.klswitch.result";

    /**
     * Maximum request-body bytes the filter will read for fact extraction. Bigger
     * bodies are streamed through to the resource without being buffered here, so
     * the filter can't be DoS'd by an oversized JSON payload. The resource's own
     * deserializer still sees the full body (it's reconstructed via SequenceInputStream).
     */
    private static final int MAX_BODY_BYTES_FOR_FACTS = 1_048_576; // 1 MB

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Client ks;

    @Context
    private ResourceInfo resourceInfo;

    protected AbstractKLSwitchFilter(Client ks, String mountDescription) {
        if (ks == null) throw new IllegalArgumentException("ks must not be null");
        ks.registerGuardMount(mountDescription);
        this.ks = ks;
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String input = RequestContext.toEvaluationInput(
                ctx.getMethod(),
                declaredPath(ctx),
                firstValues(ctx.getHeaders(), true),
                firstValues(ctx.getUriInfo().getQueryParameters(), false),
                firstValues(ctx.getUriInfo().getPathParameters(), false),
                readJsonBody(ctx));

        EvaluationResult result = ks.evaluate(input);
        ctx.setProperty(RESULT_PROPERTY, result);

        Optional<MatchedRule> block = result.matched().stream()
                .filter(m -> m.action() != null && "block".equals(m.action().type()))
                .findFirst();
        if (block.isPresent()) {
            MatchedRule rule = block.get();
            int status = 403;
            Map<String, Object> payload = rule.action().payload();
            if (payload != null && payload.get("statusCode") instanceof Number n) {
                status = n.intValue();
            }
            ctx.abortWith(Response.status(status)
                    .entity(Map.of("blocked", true, "ruleId", rule.id(), "action", rule.action()))
                    .build());
        }
    }

    /**
     * The templated path to evaluate against, in the engine's {@code :id} dialect.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link KLSwitchBinding#path()} on the method, then the class — the
     *       operator has stated the canonical path they wrote rules against, so
     *       it wins outright.</li>
     *   <li>The resource's own {@code @Path} annotations, class + method joined
     *       and converted from {@code {id}} to {@code :id}. This is the same
     *       derivation {@link JaxRsManifestSource} reports to the console, so a
     *       rule built from the console's suggestion matches without the operator
     *       annotating anything.</li>
     *   <li>The concrete request path, as a last resort. Matches only exact
     *       selectors or {@code *} path patterns.</li>
     * </ol>
     */
    private String declaredPath(ContainerRequestContext ctx) {
        if (resourceInfo != null) {
            Method resourceMethod = resourceInfo.getResourceMethod();
            Class<?> resourceClass = resourceInfo.getResourceClass();

            if (resourceMethod != null) {
                KLSwitchBinding binding = resourceMethod.getAnnotation(KLSwitchBinding.class);
                if (binding == null && resourceClass != null) {
                    binding = resourceClass.getAnnotation(KLSwitchBinding.class);
                }
                if (binding != null && !binding.path().isEmpty()) {
                    return binding.path();
                }
            }

            String derived = derivedFromJaxRsPaths(resourceClass, resourceMethod);
            if (derived != null) {
                return derived;
            }
        }
        return "/" + ctx.getUriInfo().getPath();
    }

    /** Join the class-level and method-level {@code @Path} into an engine path, or null. */
    private static String derivedFromJaxRsPaths(Class<?> resourceClass, Method resourceMethod) {
        String base = "";
        if (resourceClass != null) {
            Path classPath = resourceClass.getAnnotation(Path.class);
            if (classPath != null) base = JaxRsManifestSource.normaliseSegment(classPath.value());
        }
        String sub = "";
        if (resourceMethod != null) {
            Path methodPath = resourceMethod.getAnnotation(Path.class);
            if (methodPath != null) sub = JaxRsManifestSource.normaliseSegment(methodPath.value());
        }
        if (base.isEmpty() && sub.isEmpty()) {
            return null;
        }
        return JaxRsManifestSource.convertToColonStyle(JaxRsManifestSource.joinPaths(base, sub));
    }

    private Object readJsonBody(ContainerRequestContext ctx) throws IOException {
        if (!ctx.hasEntity()) {
            return null;
        }
        MediaType type = ctx.getMediaType();
        if (type == null || !type.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
            return null;
        }

        // Read at most MAX+1 bytes. If the body is larger, we splice the
        // already-read prefix back in front of the original stream so the
        // resource still sees the full body — and we skip body facts (the
        // engine treats missing facts as leaf-false).
        InputStream original = ctx.getEntityStream();
        byte[] buffer = original.readNBytes(MAX_BODY_BYTES_FOR_FACTS + 1);
        boolean truncated = buffer.length > MAX_BODY_BYTES_FOR_FACTS;
        ctx.setEntityStream(
                truncated
                        ? new SequenceInputStream(new ByteArrayInputStream(buffer), original)
                        : new ByteArrayInputStream(buffer));

        if (buffer.length == 0 || truncated) {
            return null;
        }
        try {
            return MAPPER.readValue(buffer, Object.class);
        } catch (IOException malformed) {
            // Fail safe: a malformed body must not 500 before any rule runs. The
            // engine then sees no body facts (missing field → leaf false). The
            // resource's own deserializer still surfaces the error to the client.
            return null;
        }
    }

    private static Map<String, Object> firstValues(MultivaluedMap<String, String> mv, boolean lowercaseKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : mv.entrySet()) {
            String key = lowercaseKeys ? e.getKey().toLowerCase(Locale.ROOT) : e.getKey();
            out.put(key, e.getValue().isEmpty() ? null : e.getValue().get(0));
        }
        return out;
    }
}
