package org.tornotron.echno_backend.leave.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveBalanceDto {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private LeavePolicySimpleDto leavePolicy;
    private Integer year;
    private Double openingBalance;
    private Double accrued;
    private Double used;
    private Double pending;
    private Double available;
    private Double bookable;
    private Double carryForwardFromPrevious;
    private LocalDate carryForwardExpiryDate;
    private LocalDateTime lastCalculatedAt;
}
