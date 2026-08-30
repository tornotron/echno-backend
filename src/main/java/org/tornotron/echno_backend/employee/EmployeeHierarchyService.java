package org.tornotron.echno_backend.employee;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Owns the employee reporting hierarchy: assigning and removing an employee's
 * manager, validating a proposed manager (same organization, not self, no cycle
 * in the chain), and reading subordinates and managers.
 *
 * Extracted from {@link EmployeeService}, which keeps employee CRUD, join, and
 * role/Keycloak concerns and delegates the hierarchy operations here.
 * {@link #validateManager} is public because {@link EmployeeService}'s patch
 * path validates a manager change through it.
 */
@Service
public class EmployeeHierarchyService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;

    public EmployeeHierarchyService(EmployeeRepository employeeRepository, EmployeeMapper employeeMapper) {
        this.employeeRepository = employeeRepository;
        this.employeeMapper = employeeMapper;
    }

    /**
     * Validates that a manager assignment is valid. Checks that the employee is
     * not their own manager, the manager is in the same organization, and no
     * circular reference is created in the management chain.
     *
     * @throws IllegalArgumentException if validation fails.
     */
    public void validateManager(Employee employee, Employee manager) {
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
     * @throws ResourceNotFoundException if the employee or manager is not found.
     * @throws IllegalArgumentException  if validation fails.
     */
    @Transactional
    public EmployeeDto assignManager(Long employeeId, Long managerId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));
        Employee manager = employeeRepository.findByIdAndOrganizationId(managerId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Manager with ID " + managerId + " was not found in this organization"));

        validateManager(employee, manager);
        employee.setManager(manager);

        return employeeMapper.toDto(employeeRepository.save(employee));
    }

    /**
     * Removes the manager assignment from an employee.
     *
     * @throws ResourceNotFoundException if the employee is not found.
     */
    @Transactional
    public EmployeeDto removeManager(Long employeeId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));

        employee.setManager(null);

        return employeeMapper.toDto(employeeRepository.save(employee));
    }

    /**
     * Retrieves all direct subordinates of a manager.
     *
     * @throws ResourceNotFoundException if the manager is not found.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getDirectSubordinates(Long managerId) {
        if (!employeeRepository.existsByIdAndOrganization_Id(managerId,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Manager with ID " + managerId + " was not found in this organization");
        }
        return employeeRepository.findByManager_Id(managerId).stream()
                .map(employee -> employeeMapper.toDto(employee))
                .collect(Collectors.toList());
    }

    /**
     * Lists the employees who manage: the holders of an organization role in
     * {@link OrgRole#getManagerRoles()}.
     *
     * <p>The role is what decides this, because the role is what the authorization layer
     * already reads. It comes from the Keycloak group on the caller's token, so a manager
     * list built from roles is the same set of people the {@code @PreAuthorize} checks let
     * through.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> readAllTheManagers() {
        return employeeRepository.findEmployeesByOrgRoles(OrgRole.getManagerRoles())
                .stream()
                .map(employee -> employeeMapper.toDto(employee))
                .collect(Collectors.toList());
    }

    /**
     * The same list, narrowed to one organization.
     *
     * <p>This used to read an {@code is_manager} boolean on the employee row, which nothing
     * ever set: it was false on every row on staging, so the endpoint returned an empty list
     * for every organization while the unscoped read above returned the right people. Both
     * now answer from the org roles.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> readAllTheManagersByOrganizationId(Long organizationId) {
        return employeeRepository
                .findEmployeesByOrganizationIdAndOrgRoles(organizationId, OrgRole.getManagerRoles())
                .stream()
                .map(employee -> employeeMapper.toDto(employee))
                .collect(Collectors.toList());
    }
}
