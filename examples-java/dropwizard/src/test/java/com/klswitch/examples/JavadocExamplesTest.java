package com.klswitch.examples;

import com.klswitch.Client;
import com.klswitch.Config;
import com.klswitch.EvaluationResult;
import com.klswitch.ManifestRoute;
import com.klswitch.ManifestSource;
import com.klswitch.RequestContext;
import com.klswitch.jaxrs.JaxRsManifestSource;
import com.klswitch.jaxrs.KLSwitchBinding;
import com.klswitch.servlet.ServletManifestSource;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every code shape the sdk-java javadoc shows, transcribed and compiled against
 * the real API.
 *
 * Javadoc is documentation people trust more than the website — it surfaces on
 * hover and in generated API docs — and nothing else in the build exercises it.
 * A FastAPI docstring that handed back a null client, and nine fabricated Go
 * symbols on the website, both survived full test suites for exactly this
 * reason.
 */
class JavadocExamplesTest {

    /** Config.java: token only; service + environment derived from the key. */
    @SuppressWarnings("unused")
    static Client fromConfigJavadoc() {
        return Client.init(Config.builder()
                .token(System.getenv("KS_TOKEN"))
                .build());
    }

    /** ManifestSource.java: the three documented factories. */
    @SuppressWarnings("unused")
    static void fromManifestSourceJavadoc(Application app, ServletContext ctx) {
        ManifestSource a = JaxRsManifestSource.of(app);
        ManifestSource b = ServletManifestSource.of(ctx);
        ManifestSource c = ManifestSource.of("custom",
                () -> List.of(new ManifestRoute("POST", "/v1/payments/:id")));
    }

    /** KLSwitchBinding.java: the annotation shape its javadoc shows. */
    @Path("/v1/payments")
    @KLSwitchBinding
    public static class PaymentsResource {
        @POST
        @Path("/{id}")
        @KLSwitchBinding(path = "/v1/payments/:id")
        public String collect() {
            return "{}";
        }
    }

    /** Client.java: the documented manual-integration path. */
    @SuppressWarnings("unused")
    static EvaluationResult fromClientJavadoc(Client ks) {
        return ks.evaluate(RequestContext.toEvaluationInput(
                "POST", "/v1/payments/:id",
                Map.of(), Map.of(), Map.of("id", "7"), Map.of("amount", 1)));
    }

    @Test
    void javadocExamplesCompile() {
        // The value is that everything above compiles against the shipped API.
    }
}
