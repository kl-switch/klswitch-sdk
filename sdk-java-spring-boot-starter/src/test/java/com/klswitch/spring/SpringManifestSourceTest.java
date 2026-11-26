package com.klswitch.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.klswitch.ManifestRoute;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring discovery test. We don't use @EnableWebMvc — it pulls in
 * Spring's ResourceHandlerMapping which requires a real ServletContext.
 * The discovery code only needs the {@link RequestMappingHandlerMapping}
 * bean, so we wire one up directly against a vanilla
 * {@link AnnotationConfigApplicationContext} containing the controller
 * beans. Faster boot, no servlet container needed.
 */
class SpringManifestSourceTest {

    // ─── Path conversion ─────────────────────────────────────────────────

    @Test
    void convertToColonStyleStripsCurlyBraces() {
        assertThat(SpringManifestSource.convertToColonStyle("/v1/payments/{id}"))
                .isEqualTo("/v1/payments/:id");
        assertThat(SpringManifestSource.convertToColonStyle("/v1/users/{userId}/posts/{postId}"))
                .isEqualTo("/v1/users/:userId/posts/:postId");
    }

    @Test
    void convertToColonStyleStripsRegexSpec() {
        assertThat(SpringManifestSource.convertToColonStyle("/v1/items/{id:\\d+}"))
                .isEqualTo("/v1/items/:id");
    }

    @Test
    void convertToColonStylePassthroughForLiteralPaths() {
        assertThat(SpringManifestSource.convertToColonStyle("/v1/health")).isEqualTo("/v1/health");
    }

    // ─── Defensive paths ─────────────────────────────────────────────────

    @Test
    void discoverFromNullMappingReturnsEmpty() {
        assertThat(SpringManifestSource.discover(null)).isEmpty();
    }

    @Test
    void discoverFromNullContextReturnsEmpty() {
        assertThat(SpringManifestSource.discoverFromContext(null)).isEmpty();
    }

    @Test
    void discoverFromContextMissingBeanReturnsEmpty() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.refresh();
            assertThat(catchThrowableOfType(
                    () -> ctx.getBean(RequestMappingHandlerMapping.class),
                    NoSuchBeanDefinitionException.class)).isNotNull();
            assertThat(SpringManifestSource.discoverFromContext(ctx)).isEmpty();
        }
    }

    // ─── Full discovery (real controllers, real RequestMappingHandlerMapping) ───

    @Test
    void discoverWalksControllerHandlerMethods() {
        try (AnnotationConfigApplicationContext ctx = bootWithControllers()) {
            RequestMappingHandlerMapping mapping = ctx.getBean(RequestMappingHandlerMapping.class);
            List<ManifestRoute> routes = SpringManifestSource.discover(mapping);

            Set<String> pairs = routes.stream()
                    .map(r -> r.method() + " " + r.path())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(pairs).contains(
                    "POST /v1/payments/:id",
                    "GET /v1/payments/:id",
                    "DELETE /v1/payments",
                    "GET /v1/users/:userId/posts/:postId");
        }
    }

    @Test
    void discoverEmitsHandlerName() {
        try (AnnotationConfigApplicationContext ctx = bootWithControllers()) {
            List<ManifestRoute> routes = SpringManifestSource.discoverFromContext(ctx);
            ManifestRoute post = routes.stream()
                    .filter(r -> r.method().equals("POST") && r.path().equals("/v1/payments/:id"))
                    .findFirst().orElseThrow();
            // Class.method form, mirroring JAX-RS discovery.
            assertThat(post.handler()).isEqualTo("PaymentController.create");
        }
    }

    @Test
    void manifestSourceFrameworkIsSpring() {
        try (AnnotationConfigApplicationContext ctx = bootWithControllers()) {
            assertThat(SpringManifestSource.of(ctx).framework()).isEqualTo("spring");
        }
    }

    @Test
    void manifestSourceCollectFromHandlerMappingDirectly() {
        try (AnnotationConfigApplicationContext ctx = bootWithControllers()) {
            RequestMappingHandlerMapping mapping = ctx.getBean(RequestMappingHandlerMapping.class);
            List<ManifestRoute> routes = SpringManifestSource.of(mapping).collect();
            assertThat(routes).isNotEmpty();
        }
    }

    @Test
    void handlerWithoutExplicitMethodIsFlaggedAny() {
        // @RequestMapping(path=...) with no method= attribute matches every
        // verb — Spring reports an empty methods set. The discoverer emits
        // a single "ANY" route to flag that.
        try (AnnotationConfigApplicationContext ctx = bootWith(AnyVerbController.class)) {
            List<ManifestRoute> routes = SpringManifestSource.discoverFromContext(ctx);
            assertThat(routes).anyMatch(r -> r.method().equals("ANY") && r.path().equals("/v1/anything"));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** Boot a context with our standard two controllers + the discovery mapping bean. */
    private static AnnotationConfigApplicationContext bootWithControllers() {
        return bootWith(PaymentController.class, UserPostController.class);
    }

    /**
     * Boot a vanilla AnnotationConfigApplicationContext with the given
     * controller classes + the RequestMappingHandlerMapping bean wired
     * directly. We skip @EnableWebMvc deliberately — it requires a
     * ServletContext which we don't have outside @SpringBootTest.
     */
    private static AnnotationConfigApplicationContext bootWith(Class<?>... controllers) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) ctx.getBeanFactory();
        for (Class<?> c : controllers) {
            bf.registerBeanDefinition(c.getSimpleName(), new RootBeanDefinition(c));
        }
        bf.registerBeanDefinition(
                "requestMappingHandlerMapping",
                new RootBeanDefinition(RequestMappingHandlerMapping.class));
        ctx.refresh();
        return ctx;
    }

    private static <T extends Throwable> T catchThrowableOfType(Runnable r, Class<T> type) {
        try { r.run(); return null; }
        catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type + ", got " + t.getClass(), t);
        }
    }

    // ─── Test controllers ────────────────────────────────────────────────

    @RestController
    @RequestMapping("/v1/payments")
    public static class PaymentController {
        @PostMapping("/{id}")
        public String create(@PathVariable("id") String id) { return id; }

        @GetMapping("/{id}")
        public String get(@PathVariable("id") String id) { return id; }

        @DeleteMapping
        public String deleteAll() { return "ok"; }
    }

    @RestController
    public static class UserPostController {
        @GetMapping("/v1/users/{userId}/posts/{postId}")
        public String get(@PathVariable("userId") String u, @PathVariable("postId") String p) {
            return u + ":" + p;
        }
    }

    @RestController
    public static class AnyVerbController {
        // No HTTP method specified — matches every verb.
        @RequestMapping("/v1/anything")
        public String any() { return "ok"; }
    }
}
