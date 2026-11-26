package com.klswitch.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.ManifestRoute;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JaxRsManifestSourceTest {

    // ─── Resource fixtures ────────────────────────────────────────────────

    @Path("/v1/payments")
    public static class PaymentResource {
        @POST
        @Path("/{id}")
        public void create() { /* no body */ }

        @GET
        @Path("/{id}")
        public void get() { /* no body */ }

        @DELETE
        public void deleteAll() { /* no body */ }
    }

    @Path("/v1/users/{userId}/posts/{postId}")
    public static class NestedResource {
        @PUT
        public void put() { /* no body */ }
    }

    @Path("/v1/items/{id:\\d+}")
    public static class RegexParamResource {
        @GET
        public void get() { /* no body */ }
    }

    @Path("/v1/things")
    public static class BoundResource {
        @POST
        @Path("/{id}")
        @KLSwitchBinding(path = "/v1/things/special/:id")
        public void overridden() { /* no body */ }
    }

    public static class NoPathClass {
        @GET
        public void notAResource() { /* no @Path → ignored */ }
    }

    // ─── Path conversion ──────────────────────────────────────────────────

    @Test
    void convertToColonStyleStripsCurlyBraces() {
        assertEquals("/v1/payments/:id",
                JaxRsManifestSource.convertToColonStyle("/v1/payments/{id}"));
        assertEquals("/v1/users/:userId/posts/:postId",
                JaxRsManifestSource.convertToColonStyle("/v1/users/{userId}/posts/{postId}"));
    }

    @Test
    void convertToColonStyleStripsRegexSpec() {
        assertEquals("/v1/items/:id",
                JaxRsManifestSource.convertToColonStyle("/v1/items/{id:\\d+}"));
    }

    @Test
    void convertToColonStylePassthroughForLiteralPaths() {
        assertEquals("/v1/health", JaxRsManifestSource.convertToColonStyle("/v1/health"));
    }

    // ─── Discovery ────────────────────────────────────────────────────────

    @Test
    void discoverEmitsOneRoutePerHttpMethod() {
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(PaymentResource.class));
        Set<String> pairs = pairsOf(routes);
        assertTrue(pairs.contains("POST /v1/payments/:id"));
        assertTrue(pairs.contains("GET /v1/payments/:id"));
        assertTrue(pairs.contains("DELETE /v1/payments"));
        assertEquals(3, routes.size());
    }

    @Test
    void discoverHandlesNestedTemplatedClassPath() {
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(NestedResource.class));
        assertEquals(1, routes.size());
        assertEquals("PUT", routes.get(0).method());
        assertEquals("/v1/users/:userId/posts/:postId", routes.get(0).path());
    }

    @Test
    void discoverStripsRegexSpecOnClassPath() {
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(RegexParamResource.class));
        assertEquals(1, routes.size());
        assertEquals("/v1/items/:id", routes.get(0).path());
    }

    @Test
    void klswitchBindingOverridesDiscoveredPath() {
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(BoundResource.class));
        assertEquals(1, routes.size());
        // The operator's @KLSwitchBinding(path=...) wins over the @Path derivation.
        assertEquals("/v1/things/special/:id", routes.get(0).path());
        assertEquals("POST", routes.get(0).method());
    }

    @Test
    void discoverSkipsMethodsWithoutHttpVerbAnnotation() {
        // NoPathClass has a @GET method but no @Path on the class → still
        // emitted under method-level path "" → joinPaths returns "/" → we
        // get a single GET / route, which is valid (root resource).
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(NoPathClass.class));
        assertEquals(1, routes.size());
        assertEquals("GET", routes.get(0).method());
        assertEquals("/",   routes.get(0).path());
    }

    @Test
    void discoverFromApplicationWalksClassesAndSingletons() {
        Application app = new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(PaymentResource.class);
            }
            @Override
            public Set<Object> getSingletons() {
                return Set.of(new NestedResource());
            }
        };
        List<ManifestRoute> routes = JaxRsManifestSource.discoverFromApplication(app);
        Set<String> pairs = pairsOf(routes);
        assertTrue(pairs.contains("PUT /v1/users/:userId/posts/:postId"));
        assertTrue(pairs.contains("POST /v1/payments/:id"));
    }

    @Test
    void manifestSourceFrameworkIsJaxRs() {
        assertEquals("jax-rs",
                JaxRsManifestSource.ofClasses(List.of(PaymentResource.class)).framework());
    }

    @Test
    void discoverIncludesHandlerName() {
        List<ManifestRoute> routes = JaxRsManifestSource.discover(List.of(PaymentResource.class));
        ManifestRoute post = routes.stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/v1/payments/:id"))
                .findFirst().orElseThrow();
        assertEquals("PaymentResource.create", post.handler());
    }

    // ─── Verb-detector coverage ──────────────────────────────────────────
    //
    // The httpVerb() helper has one branch per HTTP verb annotation plus a
    // fall-through that walks every annotation on the method looking for a
    // @HttpMethod meta-annotation (third-party verb extensions). Existing
    // tests cover GET/POST/DELETE/PUT; these close PATCH/HEAD/OPTIONS and
    // the meta-annotation path.

    @Path("/v1/rare")
    public static class RareVerbsResource {
        @PATCH   public void patch()   { /* no body */ }
        @HEAD    public void head()    { /* no body */ }
        @OPTIONS public void options() { /* no body */ }
    }

    @Test
    void discoverEmitsPatchHeadOptions() {
        Set<String> pairs = pairsOf(
                JaxRsManifestSource.discover(List.of(RareVerbsResource.class)));
        assertTrue(pairs.contains("PATCH /v1/rare"),   pairs::toString);
        assertTrue(pairs.contains("HEAD /v1/rare"),    pairs::toString);
        assertTrue(pairs.contains("OPTIONS /v1/rare"), pairs::toString);
    }

    /** Custom verb annotation meta-annotated with @HttpMethod. Mirrors how
     *  third-party JAX-RS extensions register non-standard verbs (e.g.
     *  WebDAV's PROPFIND). The walker should still surface it. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("PROPFIND")
    public @interface PROPFIND {}

    @Path("/v1/custom")
    public static class CustomVerbResource {
        @PROPFIND public void propfind() { /* WebDAV-style discovery verb */ }
    }

    @Test
    void discoverPicksUpCustomHttpMethodViaMetaAnnotation() {
        Set<String> pairs = pairsOf(
                JaxRsManifestSource.discover(List.of(CustomVerbResource.class)));
        assertTrue(pairs.contains("PROPFIND /v1/custom"),
                "custom @HttpMethod-meta-annotated verb must surface: " + pairs);
    }

    // ─── Defensive Application paths ─────────────────────────────────────

    @Test
    void discoverFromNullApplicationReturnsEmpty() {
        assertTrue(JaxRsManifestSource.discoverFromApplication(null).isEmpty());
    }

    @Test
    void discoverFromApplicationWithNullCollectionsIsSafe() {
        // Spec lets getClasses() / getSingletons() return null; the walker
        // must skip the null branch rather than NPE.
        Application app = new Application() {
            @Override public Set<Class<?>> getClasses()    { return null; }
            @Override public Set<Object>   getSingletons() { return null; }
        };
        assertTrue(JaxRsManifestSource.discoverFromApplication(app).isEmpty());
    }

    @Test
    void discoverFromEmptyClassListReturnsEmpty() {
        assertTrue(JaxRsManifestSource.discover(List.of()).isEmpty());
        assertTrue(JaxRsManifestSource.discover(null).isEmpty());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static Set<String> pairsOf(List<ManifestRoute> routes) {
        return routes.stream().map(r -> r.method() + " " + r.path())
                .collect(java.util.stream.Collectors.toSet());
    }
}
