package org.tornotron.echno_backend.common.module.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.module.NavDescriptor;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization on both module routes, plus the response shape the web loader depends on.
 *
 * <p>Method security comes from a minimal test filter chain and {@code @orgSecurity} is mocked,
 * as in {@code UserControllerAuthzTest}. The registry is real, built from two fixture modules,
 * so the JSON asserted here is what the controller produces rather than what a mock returned.
 * The {@code ORG_MEMBER_7} authority lets {@code TenantFilter} infer organization 7.
 */
@WebMvcTest(ModuleControllerWeb.class)
@Import({ModuleControllerWebAuthzTest.TestSecurityConfig.class, ModuleControllerWebAuthzTest.FixtureModules.class})
@TestPropertySource(properties = "echno.modules.killed.enabled=false")
class ModuleControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void installed_isForbidden_forANonAdminMember() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);

        mockMvc.perform(get("/api/v1/modules/web").with(memberOfOrgSeven()))
                .andExpect(status().isForbidden());
    }

    @Test
    void installed_listsEveryModuleWithSwitchAndEntitlement_forASystemAdmin() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);

        mockMvc.perform(get("/api/v1/modules/web").with(memberOfOrgSeven()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("inspections"))
                .andExpect(jsonPath("$[0].name").value("Site Inspections"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].entitlementFeatureKey").value("MODULE_INSPECTIONS"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].entitled").value(true))
                .andExpect(jsonPath("$[0].nav[0].label").value("Inspections"))
                .andExpect(jsonPath("$[0].nav[0].section").value("site"))
                .andExpect(jsonPath("$[0].nav[0].path").value("/inspections"))
                .andExpect(jsonPath("$[0].nav[0].icon").value("clipboard"))
                .andExpect(jsonPath("$[0].nav[0].requiredPermissions[0]").value("inspections:view"))
                .andExpect(jsonPath("$[0].permissions[0]").value("inspections:view"))
                .andExpect(jsonPath("$[0].permissions[1]").value("inspections:manage"))
                .andExpect(jsonPath("$[1].id").value("killed"))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[1].entitled").value(true))
                .andExpect(jsonPath("$[2].id").value("unpaid"))
                .andExpect(jsonPath("$[2].enabled").value(true))
                .andExpect(jsonPath("$[2].entitled").value(false));
    }

    @Test
    void enabled_isForbidden_forANonMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);

        mockMvc.perform(get("/api/v1/modules/web/enabled").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void enabled_listsOnlyTheModulesOnForTheCallersOrganization_forAMember() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);

        mockMvc.perform(get("/api/v1/modules/web/enabled").with(memberOfOrgSeven()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("inspections"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].entitled").value(true))
                .andExpect(jsonPath("$[0].nav.length()").value(1))
                .andExpect(jsonPath("$[0].permissions.length()").value(2));
    }

    @Test
    void enabled_isEmpty_forAMemberOfAnOrganizationThatIsNotEntitled() throws Exception {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);

        mockMvc.perform(get("/api/v1/modules/web/enabled")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor memberOfOrgSeven() {
        return jwt().authorities(new SimpleGrantedAuthority("ORG_MEMBER_7"));
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

    /** Three modules: one live, one killed by config, one the organization does not hold. */
    @TestConfiguration
    static class FixtureModules {

        @Bean
        ModuleRegistry moduleRegistry(Environment environment) {
            ModuleEntitlementResolver orgSevenHoldsInspections =
                    (org, key) -> org == 7L && !"MODULE_UNPAID".equals(key);
            return new ModuleRegistry(List.of(inspections(), killed(), unpaid()), orgSevenHoldsInspections, environment);
        }

        private static EchnoModule inspections() {
            return () -> new ModuleManifest("inspections", "Site Inspections", "1.0.0", "MODULE_INSPECTIONS",
                    List.of(), List.of("inspections:view", "inspections:manage"),
                    List.of(new NavDescriptor("Inspections", "site", "/inspections", "clipboard",
                            List.of("inspections:view"))),
                    false);
        }

        private static EchnoModule killed() {
            return () -> new ModuleManifest("killed", "Killed", "1.0.0", "MODULE_KILLED",
                    List.of(), List.of(), List.of(), false);
        }

        private static EchnoModule unpaid() {
            return () -> new ModuleManifest("unpaid", "Unpaid", "1.0.0", "MODULE_UNPAID",
                    List.of(), List.of(), List.of(), false);
        }
    }
}
