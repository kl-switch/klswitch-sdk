package com.klswitch.examples;

import com.codahale.metrics.health.HealthCheck;
import com.klswitch.Client;
import com.klswitch.Config;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Environment;

/**
 * Runnable Dropwizard server wiring the KLSwitch client into JAX-RS resources.
 * Run: {@code mvn -q -DskipTests package} then
 * {@code java -jar target/klswitch-examples-java-0.0.0.jar server config.yml}.
 * The resource-layer tests ({@code *ResourceTest}) exercise the same wiring
 * in-memory without booting Jetty.
 */
public final class App extends Application<DemoConfiguration> {

    public static void main(String[] args) throws Exception {
        // Default to `server` with built-in config so `java ... App` just works.
        new App().run(args.length == 0 ? new String[] {"server"} : args);
    }

    @Override
    public void run(DemoConfiguration configuration, Environment environment) {
        Config.Builder cfg = Config.builder().rules(DemoRules.json());
        // When KS_TOKEN is set, point at a real backend (klswitch) and emit analytics;
        // the per-(service,env) API key lets the backend derive service + env.
        String token = System.getenv("KS_TOKEN");
        if (token != null && !token.isBlank()) {
            String baseUrl = System.getenv("KS_BASE_URL");
            cfg.token(token)
               .baseUrl(baseUrl != null ? baseUrl : "http://localhost:40010")
               .analyticsBatchSize(1)
               .analyticsFlushIntervalMs(500)
               // Explicit API surface → powers the console's APIs tab.
               .manifest(com.klswitch.ManifestSource.of("jax-rs", () -> java.util.List.of(
                   new com.klswitch.ManifestRoute("POST", "/v1/payments/:id"),
                   new com.klswitch.ManifestRoute("GET", "/v1/users/:id"))));
        }
        Client ks = Client.init(cfg.build());
        KLSwitchGuard guard = new KLSwitchGuard(ks);

        environment.jersey().register(new PaymentsResource(guard, new PaymentService()));
        environment.jersey().register(new UsersResource(guard, new UserService()));

        environment.healthChecks().register("klswitch", new HealthCheck() {
            @Override
            protected Result check() {
                return ks.getStatus().initialized() ? Result.healthy() : Result.unhealthy("engine not initialized");
            }
        });
    }
}
