package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveTransactionDto {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private Long leaveBalanceId;
    private String leaveTypeName;
    private Long leaveRequestId;
    private String requestNumber;
    private TransactionType transactionType;
    private Double days;
    private Double balanceBefore;
    private Double balanceAfter;
    private LocalDate transactionDate;
    private Integer referenceMonth;
    private Integer referenceYear;
    private String description;
    private Long createdById;
    private String createdByName;
    private LocalDateTime createdAt;
}
