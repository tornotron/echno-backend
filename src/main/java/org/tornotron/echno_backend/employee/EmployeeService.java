package org.tornotron.echno_backend.employee;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;

    public EmployeeService(EmployeeRepository employeeRepository, OrganizationRepository organizationRepository) {
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
    }

    private EmployeeDto EmployeeObjectMapper(EmployeeCreationDto employeeCreationDto, Employee employee, Organization organization) {
        employee.setEmployeeName(employeeCreationDto.getEmployeeName());
        employee.setGender(employeeCreationDto.getGender());
        employee.setPhoneNumber(employeeCreationDto.getPhoneNumber());
        employee.setEmailAddress(employeeCreationDto.getEmailAddress());
        employee.setDateOfBirth(employeeCreationDto.getDateOfBirth());
        employee.setOrganization(organization);
        return convertToEmployeeDto(employeeRepository.save(employee));
    }

    private EmployeeDto convertToEmployeeDto(Employee employee) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(employee.getId());
        dto.setEmployeeName(employee.getEmployeeName());
        dto.setGender(employee.getGender());
        dto.setEmailAddress(employee.getEmailAddress());
        dto.setPhoneNumber(employee.getPhoneNumber());
        dto.setDateOfBirth(employee.getDateOfBirth());
        return dto;
    }

    public EmployeeDto addEmployee(EmployeeCreationDto employeeCreationDto) {
        Employee employee = new Employee();
        Organization organization = organizationRepository.findOrganizationByOrganizationName(employeeCreationDto.getOrganizationName())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with name: " + employeeCreationDto.getOrganizationName()));
        return EmployeeObjectMapper(employeeCreationDto, employee, organization);
    }


    @Transactional(readOnly = true)
    public List<EmployeeDto> displayAllEmployees() {
        return employeeRepository.findAll().stream()
                .map(this::convertToEmployeeDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmployeeDto displayAnEmployee(Long id) {
        EmployeeDto employeeDto = employeeRepository.findById(id)
                .map(this::convertToEmployeeDto)
                .orElse(null);
        if(employeeDto==null) {
            throw new ResourceNotFoundException("Employee not found with id: "+id);
        } else {
            return employeeDto;
        }
    }

    public void partialUpdateAnEmployee(Map<String,Object> updates, Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: "+id));

        updates.forEach((key,value) -> {
            switch (key) {
                case "employeeName":
                    employee.setEmployeeName((String) value);
                    break;
                case "gender":
                    employee.setGender((String) value);
                    break;
                case "phoneNumber":
                    employee.setPhoneNumber((String) value);
                    break;
                case "emailAddress":
                    employee.setEmailAddress((String) value);
                    break;
                case "dateOfBirth":
                    employee.setDateOfBirth((LocalDateTime) value);
                    break;
            }
        });
        employeeRepository.save(employee);
    }

    public void batchUpdateEmployees(List<EmployeePatchDto> updates) {
        updates.forEach(update -> {
            partialUpdateAnEmployee(update.getUpdates(), update.getId());
        });
    }

    public void deleteAnEmployee(Long id) {
        if (!employeeRepository.existsById(id)) {
            throw new ResourceNotFoundException("Employee not found with id: " + id);
        }
        employeeRepository.deleteById(id);
    }

}
