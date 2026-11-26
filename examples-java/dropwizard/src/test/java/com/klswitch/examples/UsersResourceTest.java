package com.klswitch.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.klswitch.Client;
import com.klswitch.Config;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DropwizardExtensionsSupport.class)
class UsersResourceTest {

    private static final Client KS = Client.init(Config.builder().rules(DemoRules.json()).build());

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(Jackson.newObjectMapper())
            .addResource(new UsersResource(new KLSwitchGuard(KS), new UserService()))
            .build();

    @Test
    void flagsAdminAccessViaQuery() {
        try (Response r = EXT.target("/v1/users/42").queryParam("role", "admin").request().get()) {
            assertEquals(200, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("flag_admin_access"));
        }
    }

    @Test
    void flagsAdminAccessViaHeader() {
        try (Response r = EXT.target("/v1/users/42").request().header("x-impersonate", "ops").get()) {
            assertEquals(200, r.getStatus());
            assertTrue(r.readEntity(String.class).contains("flag_admin_access"));
        }
    }

    @Test
    void noFlagForNormalUser() {
        try (Response r = EXT.target("/v1/users/42").request().get()) {
            assertEquals(200, r.getStatus());
            assertFalse(r.readEntity(String.class).contains("flag_admin_access"));
        }
    }
}
