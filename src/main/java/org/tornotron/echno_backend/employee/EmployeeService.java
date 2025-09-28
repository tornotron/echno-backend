package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.EmployeeDtoConvertor;
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

/**
 * Service class for managing employees.
 * Handles business logic related to employee creation, retrieval, updates, and deletion,
 * as well as joining organizations.
 */
@Service
@Validated
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    /**
     * Constructs an EmployeeService with the necessary repositories.
     *
     * @param employeeRepository     The repository for employee data access.
     * @param organizationRepository The repository for organization data access.
     * @param userRepository         The repository for user data access.
     */
    public EmployeeService(EmployeeRepository employeeRepository, OrganizationRepository organizationRepository, UserRepository userRepository) {
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Maps data from an {@link EmployeeCreationDto} to an {@link Employee} entity and saves it.
     *
     * @param employeeCreationDto The DTO containing employee creation data.
     * @param employee            The employee entity to map data to.
     * @param organization        The organization the employee belongs to.
     * @return The DTO of the saved employee.
     */
    private EmployeeDto EmployeeObjectMapper(EmployeeCreationDto employeeCreationDto, Employee employee, Organization organization) {
        employee.setEmployeeName(employeeCreationDto.getEmployeeName());
        employee.setGender(employeeCreationDto.getGender());
        employee.setAddress(employeeCreationDto.getAddress());
        employee.setPhoneNumber(employeeCreationDto.getPhoneNumber());
        employee.setEmailAddress(employeeCreationDto.getEmailAddress());
        employee.setDateOfBirth(employeeCreationDto.getDateOfBirth());
        employee.setOrganization(organization);
        return EmployeeDtoConvertor.convertEmployeeToDto(employeeRepository.save(employee));
    }


    /**
     * Allows a user to join an organization as an employee.
     *
     * @param userId             The ID of the user joining.
     * @param orgId              The ID of the organization to join.
     * @param employeeJoinOrgDto DTO containing additional employment details.
     * @return The DTO of the newly created employee record.
     * @throws ResourceNotFoundException if the user or organization is not found.
     * @throws IllegalStateException     if the user is already an employee of the organization.
     */
    @Transactional
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
        employee.setAddress(user.getAddress());
        employee.setPhoneNumber(user.getPhone());
        employee.setEmailAddress(user.getEmail());
        employee.setDateOfBirth(user.getDateOfBirth());

        Employee savedEmployee = employeeRepository.save(employee);

        return EmployeeDtoConvertor.convertEmployeeToDto(savedEmployee);
    }

    /**
     * Creates a new employee record.
     *
     * @param employeeCreationDto DTO containing the details for the new employee.
     * @return The DTO of the newly created employee.
     * @throws ResourceNotFoundException if the organization specified in the DTO does not exist.
     */
    @Transactional
    public EmployeeDto addEmployee(EmployeeCreationDto employeeCreationDto) {
        Employee employee = new Employee();
        Organization organization = organizationRepository.findOrganizationByOrganizationName(employeeCreationDto.getOrganizationName())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with name: " + employeeCreationDto.getOrganizationName()));
        return EmployeeObjectMapper(employeeCreationDto, employee, organization);
    }


    /**
     * Retrieves a list of all employees.
     *
     * @return A list of all employee DTOs.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> displayAllEmployees() {
        return employeeRepository.findAll().stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a single employee by their ID.
     *
     * @param id The ID of the employee to retrieve.
     * @return The employee DTO.
     * @throws ResourceNotFoundException if no employee with the given ID is found.
     */
    @Transactional(readOnly = true)
    public EmployeeDto displayAnEmployee(Long id) {
        EmployeeDto employeeDto = employeeRepository.findById(id)
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .orElse(null);
        if(employeeDto==null) {
            throw new ResourceNotFoundException("Employee not found with id: "+id);
        } else {
            return employeeDto;
        }
    }

    /**
     * Retrieves all employees for a given organization.
     *
     * @param organizationId The ID of the organization.
     * @return A list of employee DTOs belonging to the specified organization.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> displayEmployeesByOrganization(Long organizationId) {
        return employeeRepository.findEmployeesByOrganization_Id(organizationId).stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toList());
    }

    /**
     * Partially updates an existing employee.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the employee to update.
     * @throws ResourceNotFoundException if no employee with the given ID is found.
     */
    @Transactional
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

    /**
     * Updates multiple employees in a batch.
     *
     * @param updates A list of DTOs containing the updates for each employee.
     */
    @Transactional
    public void batchUpdateEmployees(List<EmployeePatchDto> updates) {
        updates.forEach(update -> {
            partialUpdateAnEmployee(update.getUpdates(), update.getId());
        });
    }

    /**
     * Deletes an employee by their ID.
     *
     * @param id The ID of the employee to delete.
     * @throws ResourceNotFoundException if no employee with the given ID is found.
     */
    @Transactional
    public void deleteAnEmployee(Long id) {
        if (!employeeRepository.existsById(id)) {
            throw new ResourceNotFoundException("Employee not found with id: " + id);
        }
        employeeRepository.deleteById(id);
    }

}