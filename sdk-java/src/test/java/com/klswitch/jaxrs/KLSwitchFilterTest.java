package com.klswitch.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JAX-RS request filter — driven against a real
 * {@link Client} with inline rules and a JDK-proxied
 * {@link ContainerRequestContext} so we don't need a Jersey/Grizzly
 * runtime in the test classpath. Covers the block-action short-circuit,
 * flag/notify pass-through, body extraction + caps, and
 * {@link KLSwitchBinding} path override.
 */
class KLSwitchFilterTest {

    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":["
            + "{\"id\":\"block_large_payment\",\"name\":\"large payment\","
            + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/payments/:id\"}},"
            + "  \"enabled\":true,\"priority\":100,"
            + "  \"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":50000}]},"
            + "  \"action\":{\"type\":\"block\",\"payload\":{\"statusCode\":429,\"code\":\"PAYMENT_TOO_LARGE\"}}},"
            + "{\"id\":\"block_default_status\",\"name\":\"default status block\","
            + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/blocked\"}},"
            + "  \"enabled\":true,\"priority\":100,"
            + "  \"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}]},"
            + "  \"action\":{\"type\":\"block\"}},"
            + "{\"id\":\"flag_admin\",\"name\":\"flag admin\","
            + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/v1/users/:id\"}},"
            + "  \"enabled\":true,\"priority\":50,"
            + "  \"condition\":{\"all\":[{\"field\":\"query.role\",\"operator\":\"eq\",\"value\":\"admin\"}]},"
            + "  \"action\":{\"type\":\"flag\"}}"
            + "]}";

    private Client ks;

    @BeforeEach
    void setUp() {
        ks = Client.init(Config.builder().rules(RULES).build());
    }

    @AfterEach
    void tearDown() {
        if (ks != null) ks.close();
    }

    // ─── Block-on-block ─────────────────────────────────────────────────

    @Test
    void blockActionWithPayloadStatusCodeAbortsWithThatStatus() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("{\"amount\":70000}")
                .build();

        filter.filter(ctx.proxy());

        assertNotNull(ctx.abortedWith, "expected abortWith() to be called");
        assertEquals(429, ctx.abortedWith.getStatus(),
                "statusCode from action.payload.statusCode must win over the 403 default");
        // The aborted response carries the matched rule's id in its entity for
        // downstream observability — wrappers all stamp `{blocked, ruleId, action}`.
        Object entity = ctx.abortedWith.getEntity();
        assertInstanceOf(Map.class, entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity;
        assertEquals(Boolean.TRUE, body.get("blocked"));
        assertEquals("block_large_payment", body.get("ruleId"));
    }

    @Test
    void blockActionWithoutPayloadStatusCodeUses403Default() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/blocked");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/blocked")
                .body("{\"amount\":1}")
                .build();

        filter.filter(ctx.proxy());

        assertNotNull(ctx.abortedWith);
        assertEquals(403, ctx.abortedWith.getStatus(),
                "missing payload.statusCode must fall back to 403");
    }

    // ─── Pass-through (no match / non-block action) ─────────────────────

    @Test
    void noMatchPassesThroughAndAttachesResult() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("{\"amount\":10}")   // below threshold → no match
                .build();

        filter.filter(ctx.proxy());

        assertNull(ctx.abortedWith, "no rule matched → must not abort");
        EvaluationResult result = (EvaluationResult) ctx.properties.get(KLSwitchFilter.RESULT_PROPERTY);
        assertNotNull(result);
        assertTrue(result.matched().isEmpty());
    }

    @Test
    void flagActionPassesThroughAndAttachesResult() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/users/:id");
        StubCtx ctx = ctxBuilder()
                .method("GET")
                .path("v1/users/42")
                .query("role", "admin")
                .build();

        filter.filter(ctx.proxy());

        assertNull(ctx.abortedWith, "flag-only match must NOT abort");
        EvaluationResult result = (EvaluationResult) ctx.properties.get(KLSwitchFilter.RESULT_PROPERTY);
        assertNotNull(result);
        assertEquals(1, result.matched().size());
        MatchedRule m = result.matched().get(0);
        assertEquals("flag_admin", m.id());
        assertEquals("flag", m.action().type());
    }

    // ─── Path resolution ────────────────────────────────────────────────

    @Test
    void klswitchBindingPathOverridesConcreteUrlPath() throws Exception {
        // Concrete URL is /v1/payments/abc; binding declares /v1/payments/:id.
        // Without the binding override, the rule selector for `/v1/payments/:id`
        // would never match.
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("{\"amount\":70000}")
                .build();

        filter.filter(ctx.proxy());
        assertNotNull(ctx.abortedWith, "binding path must have routed to /v1/payments/:id");
    }

    @Test
    void missingBindingFallsBackToConcreteUrlPath() throws Exception {
        // No @KLSwitchBinding → declaredPath() returns "/" + getPath().
        // Concrete path /v1/blocked happens to match exactly here.
        KLSwitchFilter filter = filterWithoutBinding();
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/blocked")
                .body("{\"amount\":1}")
                .build();

        filter.filter(ctx.proxy());
        assertNotNull(ctx.abortedWith);
    }

    @Test
    void bindingWithEmptyPathFallsBackToConcreteUrl() throws Exception {
        // path() default is "" — should be treated as "not declared".
        KLSwitchFilter filter = filterWithBinding("");  // explicit empty
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/blocked")
                .body("{\"amount\":1}")
                .build();

        filter.filter(ctx.proxy());
        assertNotNull(ctx.abortedWith);
    }

    // ─── Body extraction ────────────────────────────────────────────────

    @Test
    void noEntityMeansNoBodyFactsAndStillEvaluates() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .noEntity()
                .build();

        filter.filter(ctx.proxy());
        // No body → body.amount missing → leaf false → no match → no abort.
        assertNull(ctx.abortedWith);
        assertNotNull(ctx.properties.get(KLSwitchFilter.RESULT_PROPERTY));
    }

    @Test
    void nonJsonContentTypeSkipsBodyButStillEvaluates() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("amount=70000")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                .build();

        filter.filter(ctx.proxy());
        // Body skipped → no body facts → no match → no abort. The entity
        // stream itself was NOT consumed (we never read for non-JSON).
        assertNull(ctx.abortedWith);
    }

    @Test
    void nullContentTypeSkipsBody() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("{\"amount\":70000}")
                .contentType(null)            // explicit null
                .build();

        filter.filter(ctx.proxy());
        assertNull(ctx.abortedWith, "null Content-Type must skip body extraction");
    }

    @Test
    void oversizedBodyIsSplicedThroughAndBodyFactsSkipped() throws Exception {
        // Build a body > 1 MiB so the filter sees the truncated marker.
        StringBuilder big = new StringBuilder("{\"amount\":70000,\"pad\":\"");
        for (int i = 0; i < (1_048_576 / 8) + 10; i++) big.append("XXXXXXXX");
        big.append("\"}");
        byte[] bodyBytes = big.toString().getBytes(StandardCharsets.UTF_8);

        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .bodyBytes(bodyBytes)
                .build();

        filter.filter(ctx.proxy());
        // Oversize → body facts skipped → no match → no abort.
        assertNull(ctx.abortedWith);
        // The entity stream was reset to a SequenceInputStream so the
        // resource still sees the full body. Read back and confirm we
        // can recover the original.
        InputStream restored = ctx.entityStream;
        byte[] readBack = restored.readAllBytes();
        assertEquals(bodyBytes.length, readBack.length,
                "the spliced entity stream must restore the full original body");
    }

    @Test
    void malformedJsonBodyDoesNotCrashAndEvaluatesWithoutFacts() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("{ not valid json")
                .build();

        // Must not throw — broken bodies are common (clients tear down
        // mid-request) and the filter should still let the resource's own
        // deserialiser surface the error to the client.
        filter.filter(ctx.proxy());
        assertNull(ctx.abortedWith,
                "broken JSON → no body facts → no match → no abort");
    }

    @Test
    void emptyBodyEvaluatesWithoutFacts() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body("")
                .build();
        filter.filter(ctx.proxy());
        assertNull(ctx.abortedWith);
    }

    @Test
    void smallJsonBodyIsResetSoResourceStillSeesIt() throws Exception {
        KLSwitchFilter filter = filterWithBinding("/v1/payments/:id");
        String original = "{\"amount\":10}";
        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/abc")
                .body(original)
                .build();

        filter.filter(ctx.proxy());
        // Stream was replaced with a ByteArrayInputStream — read it back.
        byte[] roundTrip = ctx.entityStream.readAllBytes();
        assertEquals(original, new String(roundTrip, StandardCharsets.UTF_8));
    }

    // ─── Header / query handling ────────────────────────────────────────

    @Test
    void headerKeysAreLowercasedForFactExtraction() throws Exception {
        // The rule predicate uses lowercase header keys (engine convention).
        // The filter calls firstValues(headers, /*lowercaseKeys=*/ true);
        // here we add a rule against headers.x-impersonate to prove it.
        Client custom = Client.init(Config.builder().rules(
                "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":["
                + "{\"id\":\"flag_impersonate\",\"name\":\"\","
                + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/v1/users/:id\"}},"
                + "  \"enabled\":true,\"priority\":50,"
                + "  \"condition\":{\"all\":[{\"field\":\"headers.x-impersonate\",\"operator\":\"exists\"}]},"
                + "  \"action\":{\"type\":\"flag\"}}"
                + "]}").build());
        try {
            KLSwitchFilter filter = filterWithBindingFor(custom, "/v1/users/:id");
            StubCtx ctx = ctxBuilder()
                    .method("GET")
                    .path("v1/users/42")
                    // JAX-RS preserves operator-set case — `X-Impersonate`.
                    // The filter must lowercase before facts are emitted
                    // so the rule's `headers.x-impersonate` selector hits.
                    .header("X-Impersonate", "ops-user")
                    .build();
            filter.filter(ctx.proxy());
            EvaluationResult result = (EvaluationResult) ctx.properties.get(KLSwitchFilter.RESULT_PROPERTY);
            assertNotNull(result);
            assertEquals(1, result.matched().size());
            assertEquals("flag_impersonate", result.matched().get(0).id());
        } finally {
            custom.close();
        }
    }

    // ─── Global filter: templates without annotations ───────────────────

    @Test
    void globalFilterDerivesTemplateFromJaxRsPathAnnotations() throws Exception {
        // No @KLSwitchBinding anywhere on the resource — the whole point of
        // the global filter is that unannotated resources are still guarded,
        // and still against their templated route rather than /v1/payments/8123.
        KLSwitchGlobalFilter filter = new KLSwitchGlobalFilter(ks);
        injectResourceInfo(filter, stubResourceInfo(
                PathAnnotatedResource.class,
                PathAnnotatedResource.class.getMethod("collect")));

        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/8123")
                .body("{\"amount\":70000}")
                .build();
        filter.filter(ctx.proxy());

        assertNotNull(ctx.abortedWith, "derived /v1/payments/:id should match the block rule");
        assertEquals(429, ctx.abortedWith.getStatus());
    }

    @Test
    void globalFilterBindingPathStillOverridesDerivedTemplate() throws Exception {
        KLSwitchGlobalFilter filter = new KLSwitchGlobalFilter(ks);
        injectResourceInfo(filter, stubResourceInfo(
                PathAnnotatedResource.class,
                PathAnnotatedResource.class.getMethod("overridden")));

        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/whatever")
                .body("{\"amount\":70000}")
                .build();
        filter.filter(ctx.proxy());

        // @KLSwitchBinding(path="/v1/blocked") wins over the derived
        // /v1/payments/:override, so the default-status rule fires instead.
        assertNotNull(ctx.abortedWith);
        assertEquals(403, ctx.abortedWith.getStatus());
    }

    @Test
    void nameBoundFilterAlsoDerivesTemplateWhenBindingHasNoPath() throws Exception {
        // @KLSwitchBinding with no path() used to fall through to the
        // concrete URL; now it derives from @Path like everything else.
        KLSwitchFilter filter = new KLSwitchFilter(ks);
        injectResourceInfo(filter, stubResourceInfo(
                PathAnnotatedResource.class,
                PathAnnotatedResource.class.getMethod("bareBinding")));

        StubCtx ctx = ctxBuilder()
                .method("POST")
                .path("v1/payments/8123")
                .body("{\"amount\":70000}")
                .build();
        filter.filter(ctx.proxy());

        assertNotNull(ctx.abortedWith);
        assertEquals(429, ctx.abortedWith.getStatus());
    }

    // ─── One guard per client ───────────────────────────────────────────

    @Test
    void secondJaxRsGuardOnSameClientIsRefusedAtConstruction() {
        new KLSwitchGlobalFilter(ks);
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new KLSwitchFilter(ks));
        assertTrue(ex.getMessage().contains("already guarded"), ex.getMessage());
    }

    @Test
    void servletAndJaxRsGuardsOnSameClientAreRefused() {
        // Dropwizard and Quarkus run both layers, so this pairing evaluates
        // every request twice and doubles the analytics decision count.
        new KLSwitchFilter(ks);
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new com.klswitch.servlet.KLSwitchFilter(ks));
        assertTrue(ex.getMessage().contains("JAX-RS filter"), ex.getMessage());
    }

    @Test
    void oneGuardPerClientIsStillAllowed() {
        new KLSwitchGlobalFilter(ks);   // must not throw
    }

    /** Resource carrying only standard JAX-RS @Path — no KLSwitch annotations. */
    @SuppressWarnings("unused")
    @Path("/v1/payments")
    public static class PathAnnotatedResource {
        @POST @Path("/{id}")
        public void collect() {}

        @POST @Path("/{override}")
        @KLSwitchBinding(path = "/v1/blocked")
        public void overridden() {}

        @POST @Path("/{id}")
        @KLSwitchBinding          // path() defaults to "" — derive from @Path
        public void bareBinding() {}
    }

    // ─── Test infrastructure ────────────────────────────────────────────

    /** Build a filter against the default client whose stubbed
     *  ResourceInfo points at the TestResource method with a
     *  matching @KLSwitchBinding path. */
    private KLSwitchFilter filterWithBinding(String canonical) throws Exception {
        return filterWithBindingFor(ks, canonical);
    }

    private KLSwitchFilter filterWithBindingFor(Client client, String canonical) throws Exception {
        KLSwitchFilter filter = new KLSwitchFilter(client);
        Method handler = resolveHandlerForBinding(canonical);
        ResourceInfo info = stubResourceInfo(TestResource.class, handler);
        injectResourceInfo(filter, info);
        return filter;
    }

    private KLSwitchFilter filterWithoutBinding() throws Exception {
        KLSwitchFilter filter = new KLSwitchFilter(ks);
        Method handler = TestResource.class.getMethod("noBinding");
        ResourceInfo info = stubResourceInfo(TestResource.class, handler);
        injectResourceInfo(filter, info);
        return filter;
    }

    /** Map a canonical-path string to a TestResource method whose
     *  {@code @KLSwitchBinding(path=...)} matches. Tests only need
     *  the three paths used by the inline ruleset above. */
    private static Method resolveHandlerForBinding(String canonical) throws NoSuchMethodException {
        return switch (canonical) {
            case "/v1/payments/:id" -> TestResource.class.getMethod("paymentsId");
            case "/v1/users/:id"    -> TestResource.class.getMethod("usersId");
            case "/v1/blocked"      -> TestResource.class.getMethod("blocked");
            case ""                 -> TestResource.class.getMethod("emptyBinding");
            default -> throw new AssertionError("test fixture missing for canonical: " + canonical);
        };
    }

    /** Set the @Context private ResourceInfo resourceInfo field via reflection
     *  (CDI normally does this; we don't have a container at test time). */
    private static void injectResourceInfo(AbstractKLSwitchFilter filter, ResourceInfo info) throws Exception {
        var field = AbstractKLSwitchFilter.class.getDeclaredField("resourceInfo");
        field.setAccessible(true);
        field.set(filter, info);
    }

    /** A JDK Proxy ResourceInfo whose getResourceMethod() returns `method`
     *  (so the filter can read the @KLSwitchBinding annotation from it). */
    private static ResourceInfo stubResourceInfo(Class<?> resourceClass, Method method) {
        return (ResourceInfo) Proxy.newProxyInstance(
                ResourceInfo.class.getClassLoader(),
                new Class<?>[]{ResourceInfo.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getResourceMethod" -> method;
                    case "getResourceClass"  -> resourceClass;
                    default                  -> null;
                });
    }

    // Resource methods used as carriers for the @KLSwitchBinding annotation.
    // One per canonical path used by tests — the filter reads the annotation
    // off whichever method ResourceInfo.getResourceMethod() points at.
    @SuppressWarnings("unused")
    public static class TestResource {
        @KLSwitchBinding(path = "/v1/payments/:id")
        public void paymentsId() {}

        @KLSwitchBinding(path = "/v1/users/:id")
        public void usersId() {}

        @KLSwitchBinding(path = "/v1/blocked")
        public void blocked() {}

        @KLSwitchBinding   // path() = "" default — fall-back case
        public void emptyBinding() {}

        // No @KLSwitchBinding at all — fall-back case (no binding present).
        public void noBinding() {}
    }

    private static StubCtxBuilder ctxBuilder() { return new StubCtxBuilder(); }

    /** Fluent builder for the proxied ContainerRequestContext. */
    private static final class StubCtxBuilder {
        private String method = "GET";
        private String path = "";
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        private final MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        private final MultivaluedMap<String, String> pathParams = new MultivaluedHashMap<>();
        private byte[] body = null;
        private MediaType contentType = MediaType.APPLICATION_JSON_TYPE;
        private boolean explicitContentType = false;
        private boolean noEntity = false;

        StubCtxBuilder method(String v) { this.method = v; return this; }
        StubCtxBuilder path(String v) { this.path = v; return this; }
        StubCtxBuilder header(String k, String v) { this.headers.add(k, v); return this; }
        StubCtxBuilder query(String k, String v) { this.query.add(k, v); return this; }
        StubCtxBuilder body(String v) { this.body = v.getBytes(StandardCharsets.UTF_8); return this; }
        StubCtxBuilder bodyBytes(byte[] v) { this.body = v; return this; }
        StubCtxBuilder contentType(MediaType v) {
            this.contentType = v; this.explicitContentType = true; return this;
        }
        StubCtxBuilder noEntity() { this.noEntity = true; return this; }

        StubCtx build() {
            StubCtx ctx = new StubCtx();
            ctx.method = method;
            ctx.headers = headers;
            ctx.uriInfo = buildUriInfo(path, query, pathParams);
            ctx.mediaType = (explicitContentType ? contentType : MediaType.APPLICATION_JSON_TYPE);
            ctx.hasEntity = !noEntity && (body != null);
            ctx.entityStream = (body != null) ? new ByteArrayInputStream(body) : new ByteArrayInputStream(new byte[0]);
            return ctx;
        }

        private static UriInfo buildUriInfo(String path,
                                            MultivaluedMap<String, String> query,
                                            MultivaluedMap<String, String> pathParams) {
            return (UriInfo) Proxy.newProxyInstance(
                    UriInfo.class.getClassLoader(),
                    new Class<?>[]{UriInfo.class},
                    (proxy, m, args) -> switch (m.getName()) {
                        case "getPath"            -> path;
                        case "getQueryParameters" -> query;
                        case "getPathParameters"  -> pathParams;
                        default                   -> null;
                    });
        }
    }

    /** Stateful test ctx: records calls + lets the proxy delegate cleanly. */
    private static final class StubCtx implements InvocationHandler {
        String method;
        MultivaluedMap<String, String> headers;
        UriInfo uriInfo;
        MediaType mediaType;
        boolean hasEntity;
        InputStream entityStream;
        final Map<String, Object> properties = new java.util.LinkedHashMap<>();
        Response abortedWith;

        // The proxy itself, lazily built so we can hand it back through
        // an implicit conversion in the test's `filter.filter(ctx)` calls.
        private ContainerRequestContext proxy;

        ContainerRequestContext proxy() {
            if (proxy == null) {
                proxy = (ContainerRequestContext) Proxy.newProxyInstance(
                        ContainerRequestContext.class.getClassLoader(),
                        new Class<?>[]{ContainerRequestContext.class},
                        this);
            }
            return proxy;
        }

        @Override
        public Object invoke(Object _proxy, Method m, Object[] args) {
            return switch (m.getName()) {
                case "getMethod"        -> method;
                case "getHeaders"       -> headers;
                case "getUriInfo"       -> uriInfo;
                case "getMediaType"     -> mediaType;
                case "hasEntity"        -> hasEntity;
                case "getEntityStream"  -> entityStream;
                case "setEntityStream"  -> { entityStream = (InputStream) args[0]; yield null; }
                case "setProperty"      -> { properties.put((String) args[0], args[1]); yield null; }
                case "abortWith"        -> { abortedWith = (Response) args[0]; yield null; }
                default                 -> null;
            };
        }
    }

}
