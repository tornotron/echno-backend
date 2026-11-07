package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;

/**
 * Keeps {@code employee_org_roles} in step with the assignments, in the same transaction, so the old
 * checks and the matrix never disagree about the same person. Through the entity, so a caller holding
 * the same employee in this session sees the change. Goes when the old role code does.
 */
@Component
public class LegacyRoleMirror {

    private final EmployeeRepository employees;

    public LegacyRoleMirror(EmployeeRepository employees) {
        this.employees = employees;
    }

    void add(Long organizationId, Long employeeId, String legacyRole) {
        employee(organizationId, employeeId).getOrgRoles().add(orgRole(legacyRole));
    }

    void remove(Long organizationId, Long employeeId, String legacyRole) {
        employee(organizationId, employeeId).getOrgRoles().remove(orgRole(legacyRole));
    }

    private Employee employee(Long organizationId, Long employeeId) {
        return employees.findByIdAndOrganizationId(employeeId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId
                        + " was not found in this organization"));
    }

    private static OrgRole orgRole(String legacyRole) {
        return Arrays.stream(OrgRole.values())
                .filter(role -> role.getGroupName().equals(legacyRole))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No old role is named " + legacyRole));
    }
}
