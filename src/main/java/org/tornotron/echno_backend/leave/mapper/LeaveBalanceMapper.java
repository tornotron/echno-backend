package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.LeaveBalance;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;

/**
 * Maps {@link LeaveBalance} to its DTO. The employee flattens to id + name, the leave
 * policy is mapped to its simple DTO through {@link LeavePolicyMapper}, and the
 * available/bookable balances map from their differently-named entity getters.
 *
 * The balance-summary aggregation the old converter also carried is not a per-entity
 * mapping and now lives in LeaveBalanceService.
 */
@Mapper(componentModel = "spring", uses = LeavePolicyMapper.class)
public interface LeaveBalanceMapper {

    @Mapping(source = "employee.id", target = "employeeId")
    @Mapping(source = "employee.employeeName", target = "employeeName")
    @Mapping(source = "availableBalance", target = "available")
    @Mapping(source = "bookableBalance", target = "bookable")
    LeaveBalanceDto toDto(LeaveBalance balance);
}
