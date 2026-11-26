package com.klswitch.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.servlet.KLSwitchFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Drives a real {@code DispatcherServlet} and a real
 * {@code RequestMappingHandlerMapping} through MockMvc, so the resolver is
 * verified against actual Spring dispatch rather than a stub.
 */
class SpringRouteResolverTest {

    /** params.id proves path variables reach the engine; body.amount proves the
     *  filter's body buffering still works underneath the resolver. */
    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":["
            + "{\"id\":\"block_large_payment\",\"name\":\"large payment\","
            + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/payments/:id\"}},"
            + "  \"enabled\":true,\"priority\":100,"
            + "  \"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":50000}]},"
            + "  \"action\":{\"type\":\"block\",\"payload\":{\"statusCode\":429}}},"
            + "{\"id\":\"block_user_42\",\"name\":\"user 42\","
            + "  \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/v1/users/:id\"}},"
            + "  \"enabled\":true,\"priority\":100,"
            + "  \"condition\":{\"all\":[{\"field\":\"params.id\",\"operator\":\"eq\",\"value\":\"42\"}]},"
            + "  \"action\":{\"type\":\"block\",\"payload\":{\"statusCode\":418}}}"
            + "]}";

    static final AtomicReference<String> LAST_BODY_SEEN = new AtomicReference<>();

    private Client ks;
    private AnnotationConfigWebApplicationContext ctx;

    @BeforeEach
    void setUp() {
        ks = Client.init(Config.builder().rules(RULES).build());
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.register(WebConfig.class);
        ctx.setServletContext(new MockServletContext());
        ctx.refresh();
        LAST_BODY_SEEN.set(null);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
        if (ks != null) ks.close();
    }

    private List<HandlerMapping> mappings() {
        List<HandlerMapping> all = new ArrayList<>(ctx.getBeansOfType(HandlerMapping.class).values());
        all.sort(AnnotationAwareOrderComparator.INSTANCE);
        return all;
    }

    private MockMvc mvcWithGuard() {
        KLSwitchFilter filter =
                KLSwitchFilter.withRouteResolver(ks, new SpringRouteResolver(mappings()));
        return MockMvcBuilders.webAppContextSetup(ctx).addFilters(filter).build();
    }

    @Test
    void templatedPathReachesTheEngineSoTemplateRulesMatch() throws Exception {
        // The whole point: without the resolver the engine would see
        // /v1/payments/8123 and this rule would silently never fire.
        mvcWithGuard()
                .perform(post("/v1/payments/8123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":70000}"))
                .andExpect(r -> assertEquals(429, r.getResponse().getStatus()));
    }

    @Test
    void pathVariablesReachTheEngineAsParamsFacts() throws Exception {
        mvcWithGuard()
                .perform(get("/v1/users/42"))
                .andExpect(r -> assertEquals(418, r.getResponse().getStatus()));

        // A different id must not match — proves the value is real, not just present.
        mvcWithGuard2()
                .perform(get("/v1/users/7"))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));
    }

    @Test
    void bodySurvivesBufferingAndStillReachesTheController() throws Exception {
        MvcResult res = mvcWithGuard()
                .perform(post("/v1/payments/8123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andReturn();

        assertEquals(200, res.getResponse().getStatus(), "below the threshold, must pass through");
        assertEquals("8123", LAST_BODY_SEEN.get(),
                "the controller must still receive its @PathVariable and @RequestBody");
    }

    @Test
    void unmappedRequestFallsBackToTheConcreteUriInsteadOfFailing() throws Exception {
        // No handler matches; the filter must not 500 before any rule runs.
        int status = mvcWithGuard()
                .perform(get("/nothing/here"))
                .andReturn().getResponse().getStatus();
        assertEquals(404, status);
    }

    @Test
    void withoutTheResolverTheSameRuleNeverFires() throws Exception {
        // The state this adapter exists to fix. The plain filter reports
        // /v1/payments/8123, so a rule selecting /v1/payments/:id does not
        // match and the request sails through with 200.
        Client bare = Client.init(Config.builder().rules(RULES).build());
        try {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx)
                    .addFilters(new KLSwitchFilter(bare))
                    .build();
            int status = mvc.perform(post("/v1/payments/8123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":70000}"))
                    .andReturn().getResponse().getStatus();
            assertEquals(200, status,
                    "concrete path must not match the templated selector — this is the bug");
        } finally {
            bare.close();
        }
    }

    @Test
    void earlyMappingDoesNotRunInterceptorsTwice() throws Exception {
        // The resolver asks HandlerMapping to map the request before
        // DispatcherServlet does. Mapping must stay a lookup: getHandler builds
        // the execution chain, it does not invoke it. If this ever regressed,
        // every Spring interceptor in the app would run twice per request.
        CountingInterceptor.PRE_HANDLE_CALLS.set(0);

        mvcWithGuard()
                .perform(post("/v1/payments/8123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus()));

        assertEquals(1, CountingInterceptor.PRE_HANDLE_CALLS.get(),
                "the app's own interceptors must run exactly once per request");
    }

    // ─── Manifest discovery ─────────────────────────────────────────────
    //
    // Moved out. SpringManifestSource used to exist twice under the identical name
    // com.klswitch.spring.SpringManifestSource — once here and once in the
    // spring-boot-starter — with different code: only the starter's copy took an
    // ApplicationContext, filtered BasicErrorController, and converted {id} to :id. The
    // starter depends on this module, so both landed on every Spring Boot application's
    // classpath and which one loaded was decided by jar ordering. The duplicate here is
    // deleted; Spring support belongs in the starter, and its SpringManifestSourceTest
    // carries this coverage. noSpringManifestSourceInThisModule() keeps it from coming back.

    @Test
    void springPatternsConvertToTheEngineDialect() {
        assertEquals("/v1/payments/:id", SpringRouteResolver.toEnginePath("/v1/payments/{id}"));
        assertEquals("/v1/orgs/:org/teams/:team",
                SpringRouteResolver.toEnginePath("/v1/orgs/{org}/teams/{team}"));
        assertEquals("/static/**", SpringRouteResolver.toEnginePath("/static/{*path}"));
        assertEquals("/v1/files/**", SpringRouteResolver.toEnginePath("/v1/files/**"));
        // Regex-constrained variables keep the name only — the engine matches
        // whole segments, so the constraint has nothing to bind to.
        assertEquals("/v1/payments/:id", SpringRouteResolver.toEnginePath("/v1/payments/{id:[0-9]+}"));
        assertEquals("/v1/years/:y", SpringRouteResolver.toEnginePath("/v1/years/{y:\\d{4}}"));
        assertEquals("/plain/path", SpringRouteResolver.toEnginePath("/plain/path"));
    }

    @Test
    void unbalancedPatternIsLeftVerbatimRatherThanThrowing() {
        assertTrue(SpringRouteResolver.toEnginePath("/v1/{oops").contains("{oops"));
    }

    // A second MockMvc for the same context — one guard per client is enforced,
    // so the second instance needs its own client.
    private MockMvc mvcWithGuard2() {
        Client other = Client.init(Config.builder().rules(RULES).build());
        KLSwitchFilter filter =
                KLSwitchFilter.withRouteResolver(other, new SpringRouteResolver(mappings()));
        return MockMvcBuilders.webAppContextSetup(ctx).addFilters(filter).build();
    }

    @Configuration
    @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {
        @Bean
        PaymentsController payments() {
            return new PaymentsController();
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new CountingInterceptor());
        }
    }

    /** Counts preHandle calls, to prove the resolver's early mapping does not
     *  invoke the app's interceptor chain a second time. */
    static class CountingInterceptor implements HandlerInterceptor {
        static final java.util.concurrent.atomic.AtomicInteger PRE_HANDLE_CALLS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response,
                                 Object handler) {
            PRE_HANDLE_CALLS.incrementAndGet();
            return true;
        }
    }

    @RestController
    static class PaymentsController {

        @PostMapping("/v1/payments/{id}")
        Map<String, Object> collect(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
            LAST_BODY_SEEN.set(id);
            return Map.of("ok", true, "id", id, "amount", body.get("amount"));
        }

        @GetMapping("/v1/users/{id}")
        Map<String, Object> user(@PathVariable("id") String id) {
            return Map.of("ok", true, "id", id);
        }
    }

    @Test
    @DisplayName("SpringManifestSource does not live in this module")
    void noSpringManifestSourceInThisModule() {
        // Two classes once shared the name com.klswitch.spring.SpringManifestSource: this
        // module's and the spring-boot-starter's. Different code — only the starter's took an
        // ApplicationContext, filtered BasicErrorController's /error, and converted {id} to :id.
        //
        // The starter depends on this module, so both reached every Spring Boot application's
        // classpath and the winner was decided by jar ordering. On the wrong order the starter's
        // call to of(ApplicationContext) is a NoSuchMethodError at startup; on a luckier one the
        // unfiltered copy loads and reports routes the application never declared. The testbed
        // happened to resolve the right one, which is not a property anybody should rely on.
        //
        // Asserted as a file check rather than through the classloader precisely because the
        // classloader is the thing that was ambiguous.
        java.io.File here = new java.io.File(
                "src/main/java/com/klswitch/spring/SpringManifestSource.java");
        assertTrue(!here.exists(),
                "SpringManifestSource is back in sdk-java. It belongs only in the "
                        + "spring-boot-starter; two classes with one name on one classpath is "
                        + "resolved by jar ordering, not by intent.");
    }
}
