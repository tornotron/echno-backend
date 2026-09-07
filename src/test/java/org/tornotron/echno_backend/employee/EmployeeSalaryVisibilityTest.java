package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a colleague's employee record says about their pay, and to whom.
 *
 * <p>{@code EmployeeDto} carries salary, date of birth, address and phone number, and the
 * directory reads are open to {@code project-manager}, so every project manager read all four of
 * a colleague. Three of the four earn their place on a construction site: somebody has to reach a
 * person, arrange transport, or know that a job has an age floor. Pay does not, and the roles that
 * may set it here are {@code system-admin} and {@code hr-admin} - the PATCH beside these reads has
 * always been gated on those two. So pay is now shown to exactly the roles that may change it,
 * plus the person whose pay it is.
 *
 * <p>The decision is enforced on the type rather than on the endpoint, so these tests are about a
 * response body and not about a 403. Every read below is admitted; what varies is whether the
 * body carries {@code salary}.
 *
 * <p>The mocked {@code @orgSecurity} bean only opens the door. The visibility decision reads the
 * caller's real authorities and the real {@code TenantContext} that {@code TenantFilter} sets from
 * the JWT, so what is asserted here is the mechanism and not the stub.
 */
@WebMvcTest(EmployeeControllerWeb.class)
@Import(EmployeeSalaryVisibilityTest.TestSecurityConfig.class)
class EmployeeSalaryVisibilityTest {

    private static final long ORG = 100L;
    private static final long COLLEAGUE = 42L;
    private static final double PAY = 65000.0;

    /** The Keycloak subject on the JWT every request below is made with. */
    private static final String CALLER = "kc-caller";

    /** The Keycloak subject on the record being read, so the record is somebody else's. */
    private static final String SOMEONE_ELSE = "kc-colleague";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeService employeeService;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void admitEveryRead() {
        // TenantFilter infers this from the membership authority on the JWT when the slice loads
        // it. Set here as well so the visibility decision has a tenant to read either way; the
        // filter's own finally clears it after every request that reaches the filter.
        TenantContext.setCurrentOrgId(ORG);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), any(String[].class))).thenReturn(true);
        when(employeeService.displayAnEmployee(COLLEAGUE)).thenReturn(recordOf(SOMEONE_ELSE));
        when(employeeService.displayAllEmployees(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(page(recordOf(SOMEONE_ELSE)));
    }

    /**
     * TenantContext is a ThreadLocal and MockMvc runs on the test thread. TenantFilter clears it
     * in a finally, but a test that never reaches the filter would otherwise leave a scope behind
     * for the next one.
     */
    @AfterEach
    void clearTenantScope() {
        TenantContext.clear();
    }

    @Test
    void salaryIsWithheldFromAProjectManagerReadingTheDirectory() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web").with(projectManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].salary").doesNotHaveJsonPath());
    }

    @Test
    void salaryIsWithheldFromAProjectManagerReadingOneColleague() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(projectManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salary").doesNotHaveJsonPath());
    }

    @Test
    void salaryIsShownToASystemAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salary").value(PAY));
    }

    /** hr-admin may set the salary through the PATCH beside this read, so it may read it back. */
    @Test
    void salaryIsShownToAnHrAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(hrAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salary").value(PAY));
    }

    /**
     * A person reads their own pay whatever role they hold. Without this the self branch on this
     * read, added in #710 so somebody could read back the record they may edit, would hand them a
     * record with their own pay cut out of it.
     */
    @Test
    void salaryIsShownOnTheCallersOwnRecord() throws Exception {
        when(employeeService.displayAnEmployee(COLLEAGUE)).thenReturn(recordOf(CALLER));

        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(plainMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salary").value(PAY));
    }

    // The three fields that stay. Asserted one per test: chaining them would report a failure by
    // describing the whole response, and what is being pinned is a separate decision per field.

    @Test
    void aProjectManagerStillReadsAColleaguesPhoneNumber() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(projectManager()))
                .andExpect(jsonPath("$.phoneNumber").value("9847012345"));
    }

    @Test
    void aProjectManagerStillReadsAColleaguesAddress() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(projectManager()))
                .andExpect(jsonPath("$.address").value("45 Anna Nagar, Chennai"));
    }

    @Test
    void aProjectManagerStillReadsAColleaguesDateOfBirth() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(projectManager()))
                .andExpect(jsonPath("$.dateOfBirth").exists());
    }

    /** The internal identifier the visibility decision reads must not reach a client. */
    @Test
    void theSubjectTheDecisionReadsIsNotPublished() throws Exception {
        mockMvc.perform(get("/api/v1/employee/web/" + COLLEAGUE).with(systemAdmin()))
                .andExpect(jsonPath("$.userKeycloakId").doesNotHaveJsonPath());
    }

    private static EmployeeDto recordOf(String subject) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(COLLEAGUE);
        dto.setEmployeeName("Ravi Kumar");
        dto.setSalary(PAY);
        dto.setPhoneNumber("9847012345");
        dto.setAddress("45 Anna Nagar, Chennai");
        dto.setDateOfBirth(LocalDateTime.of(1994, 3, 22, 0, 0));
        dto.setUserKeycloakId(subject);
        return dto;
    }

    private static Page<EmployeeDto> page(EmployeeDto dto) {
        return new PageImpl<>(List.of(dto));
    }

    private static RequestPostProcessor projectManager() {
        return caller("ORG_MEMBER_" + ORG, "ORG_" + ORG + "_ROLE_project-manager");
    }

    private static RequestPostProcessor systemAdmin() {
        return caller("ORG_MEMBER_" + ORG, "ORG_" + ORG + "_ROLE_system-admin");
    }

    private static RequestPostProcessor hrAdmin() {
        return caller("ORG_MEMBER_" + ORG, "ORG_" + ORG + "_ROLE_hr-admin");
    }

    private static RequestPostProcessor plainMember() {
        return caller("ORG_MEMBER_" + ORG);
    }

    /**
     * A caller carrying the membership authority TenantFilter infers the tenant from, so the
     * request runs with a real organization scope rather than none.
     */
    private static RequestPostProcessor caller(String... authorities) {
        return jwt()
                .jwt(builder -> builder.subject(CALLER))
                .authorities(Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
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
