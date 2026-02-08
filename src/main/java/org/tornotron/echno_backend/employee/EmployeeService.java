package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.DtoConversions.EmployeeDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import org.tornotron.echno_backend.common.enums.OrgRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service class for managing employees.
 * Handles business logic related to employee creation, retrieval, updates, and deletion,
 * as well as joining organizations.
 */
@Slf4j
@Service
@Validated
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final KeycloakGroupService keycloakGroupService;

    /**
     * Constructs an EmployeeService with the necessary repositories.
     *
     * @param employeeRepository     The repository for employee data access.
     * @param organizationRepository The repository for organization data access.
     * @param userRepository         The repository for user data access.
     * @param keycloakGroupService   The service for managing Keycloak groups.
     */
    public EmployeeService(EmployeeRepository employeeRepository, OrganizationRepository organizationRepository, UserRepository userRepository, KeycloakGroupService keycloakGroupService) {
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.keycloakGroupService = keycloakGroupService;
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

        if (employeeCreationDto.getManagerId() != null) {
            Employee manager = employeeRepository.findById(employeeCreationDto.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + employeeCreationDto.getManagerId()));
            // Validate manager is from the same organization
            if (!manager.getOrganization().getId().equals(organization.getId())) {
                throw new IllegalArgumentException("Manager must be from the same organization");
            }
            employee.setManager(manager);
        }

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
    public EmployeeDto joinOrganization(Long userId, Long orgId, @Valid EmployeeJoinOrgDto employeeJoinOrgDto) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));

        if (employeeRepository.existsByUserAndOrganization(user, org)) {
            throw new IllegalStateException("User already employed in this organization");
        }

        Employee employee = new Employee();

        employee.setUser(user);
        employee.setOrganization(org);

        employee.setDesignation(employeeJoinOrgDto.getDesignation());
        employee.setDepartment(employeeJoinOrgDto.getDepartment());
        employee.setJoiningDate(employeeJoinOrgDto.getJoiningDate());
        employee.setSalary(employeeJoinOrgDto.getSalary());

        if (employeeJoinOrgDto.getManagerId() != null) {
            Employee manager = employeeRepository.findById(employeeJoinOrgDto.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + employeeJoinOrgDto.getManagerId()));
            // Validate manager is from the same organization
            if (!manager.getOrganization().getId().equals(org.getId())) {
                throw new IllegalArgumentException("Manager must be from the same organization");
            }
            employee.setManager(manager);
        }

        employee.setShiftTiming(employeeJoinOrgDto.getShiftTiming());
        employee.setStatus(EmployeeStatus.valueOf(employeeJoinOrgDto.getStatus()));

        employee.setEmployeeName(user.getName());
        employee.setGender(user.getGender());
        employee.setAddress(user.getAddress());
        employee.setPhoneNumber(user.getPhone());
        employee.setEmailAddress(user.getEmail());
        employee.setDateOfBirth(user.getDateOfBirth());

        Employee savedEmployee = employeeRepository.save(employee);

        // Add user to Keycloak group - if this fails, the transaction will rollback
        try {
            keycloakGroupService.addUserToOrganization(
                    user.getKeycloakId(),
                    org.getId().toString()
            );
        } catch (Exception e) {
            log.error("Failed to add user {} to Keycloak group for organization {}: {}",
                    user.getKeycloakId(), org.getId(), e.getMessage());
            throw new RuntimeException("Failed to add user to organization in Keycloak: " + e.getMessage(), e);
        }

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
        partialUpdateAnEmployee(updates, employee);
        employeeRepository.save(employee);
    }

    private void partialUpdateAnEmployee(Map<String, Object> updates, Employee employee) {
        updates.forEach((key, value) -> {
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
                case "managerId":
                    Long managerId = ((Number) value).longValue();
                    Employee manager = employeeRepository.findById(managerId)
                            .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + managerId));
                    validateManager(employee, manager);
                    employee.setManager(manager);
                    break;
            }
        });
    }

    /**
     * Validates that a manager assignment is valid.
     * Checks that:
     * - The employee is not being assigned as their own manager
     * - The manager belongs to the same organization as the employee
     * - No circular reference is created in the management hierarchy
     *
     * @param employee The employee to assign a manager to.
     * @param manager  The proposed manager.
     * @throws IllegalArgumentException if validation fails.
     */
    private void validateManager(Employee employee, Employee manager) {
        if (employee.getId() != null && manager.getId().equals(employee.getId())) {
            throw new IllegalArgumentException("Employee cannot be their own manager");
        }
        if (!manager.getOrganization().getId().equals(employee.getOrganization().getId())) {
            throw new IllegalArgumentException("Manager must be from the same organization");
        }
        // Check for circular reference in management chain
        if (employee.getId() != null && wouldCreateCircularReference(employee, manager)) {
            throw new IllegalArgumentException("This assignment would create a circular reference in the management hierarchy");
        }
    }

    /**
     * Checks if assigning the given manager to the employee would create a circular reference.
     * A circular reference occurs when the proposed manager (or any manager up the chain)
     * reports to the employee being updated.
     *
     * @param employee The employee to assign a manager to.
     * @param manager  The proposed manager.
     * @return true if a circular reference would be created, false otherwise.
     */
    private boolean wouldCreateCircularReference(Employee employee, Employee manager) {
        Employee current = manager;
        while (current.getManager() != null) {
            if (current.getManager().getId().equals(employee.getId())) {
                return true;
            }
            current = current.getManager();
        }
        return false;
    }

    /**
     * Assigns a manager to an employee.
     *
     * @param employeeId The ID of the employee.
     * @param managerId  The ID of the manager to assign.
     * @return The updated employee DTO.
     * @throws ResourceNotFoundException if the employee or manager is not found.
     * @throws IllegalArgumentException  if validation fails.
     */
    @Transactional
    public EmployeeDto assignManager(Long employeeId, Long managerId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
        Employee manager = employeeRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + managerId));

        validateManager(employee, manager);
        employee.setManager(manager);

        return EmployeeDtoConvertor.convertEmployeeToDto(employeeRepository.save(employee));
    }

    /**
     * Removes the manager assignment from an employee.
     *
     * @param employeeId The ID of the employee.
     * @return The updated employee DTO.
     * @throws ResourceNotFoundException if the employee is not found.
     */
    @Transactional
    public EmployeeDto removeManager(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        employee.setManager(null);

        return EmployeeDtoConvertor.convertEmployeeToDto(employeeRepository.save(employee));
    }

    /**
     * Retrieves all direct subordinates of a manager.
     *
     * @param managerId The ID of the manager.
     * @return A list of employee DTOs who report to the specified manager.
     * @throws ResourceNotFoundException if the manager is not found.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getDirectSubordinates(Long managerId) {
        if (!employeeRepository.existsById(managerId)) {
            throw new ResourceNotFoundException("Manager not found with id: " + managerId);
        }
        return employeeRepository.findByManager_Id(managerId).stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toList());
    }

    /**
     * Updates multiple employees in a batch.
     *
     * @param updates A list of DTOs containing the updates for each employee.
     */
    @Transactional
    public void batchUpdateEmployees(List<EmployeePatchDto> updates) {
        List<Long> employeeIds = updates.stream().map(EmployeePatchDto::getId).collect(Collectors.toList());
        List<Employee> employees = employeeRepository.findAllById(employeeIds);

        Map<Long, Employee> employeeMap = employees.stream().collect(Collectors.toMap(Employee::getId, employee -> employee));

        updates.forEach(update -> {
            Employee employee = employeeMap.get(update.getId());
            if (employee != null) {
                partialUpdateAnEmployee(update.getUpdates(), employee);
            }
        });

        employeeRepository.saveAll(employees);
    }

    /**
     * Deletes an employee by their ID.
     *
     * @param id The ID of the employee to delete.
     * @throws ResourceNotFoundException if no employee with the given ID is found.
     */
    @Transactional
    public void deleteAnEmployee(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));

        if (employee.getUser() != null && employee.getUser().getKeycloakId() != null) {
            try {
                keycloakGroupService.removeUserFromOrganization(
                        employee.getUser().getKeycloakId(),
                        employee.getOrganization().getId().toString()
                );
            } catch (Exception e) {
                log.error("Failed to remove user {} from Keycloak group for organization {}: {}",
                        employee.getUser().getKeycloakId(),
                        employee.getOrganization().getId(),
                        e.getMessage());
            }
        }

        employeeRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> readAllTheManagers() {
        return employeeRepository.findEmployeesByIsManager(true)
                .stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> readAllTheManagersByOrganizationId(Long organizationId) {
        return employeeRepository.findEmployeesByOrganization_IdAndIsManager(organizationId,true)
                .stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeDto assignOrgRole(Long employeeId, OrgRole role) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        employee.getOrgRoles().add(role);
        Employee savedEmployee = employeeRepository.save(employee);

        try {
            keycloakGroupService.assignOrgRole(
                    employee.getUser().getKeycloakId(),
                    employee.getOrganization().getId().toString(),
                    role
            );
        } catch (Exception e) {
            log.error("Failed to assign role {} to employee {} in Keycloak: {}",
                    role, employeeId, e.getMessage());
            throw new RuntimeException("Failed to assign role in Keycloak: " + e.getMessage(), e);
        }

        return EmployeeDtoConvertor.convertEmployeeToDto(savedEmployee);
    }

    @Transactional
    public EmployeeDto removeOrgRole(Long employeeId, OrgRole role) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        employee.getOrgRoles().remove(role);
        Employee savedEmployee = employeeRepository.save(employee);

        try {
            keycloakGroupService.removeOrgRole(
                    employee.getUser().getKeycloakId(),
                    employee.getOrganization().getId().toString(),
                    role
            );
        } catch (Exception e) {
            log.error("Failed to remove role {} from employee {} in Keycloak: {}",
                    role, employeeId, e.getMessage());
            throw new RuntimeException("Failed to remove role in Keycloak: " + e.getMessage(), e);
        }

        return EmployeeDtoConvertor.convertEmployeeToDto(savedEmployee);
    }

    @Transactional(readOnly = true)
    public Set<OrgRole> getOrgRoles(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
        return employee.getOrgRoles();
    }

}