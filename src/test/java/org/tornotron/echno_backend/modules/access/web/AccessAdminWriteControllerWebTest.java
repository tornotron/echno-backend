package org.tornotron.echno_backend.modules.access.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleCreatedDto;

/**
 * Every Roles &amp; Permissions write needs {@code access:edit} before the writer is reached, and
 * the writer's refusals come back as their statuses. {@code @access} is the real guard over a
 * mocked subject and service.
 */
@WebMvcTest(AccessAdminWriteControllerWeb.class)
@Import({AccessAdminWriteControllerWebTest.TestSecurityConfig.class, AccessGuard.class})
class AccessAdminWriteControllerWebTest {

    private static final AccessSubject ADMIN = new AccessSubject(7L, 1L, 11L);
    private static final AccessPermission EDIT = AccessPermission.of("access:edit");
    private static final UUID ROLE = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentAccessSubject current;

    @MockitoBean
    private AccessService access;

    @MockitoBean
    private AccessAdminWriter writer;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    private void caller(boolean mayEdit) {
        when(current.resolve()).thenReturn(Optional.of(ADMIN));
        when(access.check(eq(ADMIN), eq(EDIT))).thenReturn(mayEdit);
    }

    private static List<MockHttpServletRequestBuilder> everyWrite() {
        String base = "/api/v1/access/web";
        return List.of(
                json(post(base + "/roles"), "{\"name\": \"Auditor\"}"),
                json(put(base + "/roles/" + ROLE), "{\"name\": \"Auditor\"}"),
                json(put(base + "/roles/" + ROLE + "/grants"), "{\"grants\": []}"),
                json(put(base + "/roles/" + ROLE + "/grantable-roles"), "{\"roleIds\": []}"),
                delete(base + "/roles/" + ROLE),
                json(put(base + "/users/5/roles"), "{\"roles\": []}"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void everyWriteIsRefusedWithoutAccessEdit() throws Exception {
        caller(false);

        for (MockHttpServletRequestBuilder request : everyWrite()) {
            mockMvc.perform(request.with(jwt())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(writer);
    }

    @Test
    void aRoleIsCreated() throws Exception {
        caller(true);
        when(writer.createRole("Auditor", null, null)).thenReturn(new AccessRoleCreatedDto(ROLE, "auditor"));

        mockMvc.perform(json(post("/api/v1/access/web/roles"), "{\"name\": \"Auditor\"}").with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROLE.toString()))
                .andExpect(jsonPath("$.slug").value("auditor"));
    }

    @Test
    void aBlankNameOrMissingListIsRefusedBeforeTheWriter() throws Exception {
        caller(true);

        mockMvc.perform(json(post("/api/v1/access/web/roles"), "{\"name\": \" \"}").with(jwt()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/v1/access/web/roles/" + ROLE + "/grants"), "{}").with(jwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(writer);
    }

    @Test
    void grantsReachTheWriterAndAnswerWithNoBody() throws Exception {
        caller(true);

        mockMvc.perform(json(put("/api/v1/access/web/roles/" + ROLE + "/grants"),
                        "{\"grants\": [{\"permission\": \"finance:read\", \"scope\": \"ALL\"}]}").with(jwt()))
                .andExpect(status().isNoContent());
        verify(writer).replaceRoleGrants(ROLE, List.of(new AccessGrantRequest("finance:read", "ALL")));
    }

    @Test
    void theWritersRefusalsComeBackAsTheirStatuses() throws Exception {
        caller(true);
        doThrow(new UnprocessableRequestException("fixed")).when(writer).deleteRole(ROLE);
        doThrow(new AccessDeniedException("own roles")).when(writer).replaceUserRoles(eq(5L), any());

        mockMvc.perform(delete("/api/v1/access/web/roles/" + ROLE).with(jwt()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/api/v1/access/web/users/5/roles"), "{\"roles\": []}").with(jwt()))
                .andExpect(status().isForbidden());
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
