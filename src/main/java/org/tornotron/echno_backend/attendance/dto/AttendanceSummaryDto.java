package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSummaryDto {
    private Long employeeId;
    private String employeeName;
    private LocalDate date;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private Long totalWorkMinutes;
    private Long totalBreakMinutes;
    private String status; // PRESENT, ABSENT, HALF_DAY, LATE
    private Boolean isLate;
    private Boolean isEarlyCheckout;
}
