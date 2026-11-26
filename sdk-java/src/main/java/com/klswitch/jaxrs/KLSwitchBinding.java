package com.klswitch.jaxrs;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource method (or class) to be guarded by the {@link KLSwitchFilter},
 * and declares the canonical templated {@code path} the engine should match.
 *
 * Because the engine matches paths exactly (and rule paths are author-chosen,
 * e.g. {@code /v1/payments/:id}), the path is declared here rather than derived
 * from JAX-RS's {@code {id}} templates — so it always matches your ruleset.
 *
 * <pre>{@code
 * @POST @Path("/{id}")
 * @KLSwitchBinding(path = "/v1/payments/:id")
 * public Response collect(...) { ... }
 * }</pre>
 */
@NameBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface KLSwitchBinding {
    /**
     * Canonical templated path matching your ruleset (default: the concrete request path).
     *
     * <p>Set this on any templated route. If omitted, the filter falls back to the
     * concrete request path (e.g. {@code /v1/payments/abc}), which will <em>not</em>
     * match a templated rule path like {@code /v1/payments/:id} — the request then
     * matches no rule and passes through. Leave it unset only for static paths.
     */
    String path() default "";
}
