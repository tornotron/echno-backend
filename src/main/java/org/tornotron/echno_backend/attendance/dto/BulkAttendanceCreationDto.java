package org.tornotron.echno_backend.attendance.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;

import java.util.List;

@Data
public class BulkAttendanceCreationDto {

    @NotEmpty(message = "Employee names list cannot be empty")
    private List<String> employeeNames;

    @NotBlank
    @Size(min = 3, max = 50, message = "location must be between 3 and 50 characters")
    private String location;

    @NotNull(message = "recordType is required")
    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    @Valid
    private GeoLocation geoLocation;

}
