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
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which organization the duplicate guard is answered against, on both twins.
 *
 * <p>Duplicating names two organizations: the one the session is scoped to, which owns the
 * source policy, and the target the copy is written into. The role check used to be evaluated
 * against the first only, so an hr-admin in their own organization could write a policy into a
 * second one on the strength of an employment record there and no role at all. These pin the
 * repaired shape: a role in the current tenant AND the same role in the organization named.
 *
 * <p>{@code hasAnyOrgRole(id, roles)} is the right half of the pair because it reads the
 * caller's authorities for a named organization directly, without going through
 * {@code TenantContext}, which by construction only ever holds the current tenant.
 */
@WebMvcTest({LeavePolicyController.class, LeavePolicyControllerWeb.class})
@Import(LeavePolicyDuplicateAuthzTest.TestSecurityConfig.class)
class LeavePolicyDuplicateAuthzTest {

    private static final long TARGET_ORG = 9L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeavePolicyService policyService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    private void callerHolds(boolean roleHere, boolean roleThere) {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(roleHere);
        when(orgSecurity.hasAnyOrgRole(eq(TARGET_ORG), any(String[].class))).thenReturn(roleThere);
        when(policyService.duplicatePolicy(anyLong(), anyLong())).thenReturn(new LeavePolicyDto());
    }

    @Test
    void duplicate_isForbidden_forAnAdminHereWhoHoldsNoRoleInTheTarget() throws Exception {
        callerHolds(true, false);

        mockMvc.perform(post("/api/v1/leave-policies/4/duplicate")
                        .param("targetOrganizationId", String.valueOf(TARGET_ORG))
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateOnTheWebTwin_isForbidden_forAnAdminHereWhoHoldsNoRoleInTheTarget() throws Exception {
        callerHolds(true, false);

        mockMvc.perform(post("/api/v1/leave-policies/web/duplicate")
                        .param("policyId", "4")
                        .param("targetOrganizationId", String.valueOf(TARGET_ORG))
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicate_isForbidden_forAnAdminInTheTargetWhoHoldsNoRoleHere() throws Exception {
        // The other side of the pair. The source policy is read out of the current tenant, so
        // the role there is not made redundant by holding one in the target.
        callerHolds(false, true);

        mockMvc.perform(post("/api/v1/leave-policies/4/duplicate")
                        .param("targetOrganizationId", String.valueOf(TARGET_ORG))
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicate_isCreated_forAnAdminInBoth() throws Exception {
        callerHolds(true, true);

        mockMvc.perform(post("/api/v1/leave-policies/4/duplicate")
                        .param("targetOrganizationId", String.valueOf(TARGET_ORG))
                        .with(jwt()))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateOnTheWebTwin_isCreated_forAnAdminInBoth() throws Exception {
        callerHolds(true, true);

        mockMvc.perform(post("/api/v1/leave-policies/web/duplicate")
                        .param("policyId", "4")
                        .param("targetOrganizationId", String.valueOf(TARGET_ORG))
                        .with(jwt()))
                .andExpect(status().isCreated());
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
