package org.tornotron.echno_backend.attendance.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;

@Data
public class AttendanceRecordDto {

    @NotBlank
    @Size(min = 3,max = 50,message = "employeeName must be between 3 and 50 characters")
    private String employeeName;

    @NotBlank
    @Size(min = 3,max = 50,message = "location must be between 3 and 50 characters")
    private String location;

    @NotNull
    @Size(min = 3,max = 20,message = "recordType must be between 3 and 20 characters")
    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    @NotNull(message = "must not be blank")
    private GeoLocation geoLocation;

    @NotBlank
    private String deviceInfo;
}
