package org.tornotron.echno_backend.employee;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    public EmployeeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    private boolean EmployeeObjectMapper(EmployeeCreationDto employeeCreationDto, Employee employee) {
        employee.setEmployeeName(employeeCreationDto.getEmployeeName());
        employee.setGender(employeeCreationDto.getGender());
        employee.setPhoneNumber(employeeCreationDto.getPhoneNumber());
        employee.setEmailAddress(employeeCreationDto.getEmailAddress());
        employee.setDateOfBirth(employeeCreationDto.getDateOfBirth());
        employeeRepository.save(employee);
        return true;
    }

    private EmployeeCreationDto convertToDto(Employee employee) {
        EmployeeCreationDto dto = new EmployeeCreationDto();
        dto.setEmployeeName(employee.getEmployeeName());
        dto.setGender(employee.getGender());
        dto.setEmailAddress(employee.getEmailAddress());
        dto.setPhoneNumber(employee.getPhoneNumber());
        dto.setDateOfBirth(employee.getDateOfBirth());
        return dto;
    }

    public boolean addEmployee(EmployeeCreationDto employeeCreationDto) {
        Employee employee = new Employee();
        return EmployeeObjectMapper(employeeCreationDto, employee);
    }


    public List<EmployeeCreationDto> displayAllEmployees() {
        return employeeRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public EmployeeCreationDto displayAnEmployee(Long id) {
        return employeeRepository.findById(id)
                .map(this::convertToDto)
                .orElse(null);
    }

    public boolean updateAnEmployee(EmployeeCreationDto updatedEmployee, Long id) {
        if(updatedEmployee == null) {
            return false;
        }
        Optional<Employee> employeeOptional = employeeRepository.findById(id);
        if(employeeOptional.isPresent()) {
            Employee employee = employeeOptional.get();
            return EmployeeObjectMapper(updatedEmployee, employee);
        }
        return false;
    }

    public void deleteAnEmployee(Long id) {
        if (!employeeRepository.existsById(id)) {
            throw new ResourceNotFoundException("Employee not found with id: " + id);
        }
        employeeRepository.deleteById(id);
    }

}
