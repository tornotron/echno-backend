package org.tornotron.echno_backend.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;

import java.time.LocalDateTime;

/**
 * The fields a partial employee update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why these endpoints
 * keep the map at runtime and publish this as their schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code EmployeeService.partialUpdateAnEmployee} actually accepts out of that method's source.
 * The same keys reach the batch endpoint, which routes each element's updates through the same
 * method.
 */
@Schema(description = "Fields a partial employee update may change. "
        + "Every field is optional and an absent field is left untouched. A field this schema "
        + "declares nullable is cleared by sending an explicit null; a field it does not declare "
        + "nullable refuses a null with a 400 rather than clearing. Keys not listed here are "
        + "ignored.")
@Data
public class EmployeeUpdateFieldsDto {

    @Schema(nullable = true, description = "Employee code within the organization.", example = "ECH-0412")
    private String employeeId;

    @Schema(nullable = true, description = "Employment status of the employee.")
    private EmployeeStatus status;

    @Schema(nullable = true, description = "Full name of the employee.", example = "Hrishikesh R")
    private String employeeName;

    @Schema(nullable = true, description = "Job title held.", example = "Site Engineer")
    private String designation;

    @Schema(nullable = true, description = "Date the employee joined.", example = "2024-06-01T00:00:00")
    private LocalDateTime joiningDate;

    @Schema(nullable = true, description = "Contact phone number.", example = "+91 99400 00000")
    private String phoneNumber;

    @Schema(nullable = true, description = "Monthly salary. Must be sent as a decimal number.", example = "62000.0")
    private Double salary;

    @Schema(nullable = true, description = "Id of the shift timing the employee works. Must belong to the employee's "
            + "organization; null clears the assignment.", example = "3")
    private Long shiftTimingId;

    @Schema(nullable = true, description = "Department the employee belongs to.", example = "Execution")
    private String department;

    @Schema(nullable = true, description = "Work email address.", example = "hrishi@echno.xyz")
    private String emailAddress;

    @Schema(nullable = true, description = "Date of birth.", example = "1998-04-12T00:00:00")
    private LocalDateTime dateOfBirth;

    @Schema(nullable = true, description = "Id of the employee's manager. Must belong to the caller's organization, "
            + "and may not introduce a cycle in the reporting line. Send null to detach the "
            + "employee from their manager.", example = "7")
    private Long managerId;
}
