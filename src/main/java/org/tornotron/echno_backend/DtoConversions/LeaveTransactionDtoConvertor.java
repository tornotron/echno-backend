package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.LeaveTransaction;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;

public class LeaveTransactionDtoConvertor {

    public static LeaveTransactionDto convertToDto(LeaveTransaction transaction) {
        if (transaction == null) return null;

        LeaveTransactionDto dto = new LeaveTransactionDto();
        dto.setId(transaction.getId());
        dto.setEmployeeId(transaction.getEmployee().getId());
        dto.setEmployeeName(transaction.getEmployee().getEmployeeName());
        dto.setLeaveBalanceId(transaction.getLeaveBalance().getId());
        dto.setLeaveTypeName(transaction.getLeaveBalance().getLeavePolicy().getLeaveTypeName());

        if (transaction.getLeaveRequest() != null) {
            dto.setLeaveRequestId(transaction.getLeaveRequest().getId());
            dto.setRequestNumber(transaction.getLeaveRequest().getRequestNumber());
        }

        dto.setTransactionType(transaction.getTransactionType());
        dto.setDays(transaction.getDays());
        dto.setBalanceBefore(transaction.getBalanceBefore());
        dto.setBalanceAfter(transaction.getBalanceAfter());
        dto.setTransactionDate(transaction.getTransactionDate());
        dto.setReferenceMonth(transaction.getReferenceMonth());
        dto.setReferenceYear(transaction.getReferenceYear());
        dto.setDescription(transaction.getDescription());
        dto.setCreatedById(transaction.getCreatedById());
        dto.setCreatedAt(transaction.getCreatedAt());

        return dto;
    }

    public static LeaveTransactionDto convertToDtoWithCreatedBy(
            LeaveTransaction transaction,
            EmployeeRepository employeeRepository) {

        LeaveTransactionDto dto = convertToDto(transaction);

        if (dto != null && transaction.getCreatedById() != null) {
            employeeRepository.findById(transaction.getCreatedById())
                    .ifPresent(emp -> dto.setCreatedByName(emp.getEmployeeName()));
        }

        return dto;
    }
}
