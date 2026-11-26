package com.klswitch.servlet;

import com.klswitch.ManifestRoute;
import com.klswitch.ManifestSource;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Servlet route discovery — best-effort.
 *
 * <p>The Servlet spec exposes registered servlets + their URL patterns via
 * {@link ServletContext#getServletRegistrations()}. The mapping registry
 * does <em>not</em> carry HTTP-method information, so we use a two-step
 * heuristic:
 *
 * <ol>
 *   <li>Load each registered servlet class via the container's classloader.
 *   <li>If it extends {@link HttpServlet}, walk its inheritance chain and
 *       check which {@code doXxx(HttpServletRequest, HttpServletResponse)}
 *       methods are overridden — those are the HTTP verbs the servlet
 *       actually handles. Emit one route per (verb, mapping) pair.
 *   <li>If the servlet doesn't extend {@code HttpServlet} (rare in
 *       practice — generic protocol servlets), emit a single route with
 *       method {@code "ANY"} so the operator still sees the mapping in
 *       the console.
 * </ol>
 *
 * <p>Servlet URL patterns (e.g. {@code /foo/*}, {@code *.action}) are
 * passed through verbatim — rule selectors match by exact string, so the
 * operator authors rules against whatever pattern the console surfaces.
 */
public final class ServletManifestSource {

    private static final String FRAMEWORK = "servlet";

    /** Methods on {@link HttpServlet} we probe for overrides. */
    private static final Map<String, String> DO_METHODS = new LinkedHashMap<>();
    static {
        DO_METHODS.put("doGet",     "GET");
        DO_METHODS.put("doPost",    "POST");
        DO_METHODS.put("doPut",     "PUT");
        DO_METHODS.put("doDelete",  "DELETE");
        DO_METHODS.put("doPatch",   "PATCH");   // since Servlet 6
        DO_METHODS.put("doHead",    "HEAD");
        DO_METHODS.put("doOptions", "OPTIONS");
    }

    private ServletManifestSource() {}

    public static ManifestSource of(ServletContext context) {
        return ManifestSource.of(FRAMEWORK, () -> discover(context));
    }

    /** Exposed for tests. */
    public static List<ManifestRoute> discover(ServletContext context) {
        if (context == null) return List.of();
        Map<String, ? extends ServletRegistration> regs = context.getServletRegistrations();
        if (regs == null || regs.isEmpty()) return List.of();

        ClassLoader cl = chooseClassLoader(context);
        List<ManifestRoute> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ServletRegistration reg : regs.values()) {
            Collection<String> mappings = reg.getMappings();
            if (mappings == null || mappings.isEmpty()) continue;
            String className = reg.getClassName();
            List<String> verbs = resolveVerbs(className, cl);
            String handler = className != null ? simpleName(className) : reg.getName();
            for (String mapping : mappings) {
                if (mapping == null || mapping.isEmpty()) continue;
                for (String verb : verbs) {
                    String key = verb + " " + mapping;
                    if (!seen.add(key)) continue;
                    out.add(new ManifestRoute(verb, mapping, handler));
                }
            }
        }
        return out;
    }

    /** Returns the list of HTTP verbs this servlet handles, or {@code ["ANY"]} fallback. */
    static List<String> resolveVerbs(String className, ClassLoader cl) {
        if (className == null || cl == null) return List.of("ANY");
        Class<?> cls;
        try {
            cls = Class.forName(className, false, cl);
        } catch (Throwable t) {
            // Not loadable from here (e.g. test contexts without the real WAR
            // classpath). Emit a single ANY mapping so the operator still
            // sees the pattern in the console.
            return List.of("ANY");
        }
        if (!HttpServlet.class.isAssignableFrom(cls)) return List.of("ANY");
        List<String> verbs = new ArrayList<>();
        for (Map.Entry<String, String> e : DO_METHODS.entrySet()) {
            if (isOverridden(cls, e.getKey())) verbs.add(e.getValue());
        }
        // Default: HttpServlet's stock doOptions answers — but operators
        // typically don't add rules for OPTIONS. Treat "no overrides" as
        // ANY so we still surface the route.
        return verbs.isEmpty() ? List.of("ANY") : verbs;
    }

    /** True iff {@code cls} (or a non-HttpServlet parent) declares {@code methodName}. */
    static boolean isOverridden(Class<?> cls, String methodName) {
        for (Class<?> c = cls; c != null && c != HttpServlet.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                if (params[0].getName().equals("jakarta.servlet.http.HttpServletRequest")
                        && params[1].getName().equals("jakarta.servlet.http.HttpServletResponse")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClassLoader chooseClassLoader(ServletContext context) {
        ClassLoader cl = context.getClassLoader();
        if (cl != null) return cl;
        ClassLoader tcl = Thread.currentThread().getContextClassLoader();
        return tcl != null ? tcl : ServletManifestSource.class.getClassLoader();
    }

    static String simpleName(String fqn) {
        int sep = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$'));
        return sep >= 0 ? fqn.substring(sep + 1) : fqn;
    }
}
