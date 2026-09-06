package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.NotificationDto;
import org.tornotron.echno_backend.leave.mapper.NotificationMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A notification is addressed to one person, and only that person reads or clears it.
 *
 * <p>{@code markAsRead} is the case that was not merely dead. It took a notification id, loaded
 * the row by that id within the tenant, and wrote {@code isRead} on whatever came back, checking
 * nothing about who was asking. On the phone twin that was unreachable behind a phantom authority,
 * but the web twin was guarded on the system-admin and hr-admin roles and worked, so any holder of
 * either marked any colleague's notification read by counting ids. The consequence is quiet: the
 * recipient's unread badge clears and they never see the notification, which for the leave
 * workflow means an approver never learns a request is waiting on them.
 *
 * <p>It could not be closed by repairing the guard, because it had no recipient parameter to be
 * wrong about. The check has to be made against the recipient stored on the row, which is what
 * these cases pin.
 *
 * <p>The read cases carry the other half. Four of the five endpoints took the recipient as an
 * {@code employeeId} the caller sent, so a repaired guard alone would have handed whoever got
 * through every colleague's inbox instead of nobody's. The recipient comes from the session, and
 * the parameter is gone from both twins.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationInboxOwnershipTest {

    private static final Long ORG_ID = 100L;
    private static final Long NOTIFICATION_ID = 42L;
    private static final Long RECIPIENT_ID = 7L;
    private static final Long COLLEAGUE_ID = 8L;

    @Mock private NotificationRepository notificationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private NotificationMapper notificationMapper;
    @Mock private CurrentEmployeeService currentEmployeeService;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
        when(notificationMapper.toDto(any(Notification.class))).thenReturn(new NotificationDto());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private NotificationService service() {
        return new NotificationService(
                notificationRepository, employeeRepository, notificationMapper, currentEmployeeService);
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        employee.setOrganization(organization);
        return employee;
    }

    /** The caller the session resolves to, for both the optional and the required lookup. */
    private void signedInAs(Long employeeId) {
        Employee caller = employee(employeeId);
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.of(caller));
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(caller);
    }

    private void signedInWithNoEmployeeRecord() {
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.empty());
        when(currentEmployeeService.requireCurrentEmployee(any()))
                .thenThrow(new AccessDeniedException("You have no employee record in this organization"));
    }

    private Notification notificationAddressedTo(Long recipientId) {
        Notification notification = new Notification();
        notification.setId(NOTIFICATION_ID);
        notification.setRecipient(employee(recipientId));
        notification.setIsRead(false);
        return notification;
    }

    @Test
    void theRecipientMarksTheirOwnNotificationRead() {
        signedInAs(RECIPIENT_ID);
        Notification notification = notificationAddressedTo(RECIPIENT_ID);
        when(notificationRepository.findByIdAndOrganization_Id(NOTIFICATION_ID, ORG_ID))
                .thenReturn(Optional.of(notification));

        assertThatCode(() -> service().markAsRead(NOTIFICATION_ID)).doesNotThrowAnyException();

        assertThat(notification.getIsRead()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    void aColleagueCannotMarkSomebodyElsesNotificationRead() {
        // The whole point of the issue. Before the check, this call succeeded for any caller the
        // guard let through, silently clearing the recipient's unread badge.
        signedInAs(COLLEAGUE_ID);
        Notification notification = notificationAddressedTo(RECIPIENT_ID);
        when(notificationRepository.findByIdAndOrganization_Id(NOTIFICATION_ID, ORG_ID))
                .thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service().markAsRead(NOTIFICATION_ID))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(notification.getIsRead()).isFalse();
        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void anAdministratorIsNoDifferentFromAnyOtherColleagueHere() {
        // Read state is personal. There is no role that makes marking somebody else's notification
        // read a sensible thing to do, so the roles that used to gate this endpoint buy nothing:
        // the check is against the recipient on the row and admins are not exempt from it.
        signedInAs(COLLEAGUE_ID);
        Notification notification = notificationAddressedTo(RECIPIENT_ID);
        when(notificationRepository.findByIdAndOrganization_Id(NOTIFICATION_ID, ORG_ID))
                .thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service().markAsRead(NOTIFICATION_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("addressed to somebody else");
    }

    @Test
    void aCallerWithNoEmployeeRecordMarksNothingRead() {
        signedInWithNoEmployeeRecord();
        when(notificationRepository.findByIdAndOrganization_Id(NOTIFICATION_ID, ORG_ID))
                .thenReturn(Optional.of(notificationAddressedTo(RECIPIENT_ID)));

        assertThatThrownBy(() -> service().markAsRead(NOTIFICATION_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void aRowWithNoRecipientIsNobodysToMark() {
        // Fail closed rather than open. A notification with a null recipient should not exist, and
        // if one does it belongs to nobody, so nobody clears it.
        signedInAs(RECIPIENT_ID);
        Notification orphan = new Notification();
        orphan.setId(NOTIFICATION_ID);
        orphan.setIsRead(false);
        when(notificationRepository.findByIdAndOrganization_Id(NOTIFICATION_ID, ORG_ID))
                .thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> service().markAsRead(NOTIFICATION_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void theInboxServedIsTheCallersOwn() {
        signedInAs(RECIPIENT_ID);
        Pageable pageable = PageRequest.of(0, 20);
        when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(RECIPIENT_ID, pageable))
                .thenReturn(Page.empty(pageable));

        service().getMyNotifications(pageable);

        // Named explicitly, so the test fails if the recipient is ever taken from anywhere but the
        // session again.
        verify(notificationRepository).findByRecipientIdOrderByCreatedAtDesc(RECIPIENT_ID, pageable);
        verify(notificationRepository, never())
                .findByRecipientIdOrderByCreatedAtDesc(eq(COLLEAGUE_ID), any(Pageable.class));
    }

    @Test
    void theUnreadListAndCountAreTheCallersOwn() {
        signedInAs(RECIPIENT_ID);
        when(notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(RECIPIENT_ID))
                .thenReturn(List.of(notificationAddressedTo(RECIPIENT_ID)));
        when(notificationRepository.countByRecipientIdAndIsReadFalse(RECIPIENT_ID)).thenReturn(3L);

        NotificationService service = service();

        assertThat(service.getMyUnreadNotifications()).hasSize(1);
        assertThat(service.getMyUnreadCount()).isEqualTo(3L);

        verify(notificationRepository, never())
                .findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(COLLEAGUE_ID);
        verify(notificationRepository, never()).countByRecipientIdAndIsReadFalse(COLLEAGUE_ID);
    }

    @Test
    void markingAllReadClearsTheCallersOwnInboxAndNobodyElses() {
        signedInAs(RECIPIENT_ID);
        when(notificationRepository.markAllAsReadByRecipientId(RECIPIENT_ID, ORG_ID)).thenReturn(4);

        assertThat(service().markAllAsRead()).isEqualTo(4);

        verify(notificationRepository).markAllAsReadByRecipientId(RECIPIENT_ID, ORG_ID);
        verify(notificationRepository, never()).markAllAsReadByRecipientId(COLLEAGUE_ID, ORG_ID);
    }

    @Test
    void aCallerWithNoEmployeeRecordHasNoInboxToRead() {
        signedInWithNoEmployeeRecord();

        NotificationService service = service();

        assertThatThrownBy(() -> service.getMyUnreadNotifications())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::getMyUnreadCount)
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::markAllAsRead)
                .isInstanceOf(AccessDeniedException.class);
    }
}
