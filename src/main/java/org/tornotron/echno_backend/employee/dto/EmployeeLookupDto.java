package org.tornotron.echno_backend.employee.dto;

import org.tornotron.echno_backend.employee.enums.EmployeeStatus;

/**
 * A minimal, non-sensitive projection of an employee for populating pickers
 * (assignee, inspector, payee, ...). It carries only what a dropdown needs to
 * display, identify and filter an employee (including active/inactive status), so
 * it can stay readable by any tenant member while the full {@link EmployeeDto}
 * (contact details, salary, personal data) is restricted to management roles.
 */
public record EmployeeLookupDto(
        Long id,
        String employeeId,
        String employeeName,
        String designation,
        EmployeeStatus status,
        Long organizationId
) {
}
