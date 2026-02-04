package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveRequestSimpleDto {
    private Long id;
    private String requestNumber;
    private String employeeName;
    private String leaveTypeName;
    private LocalDate startDate;
    private LocalDate endDate;
    private Double totalDays;
    private LeaveStatus status;
    private LocalDateTime createdAt;
}
