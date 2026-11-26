package com.klswitch.servlet;

import java.util.Map;

/**
 * A route resolved from the framework's own routing table: the templated path
 * the engine should evaluate, plus any path variables bound to it.
 *
 * <p>The Servlet spec exposes neither — a filter runs before the servlet has
 * mapped anything, and {@code HttpServletRequest} has no concept of a route
 * pattern. Frameworks layered on top of it (Spring MVC, say) do know both, so
 * a {@link RouteResolver} bridges the gap.
 *
 * @param path   templated path in the engine's dialect, e.g. {@code /v1/payments/:id}
 * @param params path variables by name, e.g. {@code {id: "8123"}}; never null
 */
public record ResolvedRoute(String path, Map<String, Object> params) {

    public ResolvedRoute {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A route with no path variables. */
    public static ResolvedRoute of(String path) {
        return new ResolvedRoute(path, Map.of());
    }
}
