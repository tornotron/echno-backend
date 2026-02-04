package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.leave.LeaveBalance;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceSummaryDto;

import java.util.List;
import java.util.stream.Collectors;

public class LeaveBalanceDtoConvertor {

    public static LeaveBalanceDto convertToDto(LeaveBalance balance) {
        if (balance == null) return null;

        LeaveBalanceDto dto = new LeaveBalanceDto();
        dto.setId(balance.getId());
        dto.setEmployeeId(balance.getEmployee().getId());
        dto.setEmployeeName(balance.getEmployee().getEmployeeName());
        dto.setLeavePolicy(LeavePolicyDtoConvertor.convertToSimpleDto(balance.getLeavePolicy()));
        dto.setYear(balance.getYear());
        dto.setOpeningBalance(balance.getOpeningBalance());
        dto.setAccrued(balance.getAccrued());
        dto.setUsed(balance.getUsed());
        dto.setPending(balance.getPending());
        dto.setAvailable(balance.getAvailableBalance());
        dto.setBookable(balance.getBookableBalance());
        dto.setCarryForwardFromPrevious(balance.getCarryForwardFromPrevious());
        dto.setCarryForwardExpiryDate(balance.getCarryForwardExpiryDate());
        dto.setLastCalculatedAt(balance.getLastCalculatedAt());
        return dto;
    }

    public static LeaveBalanceSummaryDto convertToSummaryDto(
            Long employeeId,
            String employeeName,
            Integer year,
            List<LeaveBalance> balances) {

        LeaveBalanceSummaryDto summary = new LeaveBalanceSummaryDto();
        summary.setEmployeeId(employeeId);
        summary.setEmployeeName(employeeName);
        summary.setYear(year);

        List<LeaveBalanceDto> balanceDtos = balances.stream()
                .map(LeaveBalanceDtoConvertor::convertToDto)
                .collect(Collectors.toList());

        summary.setBalances(balanceDtos);

        double totalAvailable = balances.stream()
                .mapToDouble(LeaveBalance::getAvailableBalance)
                .sum();
        double totalUsed = balances.stream()
                .mapToDouble(LeaveBalance::getUsed)
                .sum();
        double totalPending = balances.stream()
                .mapToDouble(LeaveBalance::getPending)
                .sum();

        summary.setTotalAvailable(totalAvailable);
        summary.setTotalUsed(totalUsed);
        summary.setTotalPending(totalPending);

        return summary;
    }
}
