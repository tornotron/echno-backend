package org.tornotron.echno_backend.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four guards on the mobile {@code IssueController}, which named an authority the realm
 * cannot issue, and what each answers now.
 *
 * <p>{@code issue:read}, {@code issue:create}, {@code issue:update} and {@code issue:delete} are
 * bare {@code resource:scope} authorities. {@code JwtAuthConverter} mints those in one place,
 * {@code extractPermissions}, which reads the {@code authorization} claim of an RPT, and the realm
 * defines no authorization scopes, so a permission with no scopes yields no
 * {@code resource:scope} authority at all. Every endpoint on this controller therefore refused
 * every caller from the day the annotation was written. Same mechanism as #641, #684, #710 and
 * #716; see #734.
 *
 * <p>Raising a defect from the site, with a photograph, on the phone that is already in the
 * hand is the workflow this controller exists for, so it is repaired rather than removed. The
 * target is the web twin's answer, endpoint for endpoint: reading and raising an issue is open to
 * any member of the tenant, and editing or deleting one belongs to a system admin or project
 * manager. Nobody's effective reach changes, because the same member already does all four
 * things through {@code /api/v1/issues/web}.
 *
 * <p>The role list is stubbed exactly rather than through {@code any(String[].class)}. A blanket
 * stub answering true would pass whatever roles the guards named, so a delete that quietly
 * admitted every member would look identical from here. The broad stub is kept, answering false,
 * so an endpoint asking for a list no persona grants is refused rather than falling through to a
 * Mockito default that happens to agree.
 */
@WebMvcTest(IssueController.class)
@Import(IssueMobileGuardTest.TestSecurityConfig.class)
class IssueMobileGuardTest {

    private static final long ISSUE = 42L;

    /** The pair the web twin's update and delete name. */
    private static final String[] WRITE_ROLES = {"system-admin", "project-manager"};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueService issueService;

    @MockitoBean
    private JsonPartBinder jsonPartBinder;

    // Named to match the @orgSecurity bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here because
    // .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    /** No membership of the tenant and no role in it. */
    private void callerIsAnOutsider() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
    }

    /** An ordinary member of the tenant, holding no org role. */
    private void callerIsAPlainMember() {
        callerIsAnOutsider();
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
    }

    /** A project manager: a member who also satisfies the write pair. */
    private void callerIsAProjectManager() {
        callerIsAPlainMember();
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(WRITE_ROLES)).thenReturn(true);
    }

    private static MockMultipartFile dataPart() {
        return new MockMultipartFile(
                "data", "data", MediaType.APPLICATION_JSON_VALUE, "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void read_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(get("/api/v1/issues/{id}", ISSUE).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void read_asSomebodyOutsideTheTenant_isForbidden() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/issues/{id}", ISSUE).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueService);
    }

    @Test
    void create_asAMemberOfTheTenant_isAllowed() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(multipart("/api/v1/issues").file(dataPart()).with(jwt()))
                .andExpect(status().isCreated());
    }

    @Test
    void create_asSomebodyOutsideTheTenant_isForbidden() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(multipart("/api/v1/issues").file(dataPart()).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueService);
    }

    @Test
    void update_asAProjectManager_isAllowed() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/issues/{id}", ISSUE)
                        .file(dataPart())
                        .with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The difference between the two guards on this controller. A member raises and reads an
     * issue; changing somebody else's, or removing it, is the pair's.
     */
    @Test
    void update_asAPlainMember_isForbidden() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/issues/{id}", ISSUE)
                        .file(dataPart())
                        .with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueService);
    }

    @Test
    void delete_asAProjectManager_isAllowed() throws Exception {
        callerIsAProjectManager();

        mockMvc.perform(delete("/api/v1/issues/{id}", ISSUE).with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void delete_asAPlainMember_isForbidden() throws Exception {
        callerIsAPlainMember();

        mockMvc.perform(delete("/api/v1/issues/{id}", ISSUE).with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueService);
    }

    /**
     * The repaired guards do not keep the old authority as an alternative branch. Holding it
     * would otherwise look like a working credential in a test and be unobtainable in production,
     * which is how these guards went unnoticed for as long as they did.
     */
    @Test
    void theOldAuthorityAloneOpensNothing() throws Exception {
        callerIsAnOutsider();

        mockMvc.perform(get("/api/v1/issues/{id}", ISSUE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("issue:admin"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/issues/{id}", ISSUE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("issue:delete"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(issueService);
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
