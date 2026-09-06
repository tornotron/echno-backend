package org.tornotron.echno_backend.projectInviteCode;

import org.junit.jupiter.api.BeforeEach;
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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that the invite-code endpoints refuse an organization the caller is not entitled to.
 *
 * <p>Both handlers name an organization in the path. A role check alone answers a different
 * question: whether the caller holds the role in the organization their own session is scoped
 * to. An hr-admin of one organization satisfies that while naming another, and generation is a
 * write whose product is a credential for whichever organization was named.
 *
 * <p>Nothing underneath catches it. {@code Organization} is the tenant root, so it implements
 * neither {@code TenantScopedEntity} nor carries the {@code orgFilter}, and
 * {@code TenantIsolationLoadListener} returns on its first line for anything that is not
 * tenant-scoped. Both defences are absent at exactly the point where a caller names an
 * organization by id, so the guard has to ask the question itself.
 *
 * <p>{@code @orgSecurity} is mocked so the branches are exercised without building JWT group
 * claims, and it is stubbed to behave as it would for a real hr-admin of organization
 * {@link #OWN_ORG}: entitled to their own organization, not to {@link #FOREIGN_ORG}, and
 * holding the role in the tenant their session is scoped to. The service is mocked and its
 * return value is irrelevant here; what matters is whether the request reaches it at all.
 */
@WebMvcTest(ProjectInviteCodeController.class)
@Import(ProjectInviteCodeTenantGuardTest.TestSecurityConfig.class)
class ProjectInviteCodeTenantGuardTest {

    private static final long OWN_ORG = 100L;
    private static final long FOREIGN_ORG = 200L;

    private static final String VALID_BODY = """
            {"designation":"Engineer","department":"Civil","status":"ACTIVE","maxUses":1,"validityDays":5}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectInviteCodeService projectInviteCodeService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void callerIsAnHrAdminOfTheirOwnOrganizationOnly() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);
        when(orgSecurity.isCurrentTenant(OWN_ORG)).thenReturn(true);
        when(orgSecurity.isCurrentTenant(FOREIGN_ORG)).thenReturn(false);
    }

    @Test
    void generate_forAnotherOrganization_isForbiddenAndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/invitation/web/generateCode/organizationId/" + FOREIGN_ORG)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        // The refusal has to land before the code is minted. A 403 over a persisted invite
        // would leave the credential in place for the listing to hand back.
        verifyNoInteractions(projectInviteCodeService);
    }

    @Test
    void generate_forTheCallersOwnOrganization_isStillAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/invitation/web/generateCode/organizationId/" + OWN_ORG)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void list_forAnotherOrganization_isForbiddenAndNeverReachesTheService() throws Exception {
        mockMvc.perform(get("/api/v1/invitation/web/organizationId/" + FOREIGN_ORG).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(projectInviteCodeService);
    }

    @Test
    void list_forTheCallersOwnOrganization_isStillAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/invitation/web/organizationId/" + OWN_ORG).with(jwt()))
                .andExpect(status().isOk());
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
