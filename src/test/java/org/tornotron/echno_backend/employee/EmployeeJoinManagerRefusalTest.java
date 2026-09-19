package org.tornotron.echno_backend.employee;

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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Both employee create routes, the mobile {@code /joinOrganization/{userId}/{orgId}} and the web
 * {@code /web/joinOrganization/userId/{userId}/organizationId/{orgId}}, surface the missing
 * reporting manager as a 400 with the reason in the body, so a client can show it as a field
 * error. The rule itself is proven against the database in {@link EmployeeJoinManagerRequiredIT};
 * here the service is stubbed to refuse exactly a body without {@code managerId}, which pins
 * that neither controller swallows or re-maps the refusal, and that a body carrying one is
 * passed through untouched.
 */
@WebMvcTest({EmployeeController.class, EmployeeControllerWeb.class})
@Import(EmployeeJoinManagerRefusalTest.TestSecurityConfig.class)
class EmployeeJoinManagerRefusalTest {

    private static final long ORG = 100L;
    private static final long USER_ID = 7L;
    private static final String MOBILE_ROUTE = "/api/v1/employee/joinOrganization/" + USER_ID + "/" + ORG;
    private static final String WEB_ROUTE = "/api/v1/employee/web/joinOrganization/userId/" + USER_ID + "/organizationId/" + ORG;

    private static final String BODY_WITHOUT_MANAGER = """
            {"designation":"Site Engineer","department":"Civil","status":"active"}
            """;
    private static final String BODY_WITH_MANAGER = """
            {"designation":"Site Engineer","department":"Civil","status":"active","managerId":5}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeService employeeService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void callerIsAnHrAdminOfTheOrganization() {
        when(orgSecurity.isCurrentTenant(ORG)).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(employeeService.joinOrganization(eq(USER_ID), eq(ORG),
                argThat((EmployeeJoinOrgDto dto) -> dto != null && dto.getManagerId() == null)))
                .thenThrow(new InvalidRequestException("managerId is required: a new employee must have a reporting manager"));
    }

    @Test
    void mobileJoin_withoutManager_isABadRequestNamingTheField() throws Exception {
        mockMvc.perform(post(MOBILE_ROUTE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_WITHOUT_MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("managerId is required")));
    }

    @Test
    void webJoin_withoutManager_isABadRequestNamingTheField() throws Exception {
        mockMvc.perform(post(WEB_ROUTE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_WITHOUT_MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("managerId is required")));
    }

    @Test
    void mobileJoin_withManager_isCreated() throws Exception {
        mockMvc.perform(post(MOBILE_ROUTE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_WITH_MANAGER))
                .andExpect(status().isCreated());
    }

    @Test
    void webJoin_withManager_isCreated() throws Exception {
        mockMvc.perform(post(WEB_ROUTE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_WITH_MANAGER))
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
