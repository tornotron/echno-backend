package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.MovementType;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovementRecordDto {
    private Long id;
    private Long attendanceId;
    private Long employeeId;
    private String employeeName;
    private MovementType movementType;
    private String fromLocation;
    private String toLocation;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Double distanceKm;
    private String purpose;
    private String remarks;
    private Double startLatitude;
    private Double startLongitude;
    private Double endLatitude;
    private Double endLongitude;
    private List<String> attachments;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private Boolean isVerified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
