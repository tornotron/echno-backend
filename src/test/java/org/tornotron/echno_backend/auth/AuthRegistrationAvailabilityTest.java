package org.tornotron.echno_backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.SelfRegistration;
import org.tornotron.echno_backend.user.UserService;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration answers only where the provider takes it; elsewhere people arrive by invitation. */
@WebMvcTest(AuthController.class)
@Import(AuthRegistrationAvailabilityTest.TestSecurityConfig.class)
class AuthRegistrationAvailabilityTest {

    private static final String BODY = """
            {"name": "Ravi Kumar", "userName": "ravi", "email": "ravi@example.test", "password": "Secret-123",
             "phone": "9847012345", "gender": "M", "role": "EMPLOYEE", "dateOfBirth": "1990-01-01T00:00:00"}
            """;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private IdentityProvider identityProvider;
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;
    @MockitoBean
    private RPTCache rptCache;

    @Test
    void withNoSelfRegistrationTheRouteIsNotThere() throws Exception {
        when(identityProvider.capabilities()).thenReturn(IdentityCapabilities.none());

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        verifyNoInteractions(userService);
    }

    @Test
    void withSelfRegistrationItRegisters() throws Exception {
        when(identityProvider.capabilities()).thenReturn(
                new IdentityCapabilities(Optional.of(mock(SelfRegistration.class)), Optional.empty()));
        when(userService.registerUser(any())).thenReturn(new UserDto());

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

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
