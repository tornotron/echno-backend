package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.NotificationDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.NotificationDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Validated
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmployeeRepository employeeRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            EmployeeRepository employeeRepository) {
        this.notificationRepository = notificationRepository;
        this.employeeRepository = employeeRepository;
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
        String delegatedFromName = employeeRepository.findById(delegatedFromId)
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

    @Transactional(readOnly = true)
    public Page<NotificationDto> getNotifications(Long employeeId, Pageable pageable) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(employeeId, pageable)
                .map(NotificationDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> getUnreadNotifications(Long employeeId) {
        return notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(employeeId)
                .stream()
                .map(NotificationDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long employeeId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(employeeId);
    }

    @Transactional
    public void markAsRead(Long notificationId) {
        Notification notification = notificationRepository.findByIdAndOrganization_Id(notificationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found with id: " + notificationId));

        notification.setIsRead(true);
        notification.setReadAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    @Transactional
    public int markAllAsRead(Long employeeId) {
        return notificationRepository.markAllAsReadByRecipientId(employeeId);
    }
}
