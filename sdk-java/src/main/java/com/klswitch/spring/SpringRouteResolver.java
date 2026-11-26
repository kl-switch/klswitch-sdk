package com.klswitch.spring;

import com.klswitch.servlet.KLSwitchFilter;
import com.klswitch.servlet.ResolvedRoute;
import com.klswitch.servlet.RouteResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves Spring MVC's own route template for a request, before
 * {@code DispatcherServlet} has dispatched it.
 *
 * <pre>{@code
 * @Bean
 * FilterRegistrationBean<KLSwitchFilter> klswitch(Client ks, List<HandlerMapping> mappings) {
 *     var reg = new FilterRegistrationBean<>(
 *             KLSwitchFilter.withRouteResolver(ks, new SpringRouteResolver(mappings)));
 *     reg.addUrlPatterns("/*");
 *     return reg;
 * }
 * }</pre>
 *
 * <p>Without this, the servlet filter reports the concrete path
 * {@code /v1/payments/8123}, because a filter runs before any servlet has
 * mapped the request and the Servlet spec exposes no route pattern. Rules
 * written against {@code /v1/payments/:id} would then silently never match —
 * and that is the path the manifest reports and the console suggests.
 *
 * <p>Given Spring's {@link HandlerMapping} beans, this asks them to map the
 * request itself, then reads the pattern and URI variables Spring records on
 * the request. That is the same approach the other wrappers take for the same
 * constraint: the Express adapter takes the {@code app}, the chi adapter takes
 * the router.
 *
 * <p>The request is therefore mapped twice — once here, once by
 * {@code DispatcherServlet}. Mapping is a lookup, not handler invocation:
 * {@code getHandler} builds a {@code HandlerExecutionChain} and records request
 * attributes, and does not invoke interceptors or the handler.
 *
 * <p>Spring writes templates as <code>{id}</code> while rule selectors use
 * {@code :id}, so patterns are converted. A request that maps to no handler
 * resolves to null and the filter falls back to the concrete URI.
 */
public final class SpringRouteResolver implements RouteResolver {

    private final List<HandlerMapping> handlerMappings;

    /**
     * @param handlerMappings Spring's handler mappings, injected as
     *     {@code List<HandlerMapping>}. Order matters and Spring already
     *     supplies them in precedence order.
     */
    public SpringRouteResolver(List<HandlerMapping> handlerMappings) {
        if (handlerMappings == null) {
            throw new IllegalArgumentException("handlerMappings must not be null");
        }
        this.handlerMappings = List.copyOf(handlerMappings);
    }

    @Override
    public ResolvedRoute resolve(HttpServletRequest request) {
        for (HandlerMapping mapping : handlerMappings) {
            try {
                if (mapping.getHandler(request) == null) {
                    continue;
                }
            } catch (Exception ex) {
                // A mapping can throw for a request it half-recognises (an
                // unsupported method, say). Guarding must not 500 before any
                // rule has run, so try the next mapping.
                continue;
            }
            String pattern = attr(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            return new ResolvedRoute(toEnginePath(pattern), uriVariables(request));
        }
        return null;
    }

    private static String attr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uriVariables(HttpServletRequest request) {
        Object raw = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(raw instanceof Map<?, ?> vars) || vars.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : ((Map<String, ?>) vars).entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Convert a Spring pattern to the engine's dialect: <code>{id}</code> to
     * {@code :id}, and <code>{*rest}</code> / {@code **} to the trailing
     * wildcard. Regex-constrained variables such as <code>{id:[0-9]+}</code>
     * keep only the variable name, since the engine matches by segment.
     */
    static String toEnginePath(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length());
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = matchingBrace(pattern, i);
            if (close < 0) {         // unbalanced — leave the rest verbatim
                out.append(pattern, i, pattern.length());
                break;
            }
            String name = pattern.substring(i + 1, close);
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(0, colon);     // drop the regex constraint
            }
            if (name.startsWith("*")) {
                out.append("**");                    // {*rest} captures the remainder
            } else {
                out.append(':').append(name);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** Index of the brace closing the one at {@code open}, honouring nesting
     *  (regex constraints can themselves contain braces, e.g. {@code {id:\\d{4}}}). */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
