package org.tornotron.echno_backend.attendance.dto;

import lombok.Data;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.embed.GeoLocation;

@Data
public class AttendanceDto {
    private Long id;
    private String employeeName;
    private String location;
    private RecordType recordType;
    private GeoLocation geoLocation;
    private String deviceInfo;
}
