package com.klswitch.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.ManifestRoute;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServletManifestSourceTest {

    // ─── Fixtures ────────────────────────────────────────────────────────

    public static class PaymentsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {}
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) {}
    }

    public static class WriteOnlyServlet extends HttpServlet {
        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse resp) {}
        @Override
        protected void doDelete(HttpServletRequest req, HttpServletResponse resp) {}
    }

    /** Extends HttpServlet but overrides none of the doXxx methods. */
    public static class PassthroughServlet extends HttpServlet {}

    // ─── Tests ───────────────────────────────────────────────────────────

    @Test
    void resolveVerbsReadsOverridesFromHttpServletSubclass() {
        List<String> verbs = ServletManifestSource.resolveVerbs(
                PaymentsServlet.class.getName(), getClass().getClassLoader());
        assertTrue(verbs.contains("GET"));
        assertTrue(verbs.contains("POST"));
        assertEquals(2, verbs.size());
    }

    @Test
    void resolveVerbsReturnsEmptyAnyWhenNoOverrides() {
        // PassthroughServlet has no doXxx overrides → falls back to ANY so
        // the route still surfaces in the console.
        List<String> verbs = ServletManifestSource.resolveVerbs(
                PassthroughServlet.class.getName(), getClass().getClassLoader());
        assertEquals(List.of("ANY"), verbs);
    }

    @Test
    void resolveVerbsReturnsAnyWhenClassCannotBeLoaded() {
        List<String> verbs = ServletManifestSource.resolveVerbs(
                "com.nonexistent.GhostServlet", getClass().getClassLoader());
        assertEquals(List.of("ANY"), verbs);
    }

    @Test
    void resolveVerbsReturnsAnyForNullClassName() {
        assertEquals(List.of("ANY"),
                ServletManifestSource.resolveVerbs(null, getClass().getClassLoader()));
    }

    @Test
    void discoverEmitsOneRoutePerVerbPerMapping() {
        ServletContext ctx = stubContext(Map.of(
                "payments",  reg(PaymentsServlet.class.getName(),  List.of("/payments/*")),
                "internal",  reg(WriteOnlyServlet.class.getName(), List.of("/admin/*"))
        ));
        List<ManifestRoute> routes = ServletManifestSource.discover(ctx);
        Set<String> pairs = pairsOf(routes);
        assertTrue(pairs.contains("GET /payments/*"));
        assertTrue(pairs.contains("POST /payments/*"));
        assertTrue(pairs.contains("PUT /admin/*"));
        assertTrue(pairs.contains("DELETE /admin/*"));
        assertEquals(4, routes.size());
    }

    @Test
    void discoverPassesUrlPatternsVerbatim() {
        // Servlet URL patterns include wildcards (*.action, /foo/*) — surface
        // them as-is so the operator sees what the container actually has.
        ServletContext ctx = stubContext(Map.of(
                "action", reg(PaymentsServlet.class.getName(), List.of("*.action", "/api/*"))
        ));
        List<ManifestRoute> routes = ServletManifestSource.discover(ctx);
        Set<String> pairs = pairsOf(routes);
        assertTrue(pairs.contains("GET *.action"));
        assertTrue(pairs.contains("POST /api/*"));
    }

    @Test
    void discoverIncludesHandlerSimpleName() {
        ServletContext ctx = stubContext(Map.of(
                "payments", reg(PaymentsServlet.class.getName(), List.of("/payments/*"))));
        List<ManifestRoute> routes = ServletManifestSource.discover(ctx);
        ManifestRoute r = routes.get(0);
        assertNotNull(r.handler());
        assertEquals("PaymentsServlet", r.handler());
    }

    @Test
    void discoverEmptyOnEmptyRegistry() {
        ServletContext ctx = stubContext(Map.of());
        assertTrue(ServletManifestSource.discover(ctx).isEmpty());
    }

    @Test
    void discoverEmptyOnNullContext() {
        assertTrue(ServletManifestSource.discover(null).isEmpty());
    }

    @Test
    void manifestSourceFrameworkIsServlet() {
        ServletContext ctx = stubContext(Map.of());
        assertEquals("servlet", ServletManifestSource.of(ctx).framework());
    }

    // ─── Stub helpers ────────────────────────────────────────────────────

    private static ServletRegistration reg(String className, List<String> mappings) {
        Map<Method, Object> answers = new HashMap<>();
        // Pre-populate the reflection-driven answers.
        // ServletRegistration interface methods we care about.
        return (ServletRegistration) Proxy.newProxyInstance(
                ServletRegistration.class.getClassLoader(),
                new Class<?>[]{ServletRegistration.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "getMappings": return mappings;
                            case "getClassName": return className;
                            case "getName": return className;
                            default: return null;
                        }
                    }
                });
    }

    private static ServletContext stubContext(Map<String, ServletRegistration> registrations) {
        return (ServletContext) Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "getServletRegistrations" -> new LinkedHashMap<>(registrations);
                            case "getClassLoader"          -> getClass().getClassLoader();
                            default -> null;
                        };
                    }
                });
    }

    private static Set<String> pairsOf(List<ManifestRoute> routes) {
        return routes.stream().map(r -> r.method() + " " + r.path())
                .collect(java.util.stream.Collectors.toSet());
    }
}
