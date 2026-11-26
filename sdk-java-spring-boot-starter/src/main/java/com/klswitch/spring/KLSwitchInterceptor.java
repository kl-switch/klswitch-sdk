package com.klswitch.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.klswitch.Client;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import com.klswitch.RequestContext;
import static com.klswitch.servlet.BodyCachingFilter.BODY_ATTR;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Spring HandlerInterceptor that evaluates every handler-mapped request
 * against the KLSwitch engine before the controller runs. Uses
 * Spring MVC's {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}
 * to extract the templated route pattern (e.g.
 * {@code "/v1/payments/{id}"} → canonicalised to {@code "/v1/payments/:id"})
 * so rule selectors match across wrappers.
 *
 * <p>Block-action policy mirrors the cross-language default: a matched
 * {@code block} rule short-circuits with the action's
 * {@code statusCode} (default 403) + canonical JSON body. Other actions
 * attach the {@link EvaluationResult} to the request via
 * {@link #RESULT_ATTR}.
 *
 * <p>Body extraction is intentionally minimal here — pulling the body
 * from an {@code HttpServletRequest} mid-request and replaying it for
 * the downstream controller requires a {@link Filter}-level wrapper
 * (the {@code com.klswitch.servlet.KLSwitchFilter}). Operators
 * who need body-based rules should wire that filter in addition to
 * this interceptor; for the common case of selector + header/query
 * rules the interceptor is enough.
 */
public class KLSwitchInterceptor implements HandlerInterceptor {

    /** Request attribute holding the {@link EvaluationResult} for downstream controllers. */
    public static final String RESULT_ATTR = "com.klswitch.result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Client ks;

    public KLSwitchInterceptor(Client ks) {
        this.ks = ks;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String input = RequestContext.toEvaluationInput(
                request.getMethod(),
                resolvePath(request),
                lowercaseHeaders(headerMap(request)),
                firstValuesQuery(request),
                Map.of(),
                bodyFacts(request));

        EvaluationResult result = ks.evaluate(input);
        request.setAttribute(RESULT_ATTR, result);

        Optional<MatchedRule> block = result.matched().stream()
                .filter(m -> m.action() != null && "block".equals(m.action().type()))
                .findFirst();
        if (block.isPresent()) {
            writeBlock(response, block.get());
            return false;   // short-circuit before the controller runs
        }
        return true;
    }

    /**
     * Body facts cached by {@code BodyCachingFilter}, or null when it is not wired.
     *
     * <p>Null is the honest answer rather than an empty map: an empty map would make
     * {@code exists} legitimately false, which is the false-positive this fix exists to remove.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyFacts(HttpServletRequest request) {
        Object cached = request.getAttribute(BODY_ATTR);
        return cached instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String resolvePath(HttpServletRequest req) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isEmpty()) {
            return toColonStyle(s);
        }
        // Fallback: concrete URI — only matches rules with literal paths.
        return req.getRequestURI();
    }

    /** Spring patterns use `{id}`; rule selectors use `:id`. */
    static String toColonStyle(String pattern) {
        if (pattern == null || pattern.indexOf('{') < 0) return pattern;
        return pattern.replaceAll("\\{([^:{}]+)(?::[^{}]+)?\\}", ":$1");
    }

    private static Map<String, Object> headerMap(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names == null) return out;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            out.put(name, req.getHeader(name));
        }
        return out;
    }

    private static Map<String, Object> lowercaseHeaders(Map<String, Object> in) {
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
            out.put(e.getKey(), (vs == null || vs.length == 0) ? null : vs[0]);
        }
        return out;
    }

    private static void writeBlock(HttpServletResponse res, MatchedRule rule) throws IOException {
        int status = 403;
        Map<String, Object> payload = rule.action().payload();
        if (payload != null && payload.get("statusCode") instanceof Number n) {
            status = n.intValue();
        }
        res.setStatus(status);
        res.setContentType("application/json");
        PrintWriter w = res.getWriter();
        w.write(MAPPER.writeValueAsString(Map.of(
                "blocked", true,
                "ruleId",  rule.id(),
                "action",  Map.of(
                        "type",    rule.action().type(),
                        "payload", payload == null ? Map.of() : payload))));
        w.flush();
    }
}
