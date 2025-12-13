package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.employee.Employee;

@Component
public class AttendanceMapper {

    public AttendanceResponseDto toResponseDto(Attendance attendance, Employee employee) {
        return AttendanceResponseDto.builder()
                .id(attendance.getId())
                .employeeId(attendance.getEmployeeId())
                .employeeName(employee != null ? employee.getEmployeeName() : null)
                .location(attendance.getLocation())
                .recordType(attendance.getRecordType())
                .timestamp(attendance.getTimestamp())
                .source(attendance.getSource())
                .geoLocation(attendance.getGeoLocation())
                .deviceInfo(attendance.getDeviceInfo())
                .lastModifiedAt(attendance.getLastModifiedAt())
                .modifiedBy(attendance.getModifiedBy())
                .correctionReason(attendance.getCorrectionReason())
                .build();
    }

    public AttendanceResponseDto toResponseDto(Attendance attendance, String employeeName) {
        return AttendanceResponseDto.builder()
                .id(attendance.getId())
                .employeeId(attendance.getEmployeeId())
                .employeeName(employeeName)
                .location(attendance.getLocation())
                .recordType(attendance.getRecordType())
                .timestamp(attendance.getTimestamp())
                .source(attendance.getSource())
                .geoLocation(attendance.getGeoLocation())
                .deviceInfo(attendance.getDeviceInfo())
                .lastModifiedAt(attendance.getLastModifiedAt())
                .modifiedBy(attendance.getModifiedBy())
                .correctionReason(attendance.getCorrectionReason())
                .build();
    }
}
