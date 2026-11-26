package com.klswitch.jaxrs;

import com.klswitch.Client;

/**
 * JAX-RS request filter that evaluates <strong>every</strong> resource against
 * the rule engine — the single-point mount for JAX-RS apps (Jersey, RESTEasy,
 * Quarkus, Dropwizard, Helidon MP, CXF).
 *
 * <pre>{@code
 * environment.jersey().register(new KLSwitchGlobalFilter(ks));
 * }</pre>
 *
 * <p>That is the whole integration. Resources need no annotations, and endpoints
 * added later are guarded by default — the failure mode of forgetting is
 * <em>covered</em>, not <em>exposed</em>. Contrast {@link KLSwitchFilter},
 * where an unannotated resource is silently unguarded.
 *
 * <p>Paths are derived from each resource's own {@code @Path} annotations and
 * converted to the engine's {@code :id} dialect, so rules match the same routes
 * the console suggests. Override that per endpoint with
 * {@code @KLSwitchBinding(path = "...")} when your rules were authored against
 * a different canonical path.
 *
 * <p>Only one guard may be mounted per {@link Client}; constructing a second one
 * throws {@link IllegalStateException}. See {@link Client#registerGuardMount}.
 */
public final class KLSwitchGlobalFilter extends AbstractKLSwitchFilter {

    public KLSwitchGlobalFilter(Client ks) {
        super(ks, "the global JAX-RS filter (every resource)");
    }
}
