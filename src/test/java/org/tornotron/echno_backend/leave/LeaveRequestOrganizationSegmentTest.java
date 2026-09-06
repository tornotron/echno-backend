package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The organization-wide leave-request listing publishes an {@code organizationId} in its path and
 * bound no parameter for it at all. The handler read {@code TenantContext} and answered the
 * caller's own tenant whatever the segment said, so a caller who put another organization there
 * was handed their own leave requests as though the id had been honoured. That is worse for the
 * caller than a refusal, and it is a claim the published contract makes and the handler does not
 * keep.
 *
 * <p>The segment now binds and is checked against the tenant in force, which is the treatment the
 * manager directory and the leave-policy listing took in the same family. Nothing in
 * {@code echno-core} or {@code echno-web} calls this route: both reach the {@code /web} twin
 * beside it, which takes no segment. So the check costs no screen, and whether the route should
 * keep the segment at all is a contract decision for a deliberate release rather than part of
 * repairing the guard.
 */
@WebMvcTest(LeaveRequestController.class)
@Import(LeaveRequestOrganizationSegmentTest.TestSecurityConfig.class)
class LeaveRequestOrganizationSegmentTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeaveRequestService requestService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** An hr-admin of their own organization and of no other. */
    private void callerIsAnHrAdmin() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isCurrentTenant(OWN_ORG)).thenReturn(true);
        when(orgSecurity.isCurrentTenant(FOREIGN_ORG)).thenReturn(false);
    }

    @Test
    void theListingStillAnswersForTheCallersOwnOrganization() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-requests/organizationId/" + OWN_ORG).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void namingAnotherOrganizationIsRefusedRatherThanIgnored() throws Exception {
        callerIsAnHrAdmin();

        mockMvc.perform(get("/api/v1/leave-requests/organizationId/" + FOREIGN_ORG).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(requestService);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }
}
