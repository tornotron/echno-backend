package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.MovementType;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovementRecordCreationDto {

    @NotNull
    private Long attendanceId;

    @NotNull
    private MovementType movementType;

    @NotBlank
    private String fromLocation;

    private String toLocation;

    @NotNull
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @NotBlank
    private String purpose;

    private String remarks;

    private Double startLatitude;
    private Double startLongitude;
    private Double endLatitude;
    private Double endLongitude;
    private Double distanceKm;

    private List<String> attachments;
}
