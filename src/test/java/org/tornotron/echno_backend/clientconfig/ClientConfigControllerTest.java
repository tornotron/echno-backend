package org.tornotron.echno_backend.clientconfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An app with no token reads the configuration. */
@WebMvcTest(ClientConfigController.class)
@Import({ClientConfigSettings.class, ClientConfigControllerTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "keycloak.frontend.client-id=echno-frontend",
        "echno.client-config.minimum-android-version=2.4.0"
})
class ClientConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;
    @MockitoBean
    private RPTCache rptCache;

    @Test
    void anAnonymousCallerReadsIt() throws Exception {
        mockMvc.perform(get("/api/v1/public/client-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.provider").value("keycloak"))
                .andExpect(jsonPath("$.clientIds.android").value("echno-frontend"))
                .andExpect(jsonPath("$.minimumVersions.android").value("2.4.0"))
                .andExpect(jsonPath("$.minimumVersions.ios").doesNotExist());
    }

    /** Anonymous requests get through, so only the method guard decides. */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}
