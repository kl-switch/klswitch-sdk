package com.klswitch.spring;

import com.klswitch.Client;
import com.klswitch.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.klswitch.servlet.BodyCachingFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration entry point. Fires when both of these are true:
 * <ul>
 *   <li>{@code klswitch.token} is set (no token → no SDK).</li>
 *   <li>{@code klswitch.enabled} is not explicitly {@code false}
 *       (default {@code true}).</li>
 * </ul>
 *
 * <p>What gets registered:
 * <ol>
 *   <li>A {@link Client} bean built from {@link KLSwitchProperties}.
 *       Uses {@code destroyMethod="close"} so Spring tears it down
 *       cleanly on app shutdown.</li>
 *   <li>A {@link KLSwitchInterceptor} bean.</li>
 *   <li>A {@link WebMvcConfigurer} bean that registers the
 *       interceptor against all paths.</li>
 * </ol>
 *
 * <p>All beans are {@code @ConditionalOnMissingBean} so operators can
 * override any layer (e.g. supply their own {@link Client} with custom
 * options, or register the interceptor only against a subset of paths).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "klswitch", name = "token")
@ConditionalOnExpression("${klswitch.enabled:true}")
@EnableConfigurationProperties(KLSwitchProperties.class)
public class KLSwitchAutoConfiguration {

    /**
     * The client, with route discovery already wired.
     *
     * <h2>Why the manifest is configured here and not by the application</h2>
     *
     * <p>Every other wrapper needs the application to hand the SDK its app object, because the
     * middleware is given to the framework rather than the other way round — the SDK never
     * receives anything it could introspect. Spring is the exception: the container can inject
     * the context, and {@link SpringManifestSource} reads the handler mappings out of it. So a
     * Spring Boot application should need no line at all, and until now got no manifest at all
     * — the starter built the client without one and nothing said so.
     *
     * <p>Discovery is deferred, not run here. The handler mappings do not exist yet while this
     * bean is being built; the source holds the context and walks it when the reporter asks,
     * which is after the context has refreshed.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Client klswitchClient(KLSwitchProperties props, ApplicationContext context) {
        Config.Builder b = Config.builder().token(props.getToken());
        if (props.getBaseUrl()           != null) b.baseUrl(props.getBaseUrl());
        if (props.getInstanceId()        != null) b.instanceId(props.getInstanceId());
        if (props.getRefreshIntervalMs() != null) b.refreshIntervalMs(props.getRefreshIntervalMs());
        if (props.getTimeoutMs()         != null) b.timeoutMs(props.getTimeoutMs());
        if (props.getCachePath()         != null) b.cachePath(props.getCachePath());
        b.manifest(SpringManifestSource.of(context));
        return Client.init(b.build());
    }

    /**
     * Registers the body cache ahead of the dispatcher so the interceptor can see body facts.
     *
     * <p>Highest precedence: the body has to be buffered before anything else reads the stream.
     * Without this the interceptor evaluated every request with no body, which silently disabled
     * body-threshold rules and — worse — made negated body conditions match everything, blocking
     * traffic that should have passed.
     */
    @Bean
    @ConditionalOnMissingBean(name = "klswitchBodyCachingFilter")
    public FilterRegistrationBean<BodyCachingFilter> klswitchBodyCachingFilter(
            KLSwitchProperties props) {
        FilterRegistrationBean<BodyCachingFilter> reg = new FilterRegistrationBean<>(
                props.getMaxBodyBytes() != null
                        ? new BodyCachingFilter(props.getMaxBodyBytes())
                        : new BodyCachingFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean
    public KLSwitchInterceptor klswitchInterceptor(Client client) {
        return new KLSwitchInterceptor(client);
    }

    @Bean
    @ConditionalOnMissingBean(name = "klswitchWebMvcConfigurer")
    public WebMvcConfigurer klswitchWebMvcConfigurer(KLSwitchInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
