package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.LeaveApproval;
import org.tornotron.echno_backend.leave.LeaveRequest;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestSimpleDto;

import java.util.List;
import java.util.stream.Collectors;

public class LeaveRequestDtoConvertor {

    public static LeaveRequestDto convertToDto(LeaveRequest request) {
        if (request == null) return null;

        LeaveRequestDto dto = new LeaveRequestDto();
        dto.setId(request.getId());
        dto.setRequestNumber(request.getRequestNumber());
        dto.setEmployeeId(request.getEmployee().getId());
        dto.setEmployeeName(request.getEmployee().getEmployeeName());
        dto.setDepartment(request.getEmployee().getDepartment());
        dto.setOrganizationId(request.getOrganization().getId());
        dto.setLeavePolicy(LeavePolicyDtoConvertor.convertToSimpleDto(request.getLeavePolicy()));
        dto.setStartDate(request.getStartDate());
        dto.setStartHalfDayType(request.getStartHalfDayType());
        dto.setEndDate(request.getEndDate());
        dto.setEndHalfDayType(request.getEndHalfDayType());
        dto.setTotalDays(request.getTotalDays());
        dto.setReason(request.getReason());
        dto.setStatus(request.getStatus());

        if (request.getCurrentApprover() != null) {
            dto.setCurrentApproverId(request.getCurrentApprover().getId());
            dto.setCurrentApproverName(request.getCurrentApprover().getEmployeeName());
        }

        dto.setCurrentApprovalLevel(request.getCurrentApprovalLevel());
        dto.setMaxApprovalLevel(request.getMaxApprovalLevel());
        dto.setContactDuringLeave(request.getContactDuringLeave());
        dto.setHandoverToId(request.getHandoverToId());
        dto.setHandoverNotes(request.getHandoverNotes());
        dto.setCancelledAt(request.getCancelledAt());
        dto.setCancellationReason(request.getCancellationReason());
        dto.setCreatedAt(request.getCreatedAt());
        dto.setUpdatedAt(request.getUpdatedAt());

        if (request.getApprovals() != null) {
            List<LeaveApprovalDto> approvalDtos = request.getApprovals().stream()
                    .map(LeaveRequestDtoConvertor::convertApprovalToDto)
                    .collect(Collectors.toList());
            dto.setApprovals(approvalDtos);
        }

        return dto;
    }

    public static LeaveRequestDto convertToDtoWithHandover(LeaveRequest request, EmployeeRepository employeeRepository) {
        LeaveRequestDto dto = convertToDto(request);

        if (dto != null && request.getHandoverToId() != null) {
            employeeRepository.findById(request.getHandoverToId())
                    .ifPresent(emp -> dto.setHandoverToName(emp.getEmployeeName()));
        }

        return dto;
    }

    public static LeaveRequestSimpleDto convertToSimpleDto(LeaveRequest request) {
        if (request == null) return null;

        LeaveRequestSimpleDto dto = new LeaveRequestSimpleDto();
        dto.setId(request.getId());
        dto.setRequestNumber(request.getRequestNumber());
        dto.setEmployeeName(request.getEmployee().getEmployeeName());
        dto.setLeaveTypeName(request.getLeavePolicy().getLeaveTypeName());
        dto.setStartDate(request.getStartDate());
        dto.setEndDate(request.getEndDate());
        dto.setTotalDays(request.getTotalDays());
        dto.setStatus(request.getStatus());
        dto.setCreatedAt(request.getCreatedAt());
        return dto;
    }

    public static LeaveApprovalDto convertApprovalToDto(LeaveApproval approval) {
        if (approval == null) return null;

        LeaveApprovalDto dto = new LeaveApprovalDto();
        dto.setId(approval.getId());
        dto.setLeaveRequestId(approval.getLeaveRequest().getId());
        dto.setApproverId(approval.getApprover().getId());
        dto.setApproverName(approval.getApprover().getEmployeeName());
        dto.setApproverDesignation(approval.getApprover().getDesignation());
        dto.setApprovalLevel(approval.getApprovalLevel());
        dto.setAction(approval.getAction());
        dto.setComments(approval.getComments());
        dto.setDelegatedFromId(approval.getDelegatedFromId());
        dto.setActionAt(approval.getActionAt());
        dto.setCreatedAt(approval.getCreatedAt());
        return dto;
    }

    public static LeaveApprovalDto convertApprovalToDtoWithDelegatedFrom(
            LeaveApproval approval,
            EmployeeRepository employeeRepository) {

        LeaveApprovalDto dto = convertApprovalToDto(approval);

        if (dto != null && approval.getDelegatedFromId() != null) {
            employeeRepository.findById(approval.getDelegatedFromId())
                    .ifPresent(emp -> dto.setDelegatedFromName(emp.getEmployeeName()));
        }

        return dto;
    }
}
