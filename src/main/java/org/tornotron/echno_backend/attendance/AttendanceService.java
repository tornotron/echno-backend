package org.tornotron.echno_backend.attendance;

import jakarta.validation.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.dto.AttendanceCreationDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.validator.AttendanceSequenceValidator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;

import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceSequenceValidator sequenceValidator;

    public AttendanceService(AttendanceRepository attendanceRepository, EmployeeRepository employeeRepository, AttendanceSequenceValidator sequenceValidator) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.sequenceValidator = sequenceValidator;
    }

    public void recordAttendance(AttendanceCreationDto attendanceCreationDto) {
        Optional<Employee> employee = employeeRepository.findEmployeeByEmployeeName(attendanceCreationDto.getEmployeeName());
        if(employee.isEmpty()) {
            throw new ResourceNotFoundException("Employee does not exist");
        }
        Optional<Attendance> lastRecord = attendanceRepository.findLatestRecordForEmployee(employee.get().getId());
        System.out.println(lastRecord);

        System.out.println(attendanceCreationDto.getRecordType());
        sequenceValidator.validateRecordTypeSequence(lastRecord, attendanceCreationDto.getRecordType());
        validateAttendance(lastRecord, attendanceCreationDto);


        Attendance attendanceRecord = new Attendance();
        attendanceRecord.setEmployeeId(employee.get().getId());
        attendanceRecord.setLocation(attendanceCreationDto.getLocation());
        attendanceRecord.setRecordType(attendanceCreationDto.getRecordType());
//        attendanceRecord.setGeoLocation(attendanceCreationDto.getGeoLocation());
        attendanceRecord.setDeviceInfo(attendanceCreationDto.getDeviceInfo());


        attendanceRepository.save(attendanceRecord);
    }

    private void validateAttendance(Optional<Attendance> attendanceLastRecord, AttendanceCreationDto attendanceCreationDto) {
        Optional<Employee> employeeOptional = employeeRepository.findEmployeeByEmployeeName(attendanceCreationDto.getEmployeeName());
        if(employeeOptional.isEmpty()) {
            throw new ResourceNotFoundException("Employee does not exist");
        }
        if (attendanceLastRecord.isEmpty()) {
            throw new ResourceNotFoundException("Attendance record for this employee does not exist");
        }
        Employee employee = employeeOptional.get();
        if(!Objects.equals(attendanceLastRecord.get().getEmployeeId(), employee.getId())) {
            throw new ValidationException("Employee Id does not match previous entry");
        }
        if(!Objects.equals(attendanceLastRecord.get().getLocation(), attendanceCreationDto.getLocation())) {
            throw new ValidationException("Location does not match previous entry");
        }
    }
}
