package org.tornotron.echno_backend.employee;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing employees.
 * Provides endpoints for creating, reading, updating, and deleting employees,
 * as well as for users to join organizations.
 */
@RestController
@RequestMapping("/api/v1/employee")
@Validated
@Tag(
        name = "Employees",
        description = "Employee records within an organization, covering personal and employment details, "
                + "reporting line, roles and status. Endpoints let a user join an organization and cover "
                + "creating, browsing, reading, updating and deleting employees. Access is gated by the "
                + "employee authorities, with an admin authority that grants all operations."
)
public class EmployeeController {

    private final EmployeeService employeeService;

    /**
     * Constructs an EmployeeController with the given EmployeeService.
     *
     * @param employeeService The service for handling employee-related business logic.
     */
    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    /**
     * Allows a user to join an organization as an employee.
     *
     * @param userId             The ID of the user joining.
     * @param orgId              The ID of the organization to join.
     * @param employeeJoinOrgDto DTO containing additional employment details.
     * @return A {@link ResponseEntity} with the created employee's DTO and HTTP status 201 (Created).
     */
    @PostMapping("/joinOrganization/{userId}/{orgId}")
    @PreAuthorize("hasAuthority('employee:create') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Add a user to an organization as an employee",
            description = "Creates an employee record that links the given user to the given organization, "
                    + "using the supplied employment details. Returns the created employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee record created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The employment details failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee create or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user or organization with the given id")
    })
    public ResponseEntity<EmployeeDto> joinOrganization(@PathVariable Long userId, @PathVariable Long orgId, @Valid @RequestBody EmployeeJoinOrgDto employeeJoinOrgDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.joinOrganization(userId, orgId, employeeJoinOrgDto));
    }

    /**
     * Creates a new employee.
     *
     * @param employeeCreationDto DTO containing the details for the new employee.
     * @return A {@link ResponseEntity} with the created employee's DTO and HTTP status 201 (Created).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('employee:create') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Create an employee",
            description = "Creates an employee from the supplied personal and employment details, and "
                    + "returns the created employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request body failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee create or admin authority")
    })
    public ResponseEntity<EmployeeDto> createEmployee(@Valid @RequestBody EmployeeCreationDto employeeCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.addEmployee(employeeCreationDto));
    }

    /**
     * Retrieves a list of all employees.
     *
     * @return A {@link ResponseEntity} containing the list of employee DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('employee:read') or hasAuthority('employee:admin')")
    @Operation(
            summary = "List all employees",
            description = "Returns every employee the caller is permitted to see."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee read or admin authority")
    })
    public ResponseEntity<List<EmployeeDto>> readAllEmployees() {
        return new ResponseEntity<>(employeeService.displayAllEmployees(),HttpStatus.OK);
    }

    /**
     * Retrieves a single employee by their ID.
     *
     * @param id The ID of the employee to retrieve.
     * @return A {@link ResponseEntity} containing the employee DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("hasAuthority('employee:read') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Get an employee by id",
            description = "Returns a single employee including their personal details, roles and status."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<EmployeeDto> readAnEmployee(@PathVariable Long id) {
        EmployeeDto employee = employeeService.displayAnEmployee(id);
       return ResponseEntity.status(HttpStatus.OK).body(employee);
    }

    
    /**
     * Retrieves all employees belonging to a specific organization.
     *
     * @param id The ID of the organization.
     * @return A {@link ResponseEntity} containing a list of employee DTOs for the specified organization and HTTP status 200 (OK).
     */
    @GetMapping("/organization/{id}")
    @PreAuthorize("(hasAuthority('employee:read') and @orgSecurity.isMember(#id)) or hasAuthority('employee:admin')")
    @Operation(
            summary = "List employees in an organization",
            description = "Returns the employees belonging to the given organization. A non-admin caller "
                    + "must be a member of that organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the organization and lacks the employee admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> readEmployeesByOrganizationId(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.displayEmployeesByOrganization(id));
    }

    /**
     * Partially updates an existing employee.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the employee to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("{id}")
    @PreAuthorize("hasAuthority('employee:update') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Partially update an employee",
            description = "Applies the supplied map of fields to the employee with the given id, "
                    + "changing only the fields present in the request."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the supplied fields is not valid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<ApiResponse> partialUpdateAnEmployee(@RequestBody Map<String,Object> updates,@PathVariable Long id) {
        employeeService.partialUpdateAnEmployee(updates,id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" updated"));
    }

    /**
     * Updates multiple employees in a batch.
     *
     * @param updates A list of DTOs containing the updates for each employee.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('employee:update') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Batch update employees",
            description = "Applies partial updates to several employees in one call. Each entry names an "
                    + "employee id and the map of fields to change on that employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee update or admin authority")
    })
    public ResponseEntity<ApiResponse> batchUpdateEmployees(@Valid @RequestBody List<EmployeePatchDto> updates) {
        employeeService.batchUpdateEmployees(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    /**
     * Deletes an employee by their ID.
     *
     * @param id The ID of the employee to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('employee:delete') or hasAuthority('employee:admin')")
    @Operation(
            summary = "Delete an employee",
            description = "Deletes the employee with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<ApiResponse> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" has been deleted"));
    }
}