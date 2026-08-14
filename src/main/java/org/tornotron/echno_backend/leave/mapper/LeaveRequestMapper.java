package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.LeaveApproval;
import org.tornotron.echno_backend.leave.LeaveRequest;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;

/**
 * Maps {@link LeaveRequest} and its {@link LeaveApproval} lines to their DTOs. The
 * employee, organization, current-approver and (per approval) approver associations
 * flatten to ids and names; the leave policy maps to its simple DTO via
 * {@link LeavePolicyMapper}; the approval lines map through {@link #toApprovalDto}.
 *
 * handoverToName and delegatedFromName are left unset here, matching the plain
 * converter methods; the handover-name lookup lives in LeaveRequestService at the one
 * call site that needs it (the delegated-from-name variant was dead code and is dropped).
 */
@Mapper(componentModel = "spring", uses = LeavePolicyMapper.class)
public interface LeaveRequestMapper {

    @Mapping(source = "employee.id", target = "employeeId")
    @Mapping(source = "employee.employeeName", target = "employeeName")
    @Mapping(source = "employee.department", target = "department")
    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(source = "currentApprover.id", target = "currentApproverId")
    @Mapping(source = "currentApprover.employeeName", target = "currentApproverName")
    @Mapping(target = "handoverToName", ignore = true)
    LeaveRequestDto toDto(LeaveRequest request);

    @Mapping(source = "leaveRequest.id", target = "leaveRequestId")
    @Mapping(source = "approver.id", target = "approverId")
    @Mapping(source = "approver.employeeName", target = "approverName")
    @Mapping(source = "approver.designation", target = "approverDesignation")
    @Mapping(target = "delegatedFromName", ignore = true)
    LeaveApprovalDto toApprovalDto(LeaveApproval approval);
}
