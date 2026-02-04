package org.tornotron.echno_backend.leave.dto;

import lombok.Data;

import java.util.List;

@Data
public class LeaveBalanceSummaryDto {
    private Long employeeId;
    private String employeeName;
    private Integer year;
    private List<LeaveBalanceDto> balances;
    private Double totalAvailable;
    private Double totalUsed;
    private Double totalPending;
}
