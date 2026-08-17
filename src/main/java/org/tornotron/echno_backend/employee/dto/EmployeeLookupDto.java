package org.tornotron.echno_backend.employee.dto;

/**
 * A minimal, non-sensitive projection of an employee for populating pickers
 * (assignee, inspector, payee, ...). It carries only what a dropdown needs to
 * display and identify an employee, so it can stay readable by any tenant member
 * while the full {@link EmployeeDto} (contact details, salary, personal data) is
 * restricted to management roles.
 */
public record EmployeeLookupDto(
        Long id,
        String employeeId,
        String employeeName,
        String designation,
        Long organizationId
) {
}
