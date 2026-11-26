package com.klswitch.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.klswitch.Client;
import com.klswitch.EvaluationResult;
import com.klswitch.MatchedRule;
import com.klswitch.spring.KLSwitchAutoConfigurationTest.TestApp;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-to-end test of the Spring Boot starter: boot a real Spring
 * context, drive HTTP requests through MockMvc, assert the rules engine
 * fires and the interceptor enforces the cross-language block policy.
 *
 * <p>Constructs a {@link Client} via a {@code @Primary} bean override
 * so we don't need a real backend at test time — the test app uses an
 * inline ruleset matching the rules every other adapter test uses.
 */
@SpringBootTest(
        classes = {TestApp.class, KLSwitchAutoConfigurationTest.TestController.class},
        // Enable the auto-config by satisfying its @ConditionalOnProperty.
        properties = {"klswitch.token=ks_live_test"})
@AutoConfigureMockMvc
class KLSwitchAutoConfigurationTest {

    // Rules use string-valued conditions (header presence + query eq) so
    // the test driver doesn't need a body cache — the interceptor only
    // extracts method/path/headers/query. URL params are always strings;
    // the engine doesn't auto-coerce, so we use string-shaped operators.
    private static final String RULES =
            "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[" +
            "{\"id\":\"block_dangerous_payment\",\"name\":\"dangerous\"," +
            " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/v1/payments/:id\"}}," +
            " \"enabled\":true,\"priority\":100," +
            " \"condition\":{\"all\":[{\"field\":\"headers.x-danger\",\"operator\":\"exists\"}]}," +
            " \"action\":{\"type\":\"block\",\"payload\":{\"statusCode\":429}}}," +
            "{\"id\":\"flag_admin\",\"name\":\"flag\"," +
            " \"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"GET\",\"path\":\"/v1/users/:id\"}}," +
            " \"enabled\":true,\"priority\":50," +
            " \"condition\":{\"all\":[{\"field\":\"query.role\",\"operator\":\"eq\",\"value\":\"admin\"}]}," +
            " \"action\":{\"type\":\"flag\"}}" +
            "]}";

    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext ctx;

    @Test
    void autoConfigCreatesClientBean() {
        Client ks = ctx.getBean(Client.class);
        assertThat(ks).isNotNull();
    }

    @Test
    void autoConfigCreatesInterceptorBean() {
        KLSwitchInterceptor i = ctx.getBean(KLSwitchInterceptor.class);
        assertThat(i).isNotNull();
    }

    @Test
    void blockingRuleShortCircuitsWithCustomStatusCode() throws Exception {
        // Triggered by the X-Danger header — header presence is the
        // simplest condition to drive cleanly from MockMvc.
        mvc.perform(post("/v1/payments/abc")
                        .header("X-Danger", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.ruleId").value("block_dangerous_payment"));
    }

    @Test
    void nonBlockingMatchPassesThroughAndAttachesResult() throws Exception {
        mvc.perform(get("/v1/users/42").param("role", "admin"))
                .andExpect(status().isOk())
                // Controller returns matched rule IDs read from the
                // request attribute the interceptor set.
                .andExpect(content().string("flag_admin"));
    }

    @Test
    void noMatchPassesThroughCleanly() throws Exception {
        mvc.perform(get("/v1/users/42").param("role", "user"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void safePaymentPassesThrough() throws Exception {
        // No X-Danger header → no match → handler runs.
        mvc.perform(post("/v1/payments/abc")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ─── Test app ─────────────────────────────────────────────────────────

    /** Minimal Spring Boot app the auto-config plugs into. */
    @SpringBootApplication
    public static class TestApp {

        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        /** Override the auto-config's Client bean so we don't need a
         *  real backend at test time. The auto-config sees it via
         *  @ConditionalOnMissingBean and skips its own. */
        @Bean
        @Primary
        public Client klswitchClient() {
            return Client.init(com.klswitch.Config.builder().rules(RULES).build());
        }
    }

    @RestController
    public static class TestController {

        @PostMapping("/v1/payments/{id}")
        public String createPayment(@PathVariable("id") String id) {
            return "paymentId=" + id;
        }

        @GetMapping("/v1/users/{id}")
        public String getUser(@PathVariable("id") String id, HttpServletRequest req) {
            EvaluationResult r = (EvaluationResult) req.getAttribute(KLSwitchInterceptor.RESULT_ATTR);
            if (r == null || r.matched().isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (MatchedRule m : r.matched()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(m.id());
            }
            return sb.toString();
        }
    }

    @Configuration
    static class TestControllers {
        @Bean
        public TestController testController() {
            return new TestController();
        }
    }
}
