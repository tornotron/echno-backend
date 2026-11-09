package org.tornotron.echno_backend.documentReversal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalDto;
import org.tornotron.echno_backend.documentReversal.dto.DocumentReversalRequestDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guards on the web twin: reads and raising a request open to any tenant member, the two
 * decisions to the roles that approve stock adjustments. The mobile twin carries the same
 * strings; {@code EndpointAuthorizationTest} keeps both annotated.
 */
@WebMvcTest(DocumentReversalControllerWeb.class)
@Import(DocumentReversalControllerWebAuthzTest.TestSecurityConfig.class)
class DocumentReversalControllerWebAuthzTest {

    private static final String BASE = "/api/v1/document-reversals/web";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentReversalService reversalService;

    // Named to match the @access bean the @PreAuthorize SpEL references.
    @MockitoBean(name = "access")
    private AccessGuard access;

    /** The baseline's reversal actions: every member reads, asks for and withdraws a reversal. */
    private static final String[] MEMBER = {"document-reversals:read", "document-reversals:create", "document-reversals:edit"};
    /** The system admin's and project manager's: the baseline plus deciding a reversal. */
    private static final String[] PAIR = {"document-reversals:read", "document-reversals:create", "document-reversals:edit",
            "document-reversals:approve"};

    /** Stubs {@code @access} to allow exactly these area:action keys; every other check answers false. */
    private void holds(String... permissions) {
        for (String permission : permissions) {
            String[] parts = permission.split(":");
            when(access.can(parts[0], parts[1])).thenReturn(true);
        }
    }

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @Test
    void list_isOk_forAnyMember() throws Exception {
        holds(MEMBER);
        when(reversalService.getAll(anyInt(), anyInt(), any())).thenReturn(Page.empty());

        mockMvc.perform(get(BASE).with(jwt())).andExpect(status().isOk());
    }

    @Test
    void list_isForbidden_forANonMember() throws Exception {
        holds();

        mockMvc.perform(get(BASE).with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void eligibility_refusesAnUnknownKindOfDocument() throws Exception {
        holds(MEMBER);

        mockMvc.perform(get(BASE + "/eligibility")
                        .param("documentType", "STOCK_ADJUSTMENT")
                        .param("documentId", "3")
                        .with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void request_isCreated_forAMember() throws Exception {
        holds(MEMBER);
        when(reversalService.request(any(DocumentReversalRequestDto.class))).thenReturn(new DocumentReversalDto());

        mockMvc.perform(post(BASE).with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"SITE_TRANSFER\",\"documentId\":31,\"reason\":\"Wrong store\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void request_withoutAReason_isBadRequest() throws Exception {
        holds(MEMBER);

        mockMvc.perform(post(BASE).with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"SITE_TRANSFER\",\"documentId\":31,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approve_isOk_forAnElevatedRoleHolder() throws Exception {
        holds(PAIR);
        when(reversalService.approve(anyLong())).thenReturn(new DocumentReversalDto());

        mockMvc.perform(post(BASE + "/1/approve").with(jwt()).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void approve_isForbidden_forAPlainMemberWithoutAnElevatedRole() throws Exception {
        holds(MEMBER);

        mockMvc.perform(post(BASE + "/1/approve").with(jwt()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void reject_isOk_forAnElevatedRoleHolder() throws Exception {
        holds(PAIR);
        when(reversalService.reject(anyLong(), anyString())).thenReturn(new DocumentReversalDto());

        mockMvc.perform(post(BASE + "/1/reject").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Stock already issued\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void reject_isForbidden_forAPlainMemberWithoutAnElevatedRole() throws Exception {
        holds(MEMBER);

        mockMvc.perform(post(BASE + "/1/reject").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Stock already issued\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancel_isOk_forAMember() throws Exception {
        holds(MEMBER);
        when(reversalService.cancel(anyLong())).thenReturn(new DocumentReversalDto());

        mockMvc.perform(post(BASE + "/1/cancel").with(jwt()).with(csrf())).andExpect(status().isOk());
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
