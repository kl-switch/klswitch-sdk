package com.klswitch.servlet;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the templated route for a request, ahead of the servlet that will
 * eventually handle it.
 *
 * <p>{@link KLSwitchFilter} runs before any servlet has mapped the request,
 * so on its own it can only report the concrete path — {@code /v1/payments/8123}
 * rather than {@code /v1/payments/:id} — and rules written against a template
 * silently never match. A resolver hands the filter the framework's own answer.
 *
 * <p>This is the same shape the Node and Go wrappers use for the same problem:
 * the Express adapter takes the {@code app}, the chi adapter takes the router,
 * and each resolves the match itself before evaluating.
 *
 * @see com.klswitch.spring.SpringRouteResolver
 */
@FunctionalInterface
public interface RouteResolver {

    /**
     * @return the resolved route, or null when this request maps to nothing the
     *     resolver recognises — the filter then falls back to the concrete URI.
     */
    ResolvedRoute resolve(HttpServletRequest request);
}
