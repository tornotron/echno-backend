package org.tornotron.echno_backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.invitation.IdentityLinkService;
import org.tornotron.echno_backend.user.dto.UserDto;

/**
 * Both user twins answer a change to an account outside the caller's organization with 404 and a
 * change that would reach past it on a shared account with 409, and let the account's owner through
 * to edit themselves. What decides each answer is in {@code UserService} and is checked against a real
 * database by {@code UserCrossOrganizationChangeIT}; this pins the status each twin returns.
 */
@WebMvcTest({UserController.class, UserControllerWeb.class})
@Import(UserSharedAccountTwinsTest.TestSecurityConfig.class)
class UserSharedAccountTwinsTest {

    private static final long OUTSIDER = 41L;
    private static final long SHARED = 42L;
    private static final long COLLEAGUE = 43L;
    private static final long SELF = 44L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JsonPartBinder jsonPartBinder;

    @MockitoBean
    private UserContextService userContextService;

    @MockitoBean
    private IdentityLinkService identityLinkService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void systemAdminOfTheCurrentOrganization() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(orgSecurity.isSelfUser(anyLong())).thenReturn(false);
        when(jsonPartBinder.readUpdates(any())).thenReturn(Map.of("email", "someone@example.test"));
        when(userService.partialUpdateAnUser(anyMap(), eq(OUTSIDER)))
                .thenThrow(new ResourceNotFoundException("User with ID 41 was not found"));
        when(userService.partialUpdateAnUser(anyMap(), eq(OUTSIDER), any(), any()))
                .thenThrow(new ResourceNotFoundException("User with ID 41 was not found"));
        when(userService.partialUpdateAnUser(anyMap(), eq(SHARED)))
                .thenThrow(new SharedUserException("only they may change email"));
        when(userService.partialUpdateAnUser(anyMap(), eq(SHARED), any(), any()))
                .thenThrow(new SharedUserException("only they may change email"));
        when(userService.partialUpdateAnUser(anyMap(), eq(COLLEAGUE))).thenReturn(new UserDto());
        when(userService.partialUpdateAnUser(anyMap(), eq(COLLEAGUE), any(), any())).thenReturn(new UserDto());
        doThrow(new ResourceNotFoundException("User with ID 41 was not found")).when(userService).deleteAnUser(OUTSIDER);
        doThrow(new SharedUserException("also belongs to another organization")).when(userService).deleteAnUser(SHARED);
    }

    @Test
    void aSystemAdminChangesAndDeletesAColleagueOnBothTwins() throws Exception {
        for (String prefix : new String[] {"/api/v1/user", "/api/v1/user/web"}) {
            mockMvc.perform(update(prefix, COLLEAGUE)).andExpect(status().isOk());
            mockMvc.perform(delete(prefix + "/" + COLLEAGUE).with(jwt())).andExpect(status().isOk());
        }
    }

    @Test
    void aUserOfAnotherOrganizationIsNotFoundOnBothTwins() throws Exception {
        for (String prefix : new String[] {"/api/v1/user", "/api/v1/user/web"}) {
            mockMvc.perform(update(prefix, OUTSIDER)).andExpect(status().isNotFound());
            mockMvc.perform(delete(prefix + "/" + OUTSIDER).with(jwt())).andExpect(status().isNotFound());
        }
    }

    @Test
    void aSharedUsersSignInFieldsAndRowAreRefusedWithAConflictOnBothTwins() throws Exception {
        for (String prefix : new String[] {"/api/v1/user", "/api/v1/user/web"}) {
            mockMvc.perform(update(prefix, SHARED)).andExpect(status().isConflict());
            mockMvc.perform(delete(prefix + "/" + SHARED).with(jwt())).andExpect(status().isConflict());
        }
    }

    @Test
    void theOwnerEditsThemselvesOnBothTwinsWithNoRole() throws Exception {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
        when(orgSecurity.isSelfUser(SELF)).thenReturn(true);
        when(userService.partialUpdateAnUser(anyMap(), eq(SELF))).thenReturn(new UserDto());
        when(userService.partialUpdateAnUser(anyMap(), eq(SELF), any(), any())).thenReturn(new UserDto());

        for (String prefix : new String[] {"/api/v1/user", "/api/v1/user/web"}) {
            mockMvc.perform(update(prefix, SELF)).andExpect(status().isOk());
            mockMvc.perform(update(prefix, COLLEAGUE)).andExpect(status().isForbidden());
        }
    }

    /** The mobile twin takes a JSON body, the web twin a multipart one with the fields in its data part. */
    private static MockHttpServletRequestBuilder update(String prefix, long id) {
        if (prefix.endsWith("/web")) {
            return multipart(HttpMethod.PATCH, prefix + "/" + id)
                    .param("data", "{\"email\":\"someone@example.test\"}")
                    .with(jwt());
        }
        return patch(prefix + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"someone@example.test\"}")
                .with(jwt());
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
