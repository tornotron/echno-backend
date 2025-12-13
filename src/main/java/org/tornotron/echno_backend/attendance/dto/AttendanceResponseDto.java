package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceResponseDto {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private String location;
    private RecordType recordType;
    private LocalDateTime timestamp;
    private String source;
    private GeoLocation geoLocation;
    private String deviceInfo;
    private LocalDateTime lastModifiedAt;
    private String modifiedBy;
    private String correctionReason;
}
