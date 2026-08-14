package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.LeaveTransaction;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;

/**
 * Maps {@link LeaveTransaction} to its DTO. The employee, leave-balance (including its
 * policy's type name) and leave-request associations are flattened to ids and names.
 * createdByName is left unset, matching the plain converter method that was in use
 * (the createdBy-enriching variant was dead code and is dropped).
 */
@Mapper(componentModel = "spring")
public interface LeaveTransactionMapper {

    @Mapping(source = "employee.id", target = "employeeId")
    @Mapping(source = "employee.employeeName", target = "employeeName")
    @Mapping(source = "leaveBalance.id", target = "leaveBalanceId")
    @Mapping(source = "leaveBalance.leavePolicy.leaveTypeName", target = "leaveTypeName")
    @Mapping(source = "leaveRequest.id", target = "leaveRequestId")
    @Mapping(source = "leaveRequest.requestNumber", target = "requestNumber")
    @Mapping(target = "createdByName", ignore = true)
    LeaveTransactionDto toDto(LeaveTransaction transaction);
}
