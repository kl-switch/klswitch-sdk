package com.klswitch.spring;

import com.klswitch.ManifestRoute;
import com.klswitch.ManifestSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring Boot route discovery.
 *
 * <p>Spring's {@link RequestMappingHandlerMapping} bean holds the live
 * map of {@link RequestMappingInfo} → {@link HandlerMethod} that backs
 * every {@code @Controller} / {@code @RestController} in the
 * application. We walk it once per {@code collect()} call, expanding
 * each entry into one {@link ManifestRoute} per (path pattern, HTTP
 * method) pair.
 *
 * <p>Path-template normalisation: Spring's patterns use {@code {id}};
 * the rule selector convention across wrappers is {@code :id}. We
 * convert here so the discovered routes line up with what the operator
 * authored.
 *
 * <p>Empty HTTP-method conditions (a handler that matches every verb)
 * fan out to a single {@code ManifestRoute} with method {@code "ANY"}
 * — the backend treats that as "all methods" when matching rule
 * selectors.
 *
 * <p>Bootstrapping: the {@link RequestMappingHandlerMapping} bean is
 * created during Spring's context refresh. Constructing the
 * {@code ManifestSource} via {@link #of(ApplicationContext)} captures
 * a reference to the context; the actual handler-map walk is deferred
 * to {@code collect()} time, which is invoked after the SDK's first
 * {@code reportManifest()} call. Operators should call
 * {@code client.reportManifest()} once the context is fully refreshed
 * — typically from an {@code ApplicationReadyEvent} listener.
 */
public final class SpringManifestSource {

    private static final String FRAMEWORK = "spring";

    /** "{name}" or "{name:regex}" — strip the regex spec, same as JAX-RS. */
    private static final Pattern PARAM = Pattern.compile("\\{([^:{}]+)(?::[^{}]+)?\\}");

    private SpringManifestSource() {}

    /**
     * Walks the {@link RequestMappingHandlerMapping} bean inside the
     * supplied {@link ApplicationContext}. Tolerant of contexts where
     * the bean isn't yet registered (returns an empty list).
     */
    public static ManifestSource of(ApplicationContext context) {
        return ManifestSource.of(FRAMEWORK, () -> discoverFromContext(context));
    }

    /** Walks a {@link RequestMappingHandlerMapping} directly. Useful when
     *  the operator already has the bean in hand (e.g. autowired). */
    public static ManifestSource of(RequestMappingHandlerMapping mapping) {
        return ManifestSource.of(FRAMEWORK, () -> discover(mapping));
    }

    /** Exposed for tests. */
    public static List<ManifestRoute> discoverFromContext(ApplicationContext context) {
        if (context == null) return List.of();
        RequestMappingHandlerMapping mapping;
        try {
            mapping = context.getBean(RequestMappingHandlerMapping.class);
        } catch (org.springframework.beans.BeansException e) {
            // Bean not yet registered, or no MVC at all (e.g. WebFlux-only).
            // Empty list → manifest reporter logs the empty push, no regression.
            return List.of();
        }
        return discover(mapping);
    }

    /** Exposed for tests. */
    public static List<ManifestRoute> discover(RequestMappingHandlerMapping mapping) {
        if (mapping == null) return List.of();
        Map<RequestMappingInfo, HandlerMethod> handlers = mapping.getHandlerMethods();
        if (handlers == null || handlers.isEmpty()) return List.of();

        List<ManifestRoute> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();   // dedup (method, path)
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
            collectFromMapping(entry.getKey(), entry.getValue(), out, seen);
        }
        return out;
    }

    /**
     * True for a mapping Spring Boot registers on its own behalf.
     *
     * <p>{@code BasicErrorController} is the one that shows up in practice: every Boot
     * application has it, and it maps {@code /error} with no declared verb, so it was reported
     * as {@code ANY /error} alongside the application's real endpoints. It is not API surface —
     * nobody calls it directly, and a rule targeting it would fire on whatever happened to fail.
     *
     * <p>Matched on the declaring class rather than the path, because an application is entitled
     * to its own {@code /error} endpoint and that one should be reported. Keyed by name to avoid
     * a compile-time dependency on spring-boot-autoconfigure from a module that otherwise needs
     * only spring-web.
     */
    private static boolean isFrameworkHandler(HandlerMethod handler) {
        if (handler == null) return false;
        String declaring = handler.getBeanType().getName();
        return declaring.startsWith("org.springframework.boot.autoconfigure.web.servlet.error.")
                || declaring.startsWith("org.springframework.boot.actuate.");
    }

    private static void collectFromMapping(
            RequestMappingInfo info, HandlerMethod handler,
            List<ManifestRoute> out, Set<String> seen) {

        Set<String> patterns = extractPatterns(info);
        if (patterns.isEmpty()) return;
        if (isFrameworkHandler(handler)) return;

        // RequestMethodsRequestCondition.getMethods() returns the
        // declared verbs. Empty set = handler matches every verb.
        RequestMethodsRequestCondition methodsCondition = info.getMethodsCondition();
        Set<String> methods = new TreeSet<>();
        if (methodsCondition != null && !methodsCondition.getMethods().isEmpty()) {
            methodsCondition.getMethods().forEach(m -> methods.add(m.name().toUpperCase(Locale.ROOT)));
        } else {
            methods.add("ANY");
        }

        String handlerName = handler != null
                ? handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName()
                : null;

        for (String pattern : patterns) {
            String path = convertToColonStyle(pattern);
            for (String method : methods) {
                String key = method + " " + path;
                if (!seen.add(key)) continue;
                out.add(new ManifestRoute(method, path, handlerName));
            }
        }
    }

    /**
     * Pulls the URL patterns out of a {@link RequestMappingInfo}. Spring 5+
     * exposes them via either {@code getPathPatternsCondition()} (the modern
     * {@code PathPattern} parser, Spring 5.3+) or the legacy
     * {@link PatternsRequestCondition} ({@code AntPathMatcher}). We try the
     * modern path first, fall back to the legacy condition.
     */
    private static Set<String> extractPatterns(RequestMappingInfo info) {
        try {
            // Spring 5.3+: getPathPatternsCondition() may return null when
            // the application is wired with the legacy AntPathMatcher only.
            var modern = info.getPathPatternsCondition();
            if (modern != null) {
                Set<String> patterns = new TreeSet<>();
                modern.getPatternValues().forEach(patterns::add);
                if (!patterns.isEmpty()) return patterns;
            }
        } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            // Older Spring (< 5.3) or unusual configuration. Fall through.
        }
        PatternsRequestCondition legacy = info.getPatternsCondition();
        if (legacy != null && !legacy.getPatterns().isEmpty()) {
            return new TreeSet<>(legacy.getPatterns());
        }
        return Set.of();
    }

    /** {@code "{id}"} or {@code "{id:\\d+}"} → {@code ":id"}; everything else is left alone. */
    public static String convertToColonStyle(String path) {
        if (path == null || path.indexOf('{') < 0) return path;
        Matcher m = PARAM.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(":" + m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
