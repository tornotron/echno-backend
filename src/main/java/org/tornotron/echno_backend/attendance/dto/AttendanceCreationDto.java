package org.tornotron.echno_backend.attendance.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.attendance.enums.RecordType;

@Data
public class AttendanceCreationDto {

    @NotBlank
    @Size(min = 3,max = 50,message = "employeeName must be between 3 and 50 characters")
    private String employeeName;

    @NotBlank
    @Size(min = 3,max = 50,message = "location must be between 3 and 50 characters")
    private String location;

    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    @NotBlank
    private String geoLocation;

    @NotBlank
    private String deviceInfo;
}
