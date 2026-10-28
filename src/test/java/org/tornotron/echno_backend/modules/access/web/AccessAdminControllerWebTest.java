package org.tornotron.echno_backend.modules.access.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;

/**
 * The Roles &amp; Permissions endpoints: who may read them, and the shape the screen depends on.
 *
 * <p>{@code @access} is mocked, so each test states plainly which permission the caller holds.
 */
@WebMvcTest(AccessAdminControllerWeb.class)
@Import(AccessAdminControllerWebTest.TestSecurityConfig.class)
class AccessAdminControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "access")
    private AccessGuard access;

    @MockitoBean
    private AccessAdminReader reader;

    @MockitoBean
    private AccessAdminWriter writer;

    @MockitoBean
    private CurrentAccessSubject current;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void theCatalogListsSectionsAreasActionsAndScopes() throws Exception {
        when(access.can("access", "view")).thenReturn(true);

        mockMvc.perform(get("/api/v1/access/web/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(12))
                .andExpect(jsonPath("$.sections[0]").value("Projects"))
                .andExpect(jsonPath("$.areas[?(@.id == 'progress-bills')].section").value("Bills"))
                .andExpect(jsonPath("$.areas[?(@.id == 'progress-bills')].actions[*]")
                        .value(hasItems("view", "approve", "certify")))
                .andExpect(jsonPath("$.areas[?(@.id == 'wbs')].label").value("WBS & Schedule"))
                .andExpect(jsonPath("$.scopes[*]")
                        .value(contains("ALL", "ASSIGNED_PROJECTS", "RELATED", "OWN")));
    }

    @Test
    void theCatalogIsRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/catalog").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rolesAreListedForTheRequestsOrganization() throws Exception {
        when(access.can("access", "view")).thenReturn(true);
        TenantContext.setCurrentOrgId(7L);
        UUID member = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        when(reader.roles(7L)).thenReturn(List.of(new AccessRoleDto(member, "member", "Member", null,
                "Everyone", true, true, List.of(new AccessGrantDto("leave:create", "OWN", true)),
                List.of(), List.of())));

        mockMvc.perform(get("/api/v1/access/web/roles").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("member"))
                .andExpect(jsonPath("$[0].baseline").value(true))
                .andExpect(jsonPath("$[0].grants[0].permission").value("leave:create"))
                .andExpect(jsonPath("$[0].grants[0].scope").value("OWN"))
                .andExpect(jsonPath("$[0].grants[0].locked").value(true));
    }

    @Test
    void rolesAreRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/roles").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupsAreListedForTheRequestsOrganization() throws Exception {
        when(access.can("access", "view")).thenReturn(true);
        TenantContext.setCurrentOrgId(7L);
        UUID finance = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        when(reader.groups(7L)).thenReturn(List.of(new AccessGroupDto(finance, "DEPARTMENT", "Finance", null,
                List.of(3L), List.of(), List.of(new AccessGrantDto("finance:view", "ALL", false)))));

        mockMvc.perform(get("/api/v1/access/web/groups").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("DEPARTMENT"))
                .andExpect(jsonPath("$[0].memberIds[0]").value(3))
                .andExpect(jsonPath("$[0].grants[0].permission").value("finance:view"));
    }

    @Test
    void groupsAreRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/groups").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void peopleAreListedForTheRequestsOrganization() throws Exception {
        when(access.can("access", "view")).thenReturn(true);
        TenantContext.setCurrentOrgId(7L);
        when(reader.users(7L)).thenReturn(List.of(new AccessUserDto(3L, "Anand R", "anand@example.test",
                "Site Engineer", true, List.of(), List.of(), List.of(new AccessGrantDto("tasks:view", "RELATED", false)))));

        mockMvc.perform(get("/api/v1/access/web/users").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeId").value(3))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].grants[0].scope").value("RELATED"));
    }

    @Test
    void peopleAreRefusedWithoutAccessView() throws Exception {
        when(access.can("access", "view")).thenReturn(false);

        mockMvc.perform(get("/api/v1/access/web/users").with(jwt()))
                .andExpect(status().isForbidden());
    }

    private static final AccessSubject CALLER = new AccessSubject(7L, 3L, 30L);
    private static final UUID AUDITOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    void aRoleIsCreatedForTheCaller() throws Exception {
        when(access.can("access", "create")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));
        when(writer.createRole(CALLER, "Auditor", null, "Reads the books")).thenReturn(AUDITOR);
        when(reader.role(7L, AUDITOR)).thenReturn(role(AUDITOR, "auditor"));

        mockMvc.perform(post("/api/v1/access/web/roles").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Auditor\",\"description\":\"Reads the books\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(AUDITOR.toString()))
                .andExpect(jsonPath("$.slug").value("auditor"));
    }

    @Test
    void aRoleNeedsAName() throws Exception {
        when(access.can("access", "create")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));

        mockMvc.perform(post("/api/v1/access/web/roles").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(writer);
    }

    @Test
    void creatingARoleIsRefusedWithoutAccessCreate() throws Exception {
        when(access.can("access", "create")).thenReturn(false);

        mockMvc.perform(post("/api/v1/access/web/roles").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Auditor\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(writer);
    }

    @Test
    void aRolesGrantsAreReplacedWithViewAdded() throws Exception {
        when(access.can("access", "edit")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));
        when(reader.role(7L, AUDITOR)).thenReturn(role(AUDITOR, "auditor"));

        mockMvc.perform(put("/api/v1/access/web/roles/{id}/grants", AUDITOR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"permission\":\"finance:approve\",\"scope\":\"ALL\"}]}"))
                .andExpect(status().isOk());
        verify(writer).replaceRoleGrants(CALLER, AUDITOR, Set.of(
                new Grant(AccessPermission.of("finance:view"), AccessScope.ALL),
                new Grant(AccessPermission.of("finance:approve"), AccessScope.ALL)));
    }

    @Test
    void aGrantOutsideTheCatalogIsABadRequest() throws Exception {
        when(access.can("access", "edit")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));

        mockMvc.perform(put("/api/v1/access/web/roles/{id}/grants", AUDITOR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"permission\":\"payroll:view\",\"scope\":\"ALL\"}]}"))
                .andExpect(status().isBadRequest());
        verify(writer, never()).replaceRoleGrants(any(), any(), any());
    }

    @Test
    void changingARoleIsRefusedWithoutAccessEdit() throws Exception {
        when(access.can("access", "edit")).thenReturn(false);

        mockMvc.perform(put("/api/v1/access/web/roles/{id}", AUDITOR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Auditor\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/access/web/roles/{id}/grantable-roles", AUDITOR).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(writer);
    }

    @Test
    void aRoleIsDeletedWithAccessDeleteOnly() throws Exception {
        when(current.resolve()).thenReturn(Optional.of(CALLER));
        when(access.can("access", "delete")).thenReturn(false);
        mockMvc.perform(delete("/api/v1/access/web/roles/{id}", AUDITOR).with(jwt()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(writer);

        when(access.can("access", "delete")).thenReturn(true);
        mockMvc.perform(delete("/api/v1/access/web/roles/{id}", AUDITOR).with(jwt()))
                .andExpect(status().isNoContent());
        verify(writer).deleteRole(CALLER, AUDITOR);
    }

    private static final UUID FINANCE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    void aGroupIsCreatedForTheCaller() throws Exception {
        when(access.can("access", "create")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));
        when(writer.createGroup(CALLER, "DEPARTMENT", "Finance", null)).thenReturn(FINANCE);
        when(reader.group(7L, FINANCE)).thenReturn(new AccessGroupDto(FINANCE, "DEPARTMENT", "Finance", null,
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/v1/access/web/groups").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DEPARTMENT\",\"name\":\"Finance\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FINANCE.toString()))
                .andExpect(jsonPath("$.kind").value("DEPARTMENT"));
    }

    @Test
    void aGroupsMembersRolesAndGrantsAreReplaced() throws Exception {
        when(access.can("access", "edit")).thenReturn(true);
        when(current.resolve()).thenReturn(Optional.of(CALLER));
        when(reader.group(7L, FINANCE)).thenReturn(new AccessGroupDto(FINANCE, "GROUP", "Finance", null,
                List.of(), List.of(), List.of()));

        mockMvc.perform(put("/api/v1/access/web/groups/{id}/members", FINANCE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeIds\":[3,4]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/access/web/groups/{id}/roles", FINANCE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[\"" + AUDITOR + "\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/access/web/groups/{id}/grants", FINANCE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"permission\":\"finance:view\",\"scope\":\"ALL\"}]}"))
                .andExpect(status().isOk());

        verify(writer).replaceGroupMembers(CALLER, FINANCE, List.of(3L, 4L));
        verify(writer).replaceGroupRoles(CALLER, FINANCE, List.of(AUDITOR));
        verify(writer).replaceGroupGrants(CALLER, FINANCE,
                Set.of(new Grant(AccessPermission.of("finance:view"), AccessScope.ALL)));
    }

    @Test
    void groupWritesAreRefusedWithoutTheirAction() throws Exception {
        when(access.can(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/access/web/groups").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Finance\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/access/web/groups/{id}/members", FINANCE).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeIds\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/access/web/groups/{id}", FINANCE).with(jwt()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(writer);
    }

    private static AccessRoleDto role(UUID id, String slug) {
        return new AccessRoleDto(id, slug, "Auditor", null, null, false, false, List.of(), List.of(), List.of());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
