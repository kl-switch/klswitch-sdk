package com.klswitch.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.EvaluationResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Servlet filter — drives the filter via JDK-proxied
 * {@link HttpServletRequest} / {@link HttpServletResponse} so we don't
 * need an embedded servlet container in the test classpath.
 */
class KLSwitchFilterTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[" +
            "{\"id\":\"block_large_payment\",\"name\":\"large\"," +
            " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/payments/:id\"}}," +
            " \"enabled\":true,\"priority\":100," +
            " \"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":50000}]}," +
            " \"action\":{\"type\":\"block\",\"payload\":{\"statusCode\":429}}}," +
            "{\"id\":\"block_default\",\"name\":\"default\"," +
            " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/blocked\"}}," +
            " \"enabled\":true,\"priority\":100," +
            " \"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}]}," +
            " \"action\":{\"type\":\"block\"}}," +
            "{\"id\":\"flag_admin\",\"name\":\"flag\"," +
            " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/v1/users/:id\"}}," +
            " \"enabled\":true,\"priority\":50," +
            " \"condition\":{\"all\":[{\"field\":\"query.role\",\"operator\":\"eq\",\"value\":\"admin\"}]}," +
            " \"action\":{\"type\":\"flag\"}}" +
            "]}";

    private Client ks;

    @BeforeEach
    void setUp() {
        ks = Client.init(Config.builder().rules(RULES).build());
    }

    @AfterEach
    void tearDown() {
        if (ks != null) ks.close();
    }

    // ─── Tests ────────────────────────────────────────────────────────────

    @Test
    void blockActionShortCircuitsWithStatusCode() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", "{\"amount\":70000}");
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();

        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        assertEquals(429, res.status);
        assertEquals("application/json", res.contentType);
        JsonNode body = M.readTree(res.bodyString());
        assertEquals(true, body.get("blocked").asBoolean());
        assertEquals("block_large_payment", body.get("ruleId").asText());
        assertEquals(0, chain.callCount, "block must short-circuit before the chain runs");
    }

    @Test
    void blockWithoutPayloadStatusCodeUses403Default() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/blocked");
        StubRequest req = newReq("POST", "{\"amount\":1}");
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();

        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        assertEquals(403, res.status);
    }

    @Test
    void smallPaymentPassesThroughAndAttachesResult() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", "{\"amount\":5}");
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();

        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        assertEquals(1, chain.callCount, "no match → chain must run");
        EvaluationResult result = (EvaluationResult) req.attributes.get(KLSwitchFilter.RESULT_ATTR);
        assertNotNull(result);
        assertTrue(result.matched().isEmpty());
    }

    @Test
    void flagActionPassesThroughWithResultAttached() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/users/:id");
        StubRequest req = newReq("GET", null);
        req.queryParams.put("role", new String[]{"admin"});
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();

        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        assertEquals(1, chain.callCount);
        EvaluationResult result = (EvaluationResult) req.attributes.get(KLSwitchFilter.RESULT_ATTR);
        assertEquals(1, result.matched().size());
        assertEquals("flag_admin", result.matched().get(0).id());
    }

    @Test
    void firstQueryValueWinsOnRepeats() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/users/:id");
        // role=admin then role=user → first ("admin") wins → rule fires.
        StubRequest req = newReq("GET", null);
        req.queryParams.put("role", new String[]{"admin", "user"});
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();
        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        EvaluationResult result = (EvaluationResult) req.attributes.get(KLSwitchFilter.RESULT_ATTR);
        assertEquals(1, result.matched().size());
        assertEquals("flag_admin", result.matched().get(0).id());

        // role=user then role=admin → first ("user") wins → no match.
        StubRequest req2 = newReq("GET", null);
        req2.queryParams.put("role", new String[]{"user", "admin"});
        DummyChain chain2 = new DummyChain();
        f.doFilter(req2.proxy(), new StubResponse().proxy(), chain2.proxy());
        EvaluationResult result2 = (EvaluationResult) req2.attributes.get(KLSwitchFilter.RESULT_ATTR);
        assertTrue(result2.matched().isEmpty());
    }

    @Test
    void headersAreLowercasedForFactExtraction() throws Exception {
        Client custom = Client.init(Config.builder().rules(
                "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[" +
                "{\"id\":\"impersonate\",\"name\":\"i\"," +
                " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/u\"}}," +
                " \"enabled\":true,\"priority\":10," +
                " \"condition\":{\"all\":[{\"field\":\"headers.x-impersonate\",\"operator\":\"exists\"}]}," +
                " \"action\":{\"type\":\"flag\"}}]}").build());
        try {
            KLSwitchFilter f = new KLSwitchFilter(custom, r -> "/u");
            StubRequest req = newReq("GET", null);
            req.headers.put("X-Impersonate", "ops");
            StubResponse res = new StubResponse();
            DummyChain chain = new DummyChain();
            f.doFilter(req.proxy(), res.proxy(), chain.proxy());

            EvaluationResult result = (EvaluationResult) req.attributes.get(KLSwitchFilter.RESULT_ATTR);
            assertEquals(1, result.matched().size());
        } finally {
            custom.close();
        }
    }

    @Test
    void malformedJsonBodyDoesNotCrashFilter() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", "{not valid json");
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();

        f.doFilter(req.proxy(), res.proxy(), chain.proxy());
        // Broken body → no facts → no match → chain runs.
        assertEquals(1, chain.callCount);
    }

    @Test
    void smallJsonBodyIsBufferedAndResetForChain() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", "{\"amount\":5}");
        StubResponse res = new StubResponse();
        // Chain reads the body via the wrapped request to confirm it's
        // still available — this is the contract: filter must not consume.
        DummyChain chain = new DummyChain((wreq, wres) -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                wreq.getInputStream().transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        f.doFilter(req.proxy(), res.proxy(), chain.proxy());

        assertEquals("{\"amount\":5}", chain.lastBodyRead);
    }

    @Test
    void oversizedBodySkipsFactsButFlowsThroughToChain() throws Exception {
        // Build a body > 1 MiB so the filter wrapper sees the "no buffer"
        // sentinel and the original stream is passed through untouched.
        StringBuilder sb = new StringBuilder("{\"amount\":70000,\"pad\":\"");
        sb.append("X".repeat((1 << 20) + 1024));
        sb.append("\"}");
        byte[] bigBody = sb.toString().getBytes(StandardCharsets.UTF_8);

        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", null);
        req.bodyBytes = bigBody;
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain((wreq, wres) -> {
            try {
                return String.valueOf(wreq.getInputStream().readAllBytes().length);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        f.doFilter(req.proxy(), res.proxy(), chain.proxy());
        // No body facts → no match → chain runs → reads full original body.
        assertEquals(0, res.status, "no block expected; res.status untouched");
        assertEquals(String.valueOf(bigBody.length), chain.lastBodyRead);
    }

    @Test
    void noEntityMeansNoBodyFacts() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", null);  // no body
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();
        f.doFilter(req.proxy(), res.proxy(), chain.proxy());
        assertEquals(1, chain.callCount);
    }

    @Test
    void nonJsonContentTypeSkipsBodyExtraction() throws Exception {
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/v1/payments/:id");
        StubRequest req = newReq("POST", "amount=70000");
        req.contentType = "application/x-www-form-urlencoded";
        StubResponse res = new StubResponse();
        DummyChain chain = new DummyChain();
        f.doFilter(req.proxy(), res.proxy(), chain.proxy());
        assertEquals(1, chain.callCount, "non-JSON body → no facts → no match → chain runs");
    }

    @Test
    void nullKsFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new KLSwitchFilter(null, r -> "/x"));
    }

    @Test
    void nullPathResolverFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new KLSwitchFilter(ks, null));
    }

    @Test
    void nonHttpRequestPassesThrough() throws Exception {
        // Plain ServletRequest (e.g. WebSocket upgrade) just flows through.
        KLSwitchFilter f = new KLSwitchFilter(ks, r -> "/x");
        ServletRequest req = (ServletRequest) Proxy.newProxyInstance(
                ServletRequest.class.getClassLoader(),
                new Class<?>[]{ServletRequest.class},
                (p, m, args) -> null);
        ServletResponse res = (ServletResponse) Proxy.newProxyInstance(
                ServletResponse.class.getClassLoader(),
                new Class<?>[]{ServletResponse.class},
                (p, m, args) -> null);
        DummyChain chain = new DummyChain();
        f.doFilter(req, res, chain.proxy());
        assertEquals(1, chain.callCount);
    }

    // ─── Stub helpers ────────────────────────────────────────────────────

    private StubRequest newReq(String method, String body) {
        StubRequest r = new StubRequest();
        r.method = method;
        r.contentType = "application/json";
        r.requestURI = "/some/url";
        if (body != null) r.bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return r;
    }

    private static final class StubRequest implements InvocationHandler {
        String method = "GET";
        String requestURI = "/";
        String contentType;
        byte[] bodyBytes;
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, String[]> queryParams = new LinkedHashMap<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();

        HttpServletRequest proxy() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, this);
        }

        @Override
        public Object invoke(Object _proxy, Method m, Object[] args) throws Throwable {
            return switch (m.getName()) {
                case "getMethod"      -> method;
                case "getRequestURI"  -> requestURI;
                case "getContentType" -> contentType;
                case "getInputStream" -> wrapStream(bodyBytes == null ? new byte[0] : bodyBytes);
                case "getHeaderNames" -> Collections.enumeration(new java.util.ArrayList<>(headers.keySet()));
                case "getHeader"      -> headers.get((String) args[0]);
                case "getParameterMap" -> queryParams;
                case "getParameterNames" -> Collections.enumeration(new java.util.ArrayList<>(queryParams.keySet()));
                case "getParameterValues" -> queryParams.get((String) args[0]);
                case "getAttribute"   -> attributes.get((String) args[0]);
                case "setAttribute"   -> { attributes.put((String) args[0], args[1]); yield null; }
                default               -> null;
            };
        }
    }

    private static ServletInputStream wrapStream(byte[] data) {
        ByteArrayInputStream backing = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override public int read() { return backing.read(); }
            @Override public boolean isFinished() { return backing.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { /* not supported */ }
        };
    }

    private static final class StubResponse implements InvocationHandler {
        int status = 0;
        String contentType;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintWriter writer = new PrintWriter(out);

        HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, this);
        }

        String bodyString() {
            writer.flush();
            return out.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Object invoke(Object _proxy, Method m, Object[] args) {
            return switch (m.getName()) {
                case "setStatus"      -> { status = (int) args[0]; yield null; }
                case "setContentType" -> { contentType = (String) args[0]; yield null; }
                case "getWriter"      -> writer;
                case "setHeader"      -> null;
                default               -> null;
            };
        }
    }

    /** Stub chain that optionally invokes a body-reader so tests can
     *  observe the downstream-visible request body. */
    private static final class DummyChain implements InvocationHandler {
        int callCount = 0;
        String lastBodyRead;
        private final java.util.function.BiFunction<HttpServletRequest, HttpServletResponse, String> reader;

        DummyChain() { this(null); }
        DummyChain(java.util.function.BiFunction<HttpServletRequest, HttpServletResponse, String> reader) {
            this.reader = reader;
        }

        FilterChain proxy() {
            return (FilterChain) Proxy.newProxyInstance(
                    FilterChain.class.getClassLoader(),
                    new Class<?>[]{FilterChain.class}, this);
        }

        @Override
        public Object invoke(Object _proxy, Method m, Object[] args) {
            if ("doFilter".equals(m.getName())) {
                callCount++;
                if (reader != null) {
                    lastBodyRead = reader.apply((HttpServletRequest) args[0], (HttpServletResponse) args[1]);
                }
            }
            return null;
        }
    }
}
