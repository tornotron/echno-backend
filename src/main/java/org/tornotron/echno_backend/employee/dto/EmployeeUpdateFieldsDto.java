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
@Schema(description = "Fields a partial employee update may change. Every field is optional; only "
        + "the fields present in the request are applied. Keys not listed here are ignored.")
@Data
public class EmployeeUpdateFieldsDto {

    @Schema(description = "Employee code within the organization.", example = "ECH-0412")
    private String employeeId;

    @Schema(description = "Employment status of the employee.")
    private EmployeeStatus status;

    @Schema(description = "Full name of the employee.", example = "Hrishikesh R")
    private String employeeName;

    @Schema(description = "Job title held.", example = "Site Engineer")
    private String designation;

    @Schema(description = "Date the employee joined.", example = "2024-06-01T00:00:00")
    private LocalDateTime joiningDate;

    @Schema(description = "Contact phone number.", example = "+91 99400 00000")
    private String phoneNumber;

    @Schema(description = "Monthly salary. Must be sent as a decimal number.", example = "62000.0")
    private Double salary;

    @Schema(description = "Id of the shift timing the employee works. Must belong to the employee's "
            + "organization; null clears the assignment.", example = "3")
    private Long shiftTimingId;

    @Schema(description = "Department the employee belongs to.", example = "Execution")
    private String department;

    @Schema(description = "Work email address.", example = "hrishi@echno.xyz")
    private String emailAddress;

    @Schema(description = "Date of birth.", example = "1998-04-12T00:00:00")
    private LocalDateTime dateOfBirth;

    @Schema(description = "Id of the employee's manager. Must belong to the caller's organization, "
            + "and may not introduce a cycle in the reporting line.", example = "7")
    private Long managerId;
}
