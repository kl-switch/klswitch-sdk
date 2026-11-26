package com.klswitch.examples;

import com.klswitch.Client;
import com.klswitch.servlet.KLSwitchFilter;
import io.dropwizard.core.setup.Environment;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Compile-level proof that the servlet filter can be registered app-wide in
 * Dropwizard — i.e. that Dropwizard has a one-time integration path and is not
 * limited to per-resource JAX-RS annotations. Not executed against a server;
 * this pins the API shape the docs recommend.
 */
class ServletFilterWiringTest {

    @SuppressWarnings("unused")
    static void wire(Environment environment, Client ks) {
        environment.servlets()
                .addFilter("klswitch", new KLSwitchFilter(ks))
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    }

    @Test
    void wiringCompiles() {
        // The value is that `wire` above compiles against Dropwizard's real API.
    }
}
