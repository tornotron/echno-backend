package org.tornotron.echno_backend.attendance;

import jakarta.validation.ValidationException;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.attendance.dto.AttendanceRecordDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.validator.AttendanceSequenceValidator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;

import java.util.Objects;
import java.util.Optional;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceSequenceValidator sequenceValidator;

    public AttendanceService(AttendanceRepository attendanceRepository, EmployeeRepository employeeRepository, AttendanceSequenceValidator sequenceValidator) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.sequenceValidator = sequenceValidator;
    }

    public void recordAttendance(AttendanceRecordDto attendanceRecordDto) {
        Optional<Employee> employee = employeeRepository.findEmployeeByEmployeeName(attendanceRecordDto.getEmployeeName());
        if(employee.isEmpty()) {
            throw new ResourceNotFoundException("Employee does not exist");
        }
        Optional<Attendance> lastRecord = attendanceRepository.findLatestRecordForEmployee(employee.get().getId());
        System.out.println(lastRecord);

        System.out.println(attendanceRecordDto.getRecordType());
        sequenceValidator.validateRecordTypeSequence(lastRecord,attendanceRecordDto.getRecordType());
        validateAttendance(lastRecord,attendanceRecordDto);


        Attendance attendanceRecord = new Attendance();
        attendanceRecord.setEmployeeId(employee.get().getId());
        attendanceRecord.setLocation(attendanceRecordDto.getLocation());
        attendanceRecord.setRecordType(attendanceRecordDto.getRecordType());
        attendanceRecord.setGeoLocation(attendanceRecordDto.getGeoLocation());
        attendanceRecord.setDeviceInfo(attendanceRecordDto.getDeviceInfo());


        attendanceRepository.save(attendanceRecord);
    }

    private void validateAttendance(Optional<Attendance> attendanceLastRecord,AttendanceRecordDto attendanceRecordDto) {
        Optional<Employee> employeeOptional = employeeRepository.findEmployeeByEmployeeName(attendanceRecordDto.getEmployeeName());
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
        if(!Objects.equals(attendanceLastRecord.get().getLocation(),attendanceRecordDto.getLocation())) {
            throw new ValidationException("Location does not match previous entry");
        }
    }
}
