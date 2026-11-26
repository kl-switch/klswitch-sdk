package com.klswitch.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klswitch.Client;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import com.klswitch.RequestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Generic {@link Filter} that evaluates every HTTP request against the
 * KLSwitch engine before the downstream filter chain runs. Use this in
 * any Servlet 5.0+ app (jakarta namespace) that doesn't have a more
 * specific adapter (Spring Boot starter, JAX-RS filter, etc.).
 *
 * <p>Block-action policy is the cross-language default: a matched
 * {@code block} rule short-circuits with the action's {@code statusCode}
 * (default 403) + canonical JSON body. Other actions ({@code flag},
 * {@code notify}, …) attach the {@link EvaluationResult} to the
 * request via {@link #RESULT_ATTR} so the servlet can act on it.
 *
 * <h2>Wire-up</h2>
 *
 * <pre>{@code
 * @WebFilter(urlPatterns = "/*")
 * public class MyAppFilter extends KLSwitchFilter {
 *     public MyAppFilter() {
 *         super(MyAppContextListener.klswitchClient(),  // your shared Client
 *               req -> "/v1/payments/:id");                // path template resolver
 *     }
 * }
 * }</pre>
 *
 * <p>Body access: the filter buffers up to 1 MiB of the request body for
 * fact extraction, then exposes the original (or a wrapped) input stream
 * to the downstream servlet so the servlet's own deserialiser still
 * sees the full body. Bigger bodies stream through unbuffered — the
 * filter can't be DoS'd by an oversized payload.
 *
 * <p>Path templates: the Servlet spec doesn't expose route patterns at
 * runtime. Operators supply a {@code Function<HttpServletRequest, String>}
 * that returns the templated path (e.g. {@code "/v1/payments/:id"})
 * matching their rule selectors. Default: fall back to the concrete
 * request URI — fine for static routes, won't match rules with
 * templated selectors.
 *
 * <p>Only one guard may be mounted per {@link Client}; constructing a second
 * one throws {@link IllegalStateException}. In particular, do not pair this
 * filter with a JAX-RS filter on the same client — Dropwizard and Quarkus run
 * both layers, so every request would be evaluated twice. See
 * {@link Client#registerGuardMount}.
 */
public class KLSwitchFilter implements Filter {

    /**
     * Request attribute holding the {@link EvaluationResult} for
     * downstream servlets.
     */
    public static final String RESULT_ATTR = "com.klswitch.result";

    /** Cap on the bytes buffered for fact extraction. Matches the
     *  cross-language 1 MiB default. */
    private static final int DEFAULT_MAX_BODY_BYTES = 1 << 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Client ks;
    private final RouteResolver routeResolver;
    private final int maxBodyBytes;

    /** Minimal constructor — falls back to the concrete request URI for
     *  the rule-selector path. Only useful for static routes. */
    public KLSwitchFilter(Client ks) {
        this(ks, uriRoute(), DEFAULT_MAX_BODY_BYTES);
    }

    /** Path-aware constructor — the operator supplies a function that
     *  returns the templated route for each request. Path variables are not
     *  reported; use {@link #withRouteResolver} if your framework exposes them. */
    public KLSwitchFilter(Client ks, Function<HttpServletRequest, String> pathResolver) {
        this(ks, adapt(pathResolver), DEFAULT_MAX_BODY_BYTES);
    }

    /** Path-aware constructor with a custom body cap. */
    public KLSwitchFilter(
            Client ks,
            Function<HttpServletRequest, String> pathResolver,
            int maxBodyBytes) {
        this(ks, adapt(pathResolver), maxBodyBytes);
    }

    /**
     * Route-aware factory — the resolver supplies both the templated path and
     * the path variables, so {@code params.*} facts are available too.
     *
     * <p>Spring MVC users want {@link com.klswitch.spring.SpringRouteResolver}:
     *
     * <pre>{@code
     * KLSwitchFilter.withRouteResolver(ks, new SpringRouteResolver(handlerMappings))
     * }</pre>
     *
     * <p>This is a factory rather than a constructor on purpose. {@link RouteResolver}
     * and {@code Function<HttpServletRequest, String>} are both single-argument
     * functional interfaces, so an overloaded constructor would make every
     * implicitly-typed lambda ambiguous — including the {@code req -> template}
     * shape already in use.
     */
    public static KLSwitchFilter withRouteResolver(Client ks, RouteResolver routeResolver) {
        return new KLSwitchFilter(ks, routeResolver, DEFAULT_MAX_BODY_BYTES);
    }

    /** {@link #withRouteResolver(Client, RouteResolver)} with a custom body cap. */
    public static KLSwitchFilter withRouteResolver(
            Client ks, RouteResolver routeResolver, int maxBodyBytes) {
        return new KLSwitchFilter(ks, routeResolver, maxBodyBytes);
    }

    private KLSwitchFilter(Client ks, RouteResolver routeResolver, int maxBodyBytes) {
        if (ks == null) throw new IllegalArgumentException("ks must not be null");
        if (routeResolver == null) throw new IllegalArgumentException("routeResolver must not be null");
        ks.registerGuardMount("the servlet filter");
        this.ks = ks;
        this.routeResolver = routeResolver;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** The default resolver: report the concrete request URI. */
    private static RouteResolver uriRoute() {
        return req -> {
            String uri = req.getRequestURI();
            return (uri == null || uri.isEmpty()) ? null : ResolvedRoute.of(uri);
        };
    }

    /** Bridge the legacy path-only resolver onto {@link RouteResolver}. */
    private static RouteResolver adapt(Function<HttpServletRequest, String> pathResolver) {
        if (pathResolver == null) {
            throw new IllegalArgumentException("pathResolver must not be null");
        }
        return req -> {
            String p = pathResolver.apply(req);
            return (p == null || p.isEmpty()) ? null : ResolvedRoute.of(p);
        };
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        // Plain ServletRequest / non-HTTP responses (e.g. WebSocket upgrade)
        // just pass through — KLSwitch only knows about HTTP.
        if (!(req instanceof HttpServletRequest httpReq)
                || !(res instanceof HttpServletResponse httpRes)) {
            chain.doFilter(req, res);
            return;
        }

        // Buffer the body so we can both feed fact extraction AND replay
        // it to the downstream servlet. Bigger bodies stream through
        // unbuffered (we extract no body facts).
        BodyCacheRequest wrapper = new BodyCacheRequest(httpReq, maxBodyBytes);
        byte[] buffered = wrapper.bufferedBody();
        Map<String, Object> bodyMap = parseJsonBody(httpReq.getContentType(), buffered);

        ResolvedRoute route = resolveRoute(httpReq);
        String input = RequestContext.toEvaluationInput(
                httpReq.getMethod(),
                route.path(),
                lowercaseFirstValues(headerMap(httpReq)),
                firstValuesQuery(httpReq),
                route.params(),
                bodyMap);

        EvaluationResult result = ks.evaluate(input);
        httpReq.setAttribute(RESULT_ATTR, result);

        Optional<MatchedRule> block = result.matched().stream()
                .filter(m -> m.action() != null && "block".equals(m.action().type()))
                .findFirst();
        if (block.isPresent()) {
            writeBlock(httpRes, block.get());
            return;
        }
        chain.doFilter(wrapper, httpRes);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The route to evaluate. A resolver that returns null — no framework route
     * matched this request — falls back to the concrete URI, which still matches
     * exact selectors and {@code *} path patterns.
     */
    private ResolvedRoute resolveRoute(HttpServletRequest req) {
        ResolvedRoute route;
        try {
            route = routeResolver.resolve(req);
        } catch (RuntimeException ex) {
            // A resolver that consults the framework's routing table can throw on
            // odd requests. Guarding must not 500 before any rule has run.
            route = null;
        }
        if (route != null) {
            return route;
        }
        String uri = req.getRequestURI();
        return ResolvedRoute.of((uri == null || uri.isEmpty()) ? "/" : uri);
    }

    private static Map<String, Object> headerMap(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names == null) return out;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            // First value wins on repeats — cross-language parity.
            out.put(name, req.getHeader(name));
        }
        return out;
    }

    private static Map<String, Object> lowercaseFirstValues(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }

    private static Map<String, Object> firstValuesQuery(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String[]> params = req.getParameterMap();
        if (params == null) return out;
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            String[] vs = e.getValue();
            if (vs == null || vs.length == 0) {
                out.put(e.getKey(), null);
            } else {
                out.put(e.getKey(), vs[0]);   // first value wins
            }
        }
        return out;
    }

    /** Package-visible alias so {@link BodyCachingFilter} can reuse the same parsing rules. */
    static Map<String, Object> parseJsonBodyForReuse(String contentType, byte[] buffered) {
        return parseJsonBody(contentType, buffered);
    }

    private static Map<String, Object> parseJsonBody(String contentType, byte[] buffered) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return null;
        }
        if (buffered == null || buffered.length == 0) return null;
        try {
            Object parsed = MAPPER.readValue(buffered, Object.class);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) parsed;
                return typed;
            }
            return null;
        } catch (IOException e) {
            // Malformed body: skip facts, never fail the filter.
            return null;
        }
    }

    private void writeBlock(HttpServletResponse res, MatchedRule rule) throws IOException {
        int status = 403;
        Map<String, Object> payload = rule.action().payload();
        if (payload != null && payload.get("statusCode") instanceof Number n) {
            status = n.intValue();
        }
        res.setStatus(status);
        res.setContentType("application/json");
        String body = MAPPER.writeValueAsString(Map.of(
                "blocked", true,
                "ruleId",  rule.id(),
                "action",  Map.of(
                        "type", rule.action().type(),
                        "payload", rule.action().payload() == null ? Map.of() : rule.action().payload()
                )
        ));
        PrintWriter w = res.getWriter();
        w.write(body);
        w.flush();
    }

    // ── Body-caching request wrapper ──────────────────────────────────────

    /**
     * Wraps an {@link HttpServletRequest} so the body can be read twice:
     * once by the filter for fact extraction, and again by the downstream
     * servlet's deserialiser. Bodies larger than {@code maxBodyBytes} are
     * NOT buffered (the filter sees them as "no body facts" and the
     * downstream servlet reads the original stream untouched).
     */
    static final class BodyCacheRequest extends HttpServletRequestWrapper {
        private final byte[] buffer;             // null when over the cap
        private final HttpServletRequest delegate;

        BodyCacheRequest(HttpServletRequest delegate, int maxBytes) throws IOException {
            super(delegate);
            this.delegate = delegate;
            this.buffer = readBounded(delegate, maxBytes);
        }

        byte[] bufferedBody() { return buffer; }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (buffer == null) {
                // Body was too large to buffer; expose the original
                // stream untouched. The filter already declined to
                // extract facts in this case.
                return delegate.getInputStream();
            }
            ByteArrayInputStream backing = new ByteArrayInputStream(buffer);
            return new ServletInputStream() {
                @Override public int read() { return backing.read(); }
                @Override public boolean isFinished() { return backing.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { /* not supported */ }
            };
        }

        private static byte[] readBounded(HttpServletRequest req, int maxBytes) throws IOException {
            // Read MAX+1 bytes; if we get more than max, the body is "too large"
            // and we return null so the filter skips body facts and the wrapper
            // delegates to the original stream.
            ServletInputStream in = req.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(tmp)) != -1) {
                total += n;
                if (total > maxBytes) return null;   // over the cap → no buffer
                out.write(tmp, 0, n);
            }
            return out.toByteArray();
        }
    }

    // Default init/destroy are no-ops; overridable.
}
