package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
@Validated
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public EmployeeService(EmployeeRepository employeeRepository, OrganizationRepository organizationRepository, UserRepository userRepository) {
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
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
        dto.setBloodGroup(employee.getUser().getBloodGroup());
        dto.setQualification(employee.getUser().getQualification());
        dto.setSkills(employee.getUser().getSkills());
        dto.setExperience(employee.getUser().getExperience());
        dto.setCvUrl(employee.getUser().getCvUrl());
        dto.setEmergencyContact(employee.getUser().getEmergencyContact());
        dto.setRole(employee.getUser().getRole());
        dto.setProfilePictureUrl(employee.getUser().getProfilePictureUrl());
        dto.setCreatedAt(employee.getUser().getCreatedAt());
        dto.setUpdatedAt(employee.getUser().getUpdatedAt());
        return dto;
    }


    public EmployeeDto joinOrganization(Long userId, Long orgId,@Valid EmployeeJoinOrgDto employeeJoinOrgDto) {

        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        Organization org = organizationRepository.findById(orgId).orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));

        if(employeeRepository.existsByUserAndOrganization(user, org)) {
            throw new IllegalStateException("User already employed in this organization");
        }

        Employee employee = new Employee();

        employee.setUser(user);
        employee.setOrganization(org);

        employee.setDesignation(employeeJoinOrgDto.getDesignation());
        employee.setDepartment(employeeJoinOrgDto.getDepartment());
        employee.setJoiningDate(employeeJoinOrgDto.getJoiningDate());
        employee.setSalary(employeeJoinOrgDto.getSalary());
        employee.setReportingManager(employeeJoinOrgDto.getReportingManager());
        employee.setShiftTiming(employeeJoinOrgDto.getShiftTiming());
        employee.setStatus(EmployeeStatus.valueOf(employeeJoinOrgDto.getStatus()));
        employee.setEmployeeName(user.getName());
        employee.setGender(user.getGender());
        employee.setPhoneNumber(user.getPhone());
        employee.setEmailAddress(user.getEmail());
        employee.setDateOfBirth(user.getDateOfBirth());

        Employee savedEmployee = employeeRepository.save(employee);

        return convertToEmployeeDto(savedEmployee);
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
