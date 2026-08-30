package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeLookupDto;
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
    private final EmployeeMapper employeeMapper;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final ShiftTimingRepository shiftTimingRepository;

    /**
     * Constructs an EmployeeService with the necessary repositories.
     *
     * @param employeeRepository     The repository for employee data access.
     * @param organizationRepository The repository for organization data access.
     * @param userRepository         The repository for user data access.
     * @param keycloakGroupService   The service for managing Keycloak groups.
     * @param employeeHierarchyService The service owning the reporting hierarchy.
     */
    public EmployeeService(EmployeeRepository employeeRepository, OrganizationRepository organizationRepository, UserRepository userRepository, KeycloakGroupService keycloakGroupService, EmployeeMapper employeeMapper, EmployeeHierarchyService employeeHierarchyService, ShiftTimingRepository shiftTimingRepository) {
        this.employeeRepository = employeeRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.keycloakGroupService = keycloakGroupService;
        this.employeeMapper = employeeMapper;
        this.employeeHierarchyService = employeeHierarchyService;
        this.shiftTimingRepository = shiftTimingRepository;
    }

    /**
     * Resolves a structured shift for an employee. A null id yields no shift. A
     * non-null id is looked up scoped to the given organization, and an id that does
     * not belong to that organization is rejected so a shift cannot be borrowed
     * across tenants.
     *
     * @param shiftTimingId The id of the shift to assign, or null for none.
     * @param organization  The organization the employee belongs to.
     * @return The resolved shift, or null when no id was supplied.
     * @throws ResourceNotFoundException if the id does not resolve within the organization.
     */
    private ShiftTiming resolveShiftTiming(Long shiftTimingId, Organization organization) {
        if (shiftTimingId == null) {
            return null;
        }
        return shiftTimingRepository.findByIdAndOrganization_Id(shiftTimingId, organization.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shift timing with ID " + shiftTimingId + " was not found in this organization"));
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
            Employee manager = employeeRepository.findByIdAndOrganizationId(employeeCreationDto.getManagerId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Manager with ID " + employeeCreationDto.getManagerId() + " was not found in this organization"));
            // Validate manager is from the same organization
            if (!manager.getOrganization().getId().equals(organization.getId())) {
                throw new IllegalArgumentException("Manager must be from the same organization");
            }
            employee.setManager(manager);
        }

        employee.setShiftTiming(resolveShiftTiming(employeeCreationDto.getShiftTimingId(), organization));

        return employeeMapper.toDto(employeeRepository.save(employee));
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
                .orElseThrow(() -> new ResourceNotFoundException("User with ID " + userId + " was not found"));

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        if (employeeRepository.existsByUserAndOrganization(user, org)) {
            throw new IllegalStateException("User " + userId + " is already an employee of organization " + orgId);
        }

        Employee employee = new Employee();

        employee.setUser(user);
        employee.setOrganization(org);

        employee.setDesignation(employeeJoinOrgDto.getDesignation());
        employee.setDepartment(employeeJoinOrgDto.getDepartment());
        employee.setJoiningDate(employeeJoinOrgDto.getJoiningDate());
        employee.setSalary(employeeJoinOrgDto.getSalary());
        employee.setEmployeeId(employeeJoinOrgDto.getEmployeeId());

        if (employeeJoinOrgDto.getManagerId() != null) {
            Employee manager = employeeRepository.findByIdAndOrganizationId(employeeJoinOrgDto.getManagerId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Manager with ID " + employeeJoinOrgDto.getManagerId() + " was not found in this organization"));
            // Validate manager is from the same organization
            if (!manager.getOrganization().getId().equals(org.getId())) {
                throw new IllegalArgumentException("Manager must be from the same organization");
            }
            employee.setManager(manager);
        }

        employee.setShiftTiming(resolveShiftTiming(employeeJoinOrgDto.getShiftTimingId(), org));
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
            throw new RuntimeException(
                    "Failed to add user " + userId + " to organization " + orgId + " in Keycloak: " + e.getMessage(), e);
        }

        return employeeMapper.toDto(savedEmployee);
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
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization with name '" + employeeCreationDto.getOrganizationName() + "' was not found"));
        return EmployeeObjectMapper(employeeCreationDto, employee, organization);
    }


    /**
     * Retrieves employees as a minimal, non-sensitive lookup projection for pickers.
     * Unlike {@link #displayAllEmployees(int, int, String, String, String)} this exposes
     * no contact details, salary or personal data, so it can be read by any tenant member.
     *
     * <p>A picker feed is a search-and-limit read rather than a whole-table read: the
     * caller narrows with {@code search} and takes only as many rows as the widget can
     * show. The limit is clamped to {@link UnpagedResultCap#MAX_ROWS} so no caller can
     * ask for the whole table by passing a large number.
     *
     * <p>The search matches only what the picker displays, the name and the human-facing
     * employee id. It deliberately does not match email or phone, which the restricted
     * listing does: matching a contact detail would turn a feed any member may read into
     * a way of confirming a guessed address or number against a returned identity.
     *
     * @param search Case-insensitive substring matched against the employee name or the
     *               human-facing employee id, or null for no filter.
     * @param limit  Most rows to return, clamped to at least one and at most
     *               {@link UnpagedResultCap#MAX_ROWS}.
     * @return A single page of lookup projections ordered by name, carrying the true
     *         match count so a caller can tell it narrowed too little.
     */
    @Transactional(readOnly = true)
    public Page<EmployeeLookupDto> lookupEmployees(String search, int limit) {
        int size = Math.min(Math.max(limit, 1), UnpagedResultCap.MAX_ROWS);
        Pageable pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "employeeName"));
        String searchTerm = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        return employeeRepository.searchForLookup(searchTerm, pageable)
                .map(employee -> new EmployeeLookupDto(
                        employee.getId(),
                        employee.getEmployeeId(),
                        employee.getEmployeeName(),
                        employee.getDesignation(),
                        employee.getStatus(),
                        employee.getOrganization() != null ? employee.getOrganization().getId() : null));
    }

    /**
     * Retrieves a page of employees, ordered alphabetically by name.
     *
     * <p>Every filter is optional: pass null or blank to leave that filter off.
     *
     * @param pageNo     Zero-based page index.
     * @param pageSize   Number of employees per page.
     * @param search     Case-insensitive substring to match against the employee, or null for no
     *                   name filter.
     * @param status     {@code EmployeeStatus} name to restrict to, or null for any status.
     * @param department Department to restrict to, or null for any department.
     * @return A page of employee DTOs.
     */
    @Transactional(readOnly = true)
    public Page<EmployeeDto> displayAllEmployees(int pageNo, int pageSize, String search, String status, String department) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "employeeName"));
        String searchTerm = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String departmentTerm = (department == null || department.isBlank()) ? null : department;
        EmployeeStatus statusTerm = (status == null || status.isBlank()) ? null : EmployeeStatus.valueOf(status);
        return employeeRepository.search(searchTerm, statusTerm, departmentTerm, pageable)
                .map(employee -> employeeMapper.toDto(employee));
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
        EmployeeDto employeeDto = employeeRepository.findByIdAndOrganizationId(id,TenantContext.getCurrentOrgId())
                .map(employee -> employeeMapper.toDto(employee))
                .orElse(null);
        if(employeeDto == null) {
            throw new ResourceNotFoundException("Employee with ID " + id + " was not found in this organization");
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
                .map(employee -> employeeMapper.toDto(employee))
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
        Employee employee = employeeRepository.findByIdAndOrganizationId(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + id + " was not found in this organization"));
        partialUpdateAnEmployee(updates, employee);
        employeeRepository.save(employee);
    }

    private void partialUpdateAnEmployee(Map<String, Object> updates, Employee employee) {
        updates.forEach((key, value) -> {
            switch (key) {
                case "employeeId":
                    employee.setEmployeeId((String) value);
                    break;
                case "status":
                    employee.setStatus(EmployeeStatus.valueOf((String) value));
                    break;
                case "employeeName":
                    employee.setEmployeeName((String) value);
                    break;
                case "designation":
                    employee.setDesignation((String) value);
                    break;
                case "joiningDate":
                    employee.setJoiningDate(DateConversion.parseLocalDateTime(value));
                    break;
                case "phoneNumber":
                    employee.setPhoneNumber((String) value);
                    break;
                case "salary":
                    employee.setSalary((Double) value);
                    break;
                case "shiftTimingId":
                    if (value == null) {
                        employee.setShiftTiming(null);
                    } else {
                        Long shiftTimingId = ((Number) value).longValue();
                        employee.setShiftTiming(resolveShiftTiming(shiftTimingId, employee.getOrganization()));
                    }
                    break;
                case "department":
                    employee.setDepartment((String) value);
                    break;
                case "emailAddress":
                    employee.setEmailAddress((String) value);
                    break;
                case "dateOfBirth":
                    employee.setDateOfBirth(DateConversion.parseLocalDateTime(value));
                    break;
                case "managerId":
                    Long managerId = ((Number) value).longValue();
                    Employee manager = employeeRepository.findByIdAndOrganizationId(managerId,TenantContext.getCurrentOrgId())
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "Manager with ID " + managerId + " was not found in this organization"));
                    employeeHierarchyService.validateManager(employee, manager);
                    employee.setManager(manager);
                    break;
                default:
                    // Nothing is dropped on purpose here. The keys echno-core sends that this
                    // endpoint has no field for (address, experience, gender, organizationId,
                    // qualification, skills) all arrive only when a caller sets them, and five of
                    // them are real columns on User reachable through PATCH /user/web/{id}. A
                    // warning naming the key is exactly the signal wanted. See echno-core#57.
                    PartialUpdateKeys.reportUnknown(log, "employee", employee.getId(), key);
                    break;
            }
        });
    }

    /**
     * Assigns a manager to an employee. Delegates to {@link EmployeeHierarchyService}.
     */
    public EmployeeDto assignManager(Long employeeId, Long managerId) {
        return employeeHierarchyService.assignManager(employeeId, managerId);
    }

    /**
     * Removes the manager assignment from an employee. Delegates to
     * {@link EmployeeHierarchyService}.
     */
    public EmployeeDto removeManager(Long employeeId) {
        return employeeHierarchyService.removeManager(employeeId);
    }

    /**
     * Retrieves all direct subordinates of a manager. Delegates to
     * {@link EmployeeHierarchyService}.
     */
    public List<EmployeeDto> getDirectSubordinates(Long managerId) {
        return employeeHierarchyService.getDirectSubordinates(managerId);
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
        Employee employee = employeeRepository.findByIdAndOrganizationId(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + id + " was not found in this organization"));

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

    /** Lists every employee that acts as a manager. Delegates to {@link EmployeeHierarchyService}. */
    public List<EmployeeDto> readAllTheManagers() {
        return employeeHierarchyService.readAllTheManagers();
    }

    /** Lists managers scoped to one organization. Delegates to {@link EmployeeHierarchyService}. */
    public List<EmployeeDto> readAllTheManagersByOrganizationId(Long organizationId) {
        return employeeHierarchyService.readAllTheManagersByOrganizationId(organizationId);
    }

    @Transactional
    public EmployeeDto assignOrgRole(Long employeeId, OrgRole role) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));

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
            throw new RuntimeException(
                    "Failed to assign role '" + role + "' to employee " + employeeId + " in Keycloak: " + e.getMessage(), e);
        }

        return employeeMapper.toDto(savedEmployee);
    }

    @Transactional
    public EmployeeDto removeOrgRole(Long employeeId, OrgRole role) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));

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
            throw new RuntimeException(
                    "Failed to remove role '" + role + "' from employee " + employeeId + " in Keycloak: " + e.getMessage(), e);
        }

        return employeeMapper.toDto(savedEmployee);
    }

    @Transactional(readOnly = true)
    public Set<OrgRole> getOrgRoles(Long employeeId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));
        return employee.getOrgRoles();
    }

}