package com.klswitch.examples.spring;

import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.servlet.KLSwitchFilter;
import com.klswitch.spring.SpringManifestSource;
import com.klswitch.spring.SpringRouteResolver;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The entire KLSwitch integration for this app: one client, one filter.
 * No controller below knows KLSwitch exists.
 */
@Configuration
public class KLSwitchConfig {

    @Bean(destroyMethod = "close")
    public Client klswitchClient(ApplicationContext context) {
        Config.Builder cfg = Config.builder().rules(DemoRules.json());

        // With KS_TOKEN set, point at a real backend and report the API surface.
        // The per-(service, environment) API key lets the backend derive both.
        String token = System.getenv("KS_TOKEN");
        if (token != null && !token.isBlank()) {
            String baseUrl = System.getenv("KS_BASE_URL");
            cfg.token(token)
               .baseUrl(baseUrl != null ? baseUrl : "http://localhost:40010")
               .analyticsBatchSize(1)
               .analyticsFlushIntervalMs(500)
               // Read from Spring's own @GetMapping/@PostMapping registry.
               // ServletManifestSource would report only the DispatcherServlet
               // at "/", leaving the console with nothing to suggest.
               .manifest(SpringManifestSource.of(context));
        }
        return Client.init(cfg.build());
    }

    /**
     * Passing the handler mappings is what gets templated paths. A filter runs
     * before {@code DispatcherServlet} has mapped anything, so without them the
     * engine would see {@code /v1/payments/8123} and the demo rule — which
     * selects {@code /v1/payments/:id} — would silently never match.
     */
    @Bean
    public FilterRegistrationBean<KLSwitchFilter> klswitchFilter(
            Client ks, List<HandlerMapping> handlerMappings) {

        var reg = new FilterRegistrationBean<>(
                KLSwitchFilter.withRouteResolver(ks, new SpringRouteResolver(handlerMappings)));
        reg.addUrlPatterns("/*");
        return reg;
    }
}
