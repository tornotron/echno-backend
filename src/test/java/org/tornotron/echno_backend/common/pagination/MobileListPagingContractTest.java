package org.tornotron.echno_backend.common.pagination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tornotron.echno_backend.attendance.AttendanceController;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.common.configuration.KeycloakAuthorizationService;
import org.tornotron.echno_backend.common.configuration.RPTCache;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.leave.LeaveRequestController;
import org.tornotron.echno_backend.leave.LeaveRequestService;
import org.tornotron.echno_backend.leave.NotificationController;
import org.tornotron.echno_backend.leave.NotificationService;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.dto.NotificationDto;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemController;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemService;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The paged shape the mobile lists answer with, and the bounds on what a caller may ask for (#999).
 *
 * <p>One list from each family the issue touched: leave, notifications, attendance and
 * procurement. Each takes {@code pageNo} and {@code pageSize}, hands them to the service as asked,
 * refuses a page size outside {@link PageQuery}'s bounds before the service is reached, and answers
 * with the page itself rather than its content, so the client reads {@code totalElements},
 * {@code totalPages}, {@code number} and {@code last} instead of guessing the end from a short
 * array. The guards are answered true here; what they admit is pinned by the authorization tests.
 */
@WebMvcTest(controllers = {
        LeaveRequestController.class,
        NotificationController.class,
        AttendanceController.class,
        PurchaseOrderItemController.class})
@Import(MobileListPagingContractTest.TestSecurityConfig.class)
class MobileListPagingContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeaveRequestService leaveRequestService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private AttendanceService attendanceService;

    @MockitoBean
    private JsonPartBinder jsonPartBinder;

    @MockitoBean
    private PurchaseOrderItemService purchaseOrderItemService;

    @MockitoBean(name = "orgSecurity")
    private OrganizationSecurityService orgSecurity;

    @MockitoBean(name = "attendanceSecurity")
    private AttendanceSecurityService attendanceSecurity;

    // Named to match the @access bean the purchase order item guards reference.
    @MockitoBean(name = "access")
    private org.tornotron.echno_backend.modules.access.guard.AccessGuard access;

    // Named to match the @leaveAccess bean the leave guards reference.
    @MockitoBean(name = "leaveAccess")
    private org.tornotron.echno_backend.leave.LeaveAccess leaveAccess;

    // Named to match the @teamAccess bean the attendance guards reference.
    @MockitoBean(name = "teamAccess")
    private org.tornotron.echno_backend.employee.TeamAccess teamAccess;

    @MockitoBean
    private KeycloakAuthorizationService keycloakAuthorizationService;

    @MockitoBean
    private RPTCache rptCache;

    @BeforeEach
    void everyGuardAdmits() {
        when(orgSecurity.isMemberOfCurrentTenant()).thenReturn(true);
        when(access.can(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        when(orgSecurity.isCurrentTenant(anyLong())).thenReturn(true);
        when(orgSecurity.isSelfInCurrentTenant(anyLong())).thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(attendanceSecurity.canManageRecords()).thenReturn(true);
        when(access.isMember()).thenReturn(true);
        when(leaveAccess.acrossOrganization(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(leaveAccess.allowsFor(org.mockito.ArgumentMatchers.anyString(), anyLong())).thenReturn(true);
        when(teamAccess.acrossOrganization(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(teamAccess.allowsFor(org.mockito.ArgumentMatchers.anyString(), anyLong())).thenReturn(true);
    }

    /** Five rows in all, asked for two at a time, and this is the middle page. */
    private static <T> Page<T> middleOfFive(T a, T b) {
        return new PageImpl<>(List.of(a, b), PageRequest.of(1, 2), 5);
    }

    @Test
    void aListAnswersWithThePageAndItsTotals() throws Exception {
        when(leaveRequestService.getPendingApprovals(1, 2))
                .thenReturn(middleOfFive(new LeaveRequestDto(), new LeaveRequestDto()));

        mockMvc.perform(get("/api/v1/leave-requests/pending-approvals")
                        .param("pageNo", "1").param("pageSize", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void theLastPageSaysItIsTheLast() throws Exception {
        when(leaveRequestService.getRequestsByOrganization(2, 2))
                .thenReturn(new PageImpl<>(List.of(new LeaveRequestDto()), PageRequest.of(2, 2), 5));

        mockMvc.perform(get("/api/v1/leave-requests/organizationId/100")
                        .param("pageNo", "2").param("pageSize", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void aPageSizeAboveTheCapIsRefusedBeforeTheServiceIsAsked() throws Exception {
        mockMvc.perform(get("/api/v1/leave-requests/pending-approvals")
                        .param("pageSize", String.valueOf(UnpagedResultCap.MAX_ROWS + 1))
                        .with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(leaveRequestService);
    }

    @Test
    void aPageSizeBelowOneAndANegativePageAreRefused() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread").param("pageSize", "0").with(jwt()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications/unread").param("pageNo", "-1").with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(notificationService);
    }

    @Test
    void theInboxTakesPageNoAndPageSizeAndKeepsItsDefaultOfTwenty() throws Exception {
        when(notificationService.getMyNotifications(anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/notifications").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        verify(notificationService).getMyNotifications(0, 20);

        mockMvc.perform(get("/api/v1/notifications").param("pageNo", "3").param("pageSize", "5").with(jwt()))
                .andExpect(status().isOk());
        verify(notificationService).getMyNotifications(3, 5);
    }

    @Test
    void theUnreadInboxIsPagedToo() throws Exception {
        when(notificationService.getMyUnreadNotifications(0, 10))
                .thenReturn(new PageImpl<>(List.of(new NotificationDto()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/notifications/unread").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void theProjectRollTakesThePageNamesEveryOtherListUses() throws Exception {
        when(attendanceService.getAttendancePageByProject(
                anyLong(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(middleOfFive(new AttendanceResponseDto(), new AttendanceResponseDto()));

        mockMvc.perform(get("/api/v1/attendance/project/8")
                        .param("date", "2026-11-02")
                        .param("pageNo", "1").param("pageSize", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.number").value(1));

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(attendanceService).getAttendancePageByProject(
                eq(8L), any(), isNull(), isNull(), isNull(), isNull(), asked.capture());
        assertThat(asked.getValue().getPageNumber()).isEqualTo(1);
        assertThat(asked.getValue().getPageSize()).isEqualTo(2);
        assertThat(asked.getValue().getSort())
                .as("by name, with the id breaking ties between two people of the same name")
                .isEqualTo(Sort.by("employeeName").and(Sort.by("id")));
    }

    @Test
    void theProjectRollKeepsItsDefaultOfTwenty() throws Exception {
        when(attendanceService.getAttendancePageByProject(
                anyLong(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/attendance/project/8").param("date", "2026-11-02").with(jwt()))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(attendanceService).getAttendancePageByProject(
                eq(8L), any(), any(), any(), any(), any(), asked.capture());
        assertThat(asked.getValue().getPageNumber()).isZero();
        assertThat(asked.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void theOrderItemListsCoreReadsKeepTheirArraysBesideAPagedTwin() throws Exception {
        when(purchaseOrderItemService.getItemsByPurchaseOrderId(4L))
                .thenReturn(List.of(new PurchaseOrderItemResponseDto()));
        when(purchaseOrderItemService.getItemsByPurchaseOrderId(4L, 1, 2))
                .thenReturn(middleOfFive(new PurchaseOrderItemResponseDto(), new PurchaseOrderItemResponseDto()));

        mockMvc.perform(get("/api/v1/purchase-order-items/purchase-order/4").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/purchase-order-items/purchase-order/4/paginated")
                        .param("pageNo", "1").param("pageSize", "2")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false));
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
