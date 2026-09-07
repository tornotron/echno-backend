package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.NotificationDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.mapper.NotificationMapper;
import org.tornotron.echno_backend.leave.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Validated
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentEmployeeService currentEmployeeService;

    public NotificationService(
            NotificationRepository notificationRepository,
            EmployeeRepository employeeRepository,
            NotificationMapper notificationMapper,
            CurrentEmployeeService currentEmployeeService) {
        this.notificationRepository = notificationRepository;
        this.employeeRepository = employeeRepository;
        this.notificationMapper = notificationMapper;
        this.currentEmployeeService = currentEmployeeService;
    }

    /**
     * Sends one composed notification to one employee.
     *
     * <p>The seam every non-leave sender goes through. The five methods below it are one method
     * per leave event, each carrying a {@link LeaveRequest} in its signature, and there was no way
     * for anything outside this package to raise a notification without a sixth being written for
     * it here. This takes the message as data instead.
     *
     * <p>The organization is the recipient's own rather than anything the caller passes, which is
     * what the five leave senders already do. A notification belongs to the tenant of the person
     * reading it, and a caller that could set it independently could file a row into an
     * organization the recipient cannot see, where it would be invisible to them and visible to
     * everybody else.
     *
     * @param recipient Who is being told. Must be persisted and must have an organization.
     * @param draft What they are being told.
     * @return The saved notification.
     */
    @Transactional
    public Notification deliver(Employee recipient, NotificationDraft draft) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setOrganization(recipient.getOrganization());
        notification.setNotificationType(draft.type());
        notification.setTitle(draft.title());
        notification.setMessage(draft.message());
        notification.setEntityType(draft.entityType());
        notification.setEntityId(draft.entityId());
        notification.setActionUrl(draft.actionUrl());
        notification.setIsRead(false);
        return notificationRepository.save(notification);
    }

    /**
     * Sends the same composed notification to several employees, one row each.
     *
     * <p>A role-targeted notification is a fan-out and cannot be anything else here:
     * {@code Notification.recipient} is a single employee foreign key, so there is no row that
     * addresses a role and no row that addresses everybody. Saying so in one method keeps callers
     * from each inventing their own loop, and keeps the wording of an event written once.
     *
     * <p>An empty recipient list saves nothing and is not an error. Deciding whether having
     * nobody to tell is worth reporting belongs to the caller, which is the only party that knows
     * what the silence means.
     *
     * @param recipients Who is being told. May be empty.
     * @param draft What they are being told.
     * @return The saved notifications, in the order the recipients were given.
     */
    @Transactional
    public List<Notification> deliverToAll(Collection<Employee> recipients, NotificationDraft draft) {
        if (recipients == null || recipients.isEmpty()) {
            return List.of();
        }
        return recipients.stream().map(recipient -> deliver(recipient, draft)).toList();
    }

    @Transactional
    public void sendApprovalRequiredNotification(LeaveRequest request, Employee approver) {
        Notification notification = new Notification();
        notification.setRecipient(approver);
        notification.setOrganization(approver.getOrganization());
        notification.setNotificationType(NotificationType.LEAVE_PENDING_APPROVAL);
        notification.setTitle("Leave Approval Required");
        notification.setMessage(String.format(
                "%s has requested %s leave from %s to %s (%s days). Please review.",
                request.getEmployee().getEmployeeName(),
                request.getLeavePolicy().getLeaveTypeName(),
                request.getStartDate(),
                request.getEndDate(),
                request.getTotalDays()));
        notification.setEntityType("LEAVE_REQUEST");
        notification.setEntityId(request.getId());
        notification.setActionUrl("/leave-requests/" + request.getId());
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    @Transactional
    public void sendLeaveDecisionNotification(LeaveRequest request, ApprovalAction action) {
        NotificationType type = action == ApprovalAction.APPROVED
                ? NotificationType.LEAVE_APPROVED
                : NotificationType.LEAVE_REJECTED;

        String status = action == ApprovalAction.APPROVED ? "approved" : "rejected";

        Notification notification = new Notification();
        notification.setRecipient(request.getEmployee());
        notification.setOrganization(request.getEmployee().getOrganization());
        notification.setNotificationType(type);
        notification.setTitle("Leave Request " + status.substring(0, 1).toUpperCase() + status.substring(1));
        notification.setMessage(String.format(
                "Your %s leave request (%s) from %s to %s has been %s.",
                request.getLeavePolicy().getLeaveTypeName(),
                request.getRequestNumber(),
                request.getStartDate(),
                request.getEndDate(),
                status));
        notification.setEntityType("LEAVE_REQUEST");
        notification.setEntityId(request.getId());
        notification.setActionUrl("/leave-requests/" + request.getId());
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    @Transactional
    public void sendDelegationNotification(LeaveRequest request, Employee delegatedTo, Long delegatedFromId) {
        String delegatedFromName = employeeRepository.findByIdAndOrganizationId(delegatedFromId,TenantContext.getCurrentOrgId())
                .map(Employee::getEmployeeName)
                .orElse("Someone");

        Notification notification = new Notification();
        notification.setRecipient(delegatedTo);
        notification.setOrganization(delegatedTo.getOrganization());
        notification.setNotificationType(NotificationType.APPROVAL_DELEGATED);
        notification.setTitle("Leave Approval Delegated to You");
        notification.setMessage(String.format(
                "%s has delegated the approval of %s's leave request (%s) to you.",
                delegatedFromName,
                request.getEmployee().getEmployeeName(),
                request.getRequestNumber()));
        notification.setEntityType("LEAVE_REQUEST");
        notification.setEntityId(request.getId());
        notification.setActionUrl("/leave-requests/" + request.getId());
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    @Transactional
    public void sendLeaveSubmittedNotification(LeaveRequest request) {
        Notification notification = new Notification();
        notification.setRecipient(request.getEmployee());
        notification.setOrganization(request.getEmployee().getOrganization());
        notification.setNotificationType(NotificationType.LEAVE_REQUEST_SUBMITTED);
        notification.setTitle("Leave Request Submitted");
        notification.setMessage(String.format(
                "Your %s leave request (%s) from %s to %s has been submitted for approval.",
                request.getLeavePolicy().getLeaveTypeName(),
                request.getRequestNumber(),
                request.getStartDate(),
                request.getEndDate()));
        notification.setEntityType("LEAVE_REQUEST");
        notification.setEntityId(request.getId());
        notification.setActionUrl("/leave-requests/" + request.getId());
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    @Transactional
    public void sendLeaveCancelledNotification(LeaveRequest request) {
        Notification notification = new Notification();
        notification.setRecipient(request.getEmployee());
        notification.setOrganization(request.getEmployee().getOrganization());
        notification.setNotificationType(NotificationType.LEAVE_CANCELLED);
        notification.setTitle("Leave Request Cancelled");
        notification.setMessage(String.format(
                "Your %s leave request (%s) from %s to %s has been cancelled.",
                request.getLeavePolicy().getLeaveTypeName(),
                request.getRequestNumber(),
                request.getStartDate(),
                request.getEndDate()));
        notification.setEntityType("LEAVE_REQUEST");
        notification.setEntityId(request.getId());
        notification.setActionUrl("/leave-requests/" + request.getId());
        notification.setIsRead(false);

        notificationRepository.save(notification);
    }

    /**
     * The caller's own notifications, newest first.
     *
     * <p>The recipient used to be an {@code employeeId} the caller sent, under a guard that read
     * nothing: the guard established that the caller held a role and the query answered about
     * whoever the caller named, with nothing tying the two together. A notification is addressed to
     * one person, so the inbox is the caller's own by definition and the recipient comes from the
     * session.
     */
    @Transactional(readOnly = true)
    public Page<NotificationDto> getMyNotifications(Pageable pageable) {
        Long recipientId = currentRecipientId("read your notifications");
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable)
                .map(notificationMapper::toDto);
    }

    /** The caller's own notifications that have not been marked read. */
    @Transactional(readOnly = true)
    public List<NotificationDto> getMyUnreadNotifications() {
        Long recipientId = currentRecipientId("read your notifications");
        return notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipientId)
                .stream()
                .map(notificationMapper::toDto)
                .collect(Collectors.toList());
    }

    /** How many of the caller's own notifications are unread, for the badge a client draws. */
    @Transactional(readOnly = true)
    public long getMyUnreadCount() {
        return notificationRepository.countByRecipientIdAndIsReadFalse(
                currentRecipientId("count your notifications"));
    }

    /**
     * Marks one of the caller's own notifications read.
     *
     * <p>This used to check nothing at all. It took a notification id, loaded the row by that id
     * within the current tenant, and wrote {@code isRead} on whatever came back, so any caller who
     * got past the guard marked any colleague's notification read by counting ids. It did not even
     * have a recipient parameter to be wrong about, which is why a repaired guard alone would not
     * have closed it: the check has to be made against the stored row.
     *
     * <p>Read state is personal, and the damage from marking someone else's notification read is
     * that they never see it. The recipient on the row is the only person who may mark it.
     */
    @Transactional
    public void markAsRead(Long notificationId) {
        Notification notification = notificationRepository.findByIdAndOrganization_Id(notificationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification with ID " + notificationId + " was not found in this organization"));

        Long callerId = currentRecipientId("mark a notification read");
        if (notification.getRecipient() == null
                || !callerId.equals(notification.getRecipient().getId())) {
            throw new AccessDeniedException(
                    "This notification is addressed to somebody else, so it is not yours to mark read.");
        }

        notification.setIsRead(true);
        notification.setReadAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    /** Marks every unread notification addressed to the caller read, and says how many that was. */
    @Transactional
    public int markAllAsRead() {
        return notificationRepository.markAllAsReadByRecipientId(
                currentRecipientId("mark your notifications read"), TenantContext.getCurrentOrgId());
    }

    private Long currentRecipientId(String action) {
        return currentEmployeeService.requireCurrentEmployee(action).getId();
    }
}
