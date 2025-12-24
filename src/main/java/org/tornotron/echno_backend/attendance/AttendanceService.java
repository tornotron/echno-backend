package org.tornotron.echno_backend.attendance;

import jakarta.validation.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.dto.AttendanceCreationDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSummaryDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceUpdateDto;
import org.tornotron.echno_backend.attendance.dto.BulkAttendanceCreationDto;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.validator.AttendanceSequenceValidator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceSequenceValidator sequenceValidator;
    private final AttendanceMapper attendanceMapper;

    public AttendanceService(AttendanceRepository attendanceRepository,
                           EmployeeRepository employeeRepository,
                           AttendanceSequenceValidator sequenceValidator,
                           AttendanceMapper attendanceMapper) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.sequenceValidator = sequenceValidator;
        this.attendanceMapper = attendanceMapper;
    }

    @Transactional
    public void recordAttendance(AttendanceCreationDto attendanceCreationDto) {
        Employee employee = employeeRepository.findEmployeeByEmployeeName(attendanceCreationDto.getEmployeeName())
                .orElseThrow(() -> new ResourceNotFoundException("Employee does not exist"));

        Optional<Attendance> lastRecord = attendanceRepository.findLatestRecordForEmployee(employee.getId());

        sequenceValidator.validateRecordTypeSequence(lastRecord, attendanceCreationDto.getRecordType());

        Attendance attendanceRecord = new Attendance();
        attendanceRecord.setEmployeeId(employee.getId());
        attendanceRecord.setLocation(attendanceCreationDto.getLocation());
        attendanceRecord.setRecordType(attendanceCreationDto.getRecordType());
        attendanceRecord.setGeoLocation(attendanceCreationDto.getGeoLocation());
        attendanceRecord.setDeviceInfo(attendanceCreationDto.getDeviceInfo());

        attendanceRepository.save(attendanceRecord);
    }

    @Transactional
    public void recordBulkAttendance(BulkAttendanceCreationDto bulkDto) {
        List<Employee> employees = employeeRepository.findByEmployeeNameIn(bulkDto.getEmployeeNames());

        List<String> foundNames = employees.stream().map(Employee::getEmployeeName).toList();
        List<String> missingNames = bulkDto.getEmployeeNames().stream()
                .filter(name -> !foundNames.contains(name))
                .toList();

        if (!missingNames.isEmpty()) {
            throw new ResourceNotFoundException("Employees not found: " + missingNames);
        }

        List<Attendance> attendanceRecords = new ArrayList<>();

        for (Employee employee : employees) {
            Optional<Attendance> lastRecord = attendanceRepository.findLatestRecordForEmployee(employee.getId());

            sequenceValidator.validateRecordTypeSequence(lastRecord, bulkDto.getRecordType());

            Attendance attendanceRecord = new Attendance();
            attendanceRecord.setEmployeeId(employee.getId());
            attendanceRecord.setLocation(bulkDto.getLocation());
            attendanceRecord.setRecordType(bulkDto.getRecordType());
            attendanceRecord.setGeoLocation(bulkDto.getGeoLocation());

            attendanceRecords.add(attendanceRecord);
        }

        attendanceRepository.saveAll(attendanceRecords);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getAttendanceByEmployee(Long employeeId, LocalDate startDate, LocalDate endDate) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        List<Attendance> attendanceList = attendanceRepository.findByEmployeeIdAndTimestampBetween(
                employeeId, startDateTime, endDateTime);

        return attendanceList.stream()
                .map(attendance -> attendanceMapper.toResponseDto(attendance, employee))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getAttendanceByEmployeeName(String employeeName, LocalDate startDate, LocalDate endDate) {
        Employee employee = employeeRepository.findEmployeeByEmployeeName(employeeName)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        return getAttendanceByEmployee(employee.getId(), startDate, endDate);
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryDto getDailySummary(Long employeeId, LocalDate date) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        LocalDateTime startDateTime = date.atStartOfDay();
        LocalDateTime endDateTime = date.atTime(LocalTime.MAX);

        List<Attendance> records = attendanceRepository.findByEmployeeIdAndTimestampBetween(
                employeeId, startDateTime, endDateTime);

        return calculateDailySummary(employee, records, date);
    }

    @Transactional(readOnly = true)
    public List<AttendanceSummaryDto> getSummaryForDateRange(Long employeeId, LocalDate startDate, LocalDate endDate) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        List<AttendanceSummaryDto> summaries = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            LocalDateTime startDateTime = date.atStartOfDay();
            LocalDateTime endDateTime = date.atTime(LocalTime.MAX);

            List<Attendance> records = attendanceRepository.findByEmployeeIdAndTimestampBetween(
                    employeeId, startDateTime, endDateTime);

            summaries.add(calculateDailySummary(employee, records, date));
        }

        return summaries;
    }

    @Transactional
    public AttendanceResponseDto updateAttendance(AttendanceUpdateDto updateDto) {
        Attendance attendance = attendanceRepository.findById(updateDto.getAttendanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));

        Employee employee = employeeRepository.findById(attendance.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        attendance.setLocation(updateDto.getLocation());
        attendance.setCorrectionReason(updateDto.getCorrectionReason());
        attendance.setModifiedBy(updateDto.getModifiedBy());
        attendance.setLastModifiedAt(LocalDateTime.now());

        Attendance updated = attendanceRepository.save(attendance);

        return attendanceMapper.toResponseDto(updated, employee);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getCorrectedRecords(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

        List<Attendance> correctedRecords = attendanceRepository.findCorrectedRecords(startDateTime, endDateTime);

        return correctedRecords.stream()
                .map(attendance -> {
                    Employee employee = employeeRepository.findById(attendance.getEmployeeId()).orElse(null);
                    return attendanceMapper.toResponseDto(attendance, employee);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponseDto> getCorrectedRecordsByEmployee(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        List<Attendance> correctedRecords = attendanceRepository.findCorrectedRecordsByEmployee(employeeId);

        return correctedRecords.stream()
                .map(attendance -> attendanceMapper.toResponseDto(attendance, employee))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAttendance(Long attendanceId) {
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));

        attendanceRepository.delete(attendance);
    }

    private AttendanceSummaryDto calculateDailySummary(Employee employee, List<Attendance> records, LocalDate date) {
        if (records.isEmpty()) {
            return AttendanceSummaryDto.builder()
                    .employeeId(employee.getId())
                    .employeeName(employee.getEmployeeName())
                    .date(date)
                    .status("ABSENT")
                    .totalWorkMinutes(0L)
                    .totalBreakMinutes(0L)
                    .isLate(false)
                    .isEarlyCheckout(false)
                    .build();
        }

        records.sort(Comparator.comparing(Attendance::getTimestamp));

        LocalDateTime checkInTime = null;
        LocalDateTime checkOutTime = null;
        long totalWorkMinutes = 0;
        long totalBreakMinutes = 0;

        LocalDateTime lastWorkStart = null;
        LocalDateTime lastBreakStart = null;

        for (Attendance record : records) {
            switch (record.getRecordType()) {
                case CHECK_IN:
                    if (checkInTime == null) {
                        checkInTime = record.getTimestamp();
                    }
                    lastWorkStart = record.getTimestamp();
                    break;

                case CHECK_OUT:
                    checkOutTime = record.getTimestamp();
                    if (lastWorkStart != null) {
                        totalWorkMinutes += ChronoUnit.MINUTES.between(lastWorkStart, record.getTimestamp());
                        lastWorkStart = null;
                    }
                    break;

                case BREAK_START:
                    lastBreakStart = record.getTimestamp();
                    if (lastWorkStart != null) {
                        totalWorkMinutes += ChronoUnit.MINUTES.between(lastWorkStart, record.getTimestamp());
                        lastWorkStart = null;
                    }
                    break;

                case BREAK_END:
                    if (lastBreakStart != null) {
                        totalBreakMinutes += ChronoUnit.MINUTES.between(lastBreakStart, record.getTimestamp());
                        lastBreakStart = null;
                    }
                    lastWorkStart = record.getTimestamp();
                    break;
            }
        }

        boolean isLate = checkInTime != null && checkInTime.toLocalTime().isAfter(LocalTime.of(9, 30));
        boolean isEarlyCheckout = checkOutTime != null && checkOutTime.toLocalTime().isBefore(LocalTime.of(17, 0));

        String status = "PRESENT";
        if (totalWorkMinutes < 240) {
            status = "HALF_DAY";
        }
        if (isLate) {
            status = "LATE";
        }

        return AttendanceSummaryDto.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getEmployeeName())
                .date(date)
                .checkInTime(checkInTime)
                .checkOutTime(checkOutTime)
                .totalWorkMinutes(totalWorkMinutes)
                .totalBreakMinutes(totalBreakMinutes)
                .status(status)
                .isLate(isLate)
                .isEarlyCheckout(isEarlyCheckout)
                .build();
    }
}
