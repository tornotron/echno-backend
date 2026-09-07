package org.tornotron.echno_backend.goodsReceivedNote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice authorization test for the goods receipt, which is the case the {@code store-keeper}
 * role was introduced for.
 *
 * <p>Before the role there was nothing between plain organization membership and {@code
 * system-admin} anywhere in Resources, so the only way to let the person on the store counter book a
 * delivery was to make them an administrator of the organization, able to delete projects and edit
 * anybody's employee record. Every method here answered 403 to a storekeeper.
 *
 * <p>The reads are tested alongside the write on purpose. Opening {@code POST /grns/web} on its own
 * would move the refusal rather than remove it: the browser lists the notes already recorded before
 * it offers to record another, and a storekeeper who may create one and may not see it has been
 * given a form and no register.
 *
 * <p>{@code @orgSecurity} is mocked so the branch under test is the only one answering true: the
 * storekeeper stubs deliberately answer false to every expression that does not name the role, which
 * is exactly a caller holding {@code store-keeper} and nothing else.
 */
@WebMvcTest(GoodsReceivedNoteControllerWeb.class)
@Import(GoodsReceivedNoteControllerWebAuthzTest.TestSecurityConfig.class)
class GoodsReceivedNoteControllerWebAuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoodsReceivedNoteService goodsReceivedNoteService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    // Satisfies RPTExchangeFilter, a custom filter the web slice loads; unused here
    // because .with(jwt(...)) sets the authentication directly.
    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    // RPTExchangeFilter also depends on this cache; mocked for the same reason.
    @MockitoBean
    private RPTCache rptCache;

    /** A caller holding store-keeper and no other org role. */
    private void asStoreKeeper() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper")).thenReturn(true);
    }

    /** A caller who is a member of the tenant and holds no org role at all. */
    private void asPlainMember() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "store-keeper")).thenReturn(false);
    }

    private void stubReads() {
        when(goodsReceivedNoteService.getGrnById(anyLong())).thenReturn(new GoodsReceivedNoteDto());
        when(goodsReceivedNoteService.getAllGrns(anyInt(), anyInt())).thenReturn(Page.empty());
        when(goodsReceivedNoteService.getGrnsByVendor(anyLong())).thenReturn(List.of());
        when(goodsReceivedNoteService.getGrnsByDateRange(any(), any())).thenReturn(List.of());
    }

    /** The register the browser reads before it offers to record another delivery. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/grns/web",
            "/api/v1/grns/web/1",
            "/api/v1/grns/web/all?pageNo=0&pageSize=20",
            "/api/v1/grns/web/vendor/1",
            "/api/v1/grns/web/date-range?startDate=2026-01-01T00:00:00&endDate=2026-12-31T00:00:00"
    })
    void aStoreKeeperMayReadGoodsReceivedNotes(String path) throws Exception {
        asStoreKeeper();
        stubReads();

        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isOk());
    }

    /**
     * The endpoint the issue was filed about. A 400 here would be the payload failing validation
     * after the guard let the request through, so the assertion is only that it is not a refusal.
     */
    @Test
    void aStoreKeeperMayRecordADelivery() throws Exception {
        asStoreKeeper();
        when(goodsReceivedNoteService.createGoodsReceivedNote(any()))
                .thenReturn(new GoodsReceivedNoteDto());

        mockMvc.perform(post("/api/v1/grns/web")
                        .with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "receivedOn": "2026-09-07T10:00:00",
                                  "receivedByEmployeeId": 1,
                                  "vendorId": 1,
                                  "purchaseOrderId": 1,
                                  "projectId": 1,
                                  "items": [
                                    {"materialId": 1, "orderedQuantity": 10, "receivedQuantity": 10, "unitCost": 100}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());
    }

    /** Correcting the header of a note already booked, for example a challan number typed wrong. */
    @Test
    void aStoreKeeperMayCorrectANoteTheyBooked() throws Exception {
        asStoreKeeper();
        when(goodsReceivedNoteService.updateGoodsReceivedNote(any()))
                .thenReturn(new GoodsReceivedNoteDto());

        mockMvc.perform(patch("/api/v1/grns/web")
                        .with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 1, \"invoiceNumber\": \"INV-1\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The other half of the tier. Goods receipts were never open to every member of the
     * organization and this change does not open them: the role is what grants them, so a member
     * without it is still refused.
     */
    @Test
    void aPlainMemberIsStillRefused() throws Exception {
        asPlainMember();

        mockMvc.perform(get("/api/v1/grns/web").with(jwt()))
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
