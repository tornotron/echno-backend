package org.tornotron.echno_backend.employee;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeLookupDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;
import org.tornotron.echno_backend.employee.dto.OrgRoleAssignmentDto;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/employee/web")
@Tag(
        name = "Employees",
        description = "Web-client twin of the employee endpoints. Adds joining an organization as an "
                + "employee, a minimal lookup list for pickers, paginated and filtered listing, manager "
                + "assignment, subordinate and manager lookups, and org-role assignment, alongside the "
                + "same read, update and delete operations as the base employee API."
)
public class EmployeeControllerWeb {

    private final EmployeeService employeeService;

    /**
     * Constructs an EmployeeController with the given EmployeeService.
     *
     * @param employeeService The service for handling employee-related business logic.
     */
    public EmployeeControllerWeb(EmployeeService employeeService) {
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
    @PostMapping("/joinOrganization/userId/{userId}/organizationId/{orgId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Add a user to an organization as an employee",
            description = "Creates an employee record linking the given user to the given organization, "
                    + "with the employment details supplied in the request body."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee record created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user or organization with the given id")
    })
    public ResponseEntity<EmployeeDto> joinOrganization(@PathVariable Long userId, @PathVariable Long orgId, @Valid @RequestBody EmployeeJoinOrgDto employeeJoinOrgDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.joinOrganization(userId, orgId, employeeJoinOrgDto));
    }

//    /**
//     * Creates a new employee.
//     *
//     * @param employeeCreationDto DTO containing the details for the new employee.
//     * @return A {@link ResponseEntity} with the created employee's DTO and HTTP status 201 (Created).
//     */
//    @PostMapping
//    @PreAuthorize("hasAuthority('employee:create') or hasAuthority('employee:admin')")
//    public ResponseEntity<EmployeeDto> createEmployee(@Valid @RequestBody EmployeeCreationDto employeeCreationDto) {
//        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.addEmployee(employeeCreationDto));
//    }

    /**
     * Retrieves a list of all employees.
     *
     * @return A {@link ResponseEntity} containing the list of employee DTOs and HTTP status 200 (OK).
     */
    /**
     * Minimal, non-sensitive employee list for populating pickers. Readable by any
     * tenant member; the full employee reads below are restricted to management roles.
     */
    @GetMapping("/lookup")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List employees for pickers",
            description = "Returns a minimal, non-sensitive list of employees (id and name) for "
                    + "populating selection widgets. Readable by any tenant member."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeLookupDto>> lookupEmployees() {
        return new ResponseEntity<>(employeeService.lookupEmployees(), HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List all employees",
            description = "Returns every employee in the current tenant, with their full details."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeDto>> readAllEmployees() {
        return new ResponseEntity<>(employeeService.displayAllEmployees(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List employees, paginated and filtered",
            description = "Returns a single page of employees, optionally filtered by a free-text "
                    + "search on name, employment status, or department."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<EmployeeDto>> readAllEmployeesPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String department) {
        return new ResponseEntity<>(employeeService.displayAllEmployees(pageNo, pageSize, search, status, department), HttpStatus.OK);
    }

    /**
     * Retrieves a single employee by their ID.
     *
     * @param id The ID of the employee to retrieve.
     * @return A {@link ResponseEntity} containing the employee DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "Get an employee by id",
            description = "Returns a single employee's full details."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<EmployeeDto> readAnEmployee(@PathVariable Long id) {
        EmployeeDto employee = employeeService.displayAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(employee);
    }


//    /**
//     * Retrieves all employees belonging to a specific organization.
//     *
//     * @param id The ID of the organization.
//     * @return A {@link ResponseEntity} containing a list of employee DTOs for the specified organization and HTTP status 200 (OK).
//     */
//    @GetMapping("/organization/{id}")
//    @PreAuthorize("hasAuthority('employee:read') or hasAuthority('employee:admin')")
//    public ResponseEntity<List<EmployeeDto>> readEmployeesByOrganizationId(@PathVariable Long id) {
//        return ResponseEntity.status(HttpStatus.OK).body(employeeService.displayEmployeesByOrganization(id));
//    }

    /**
     * Partially updates an existing employee.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the employee to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("{id}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Partially update an employee",
            description = "Applies the given field updates to the employee with the given id. Callable "
                    + "by the employee themselves or by a system or HR admin."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the updated fields failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee nor a system or HR admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<ApiResponse> partialUpdateAnEmployee(@RequestBody Map<String,Object> updates, @PathVariable Long id) {
        employeeService.partialUpdateAnEmployee(updates,id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" updated"));
    }

//    /**
//     * Updates multiple employees in a batch.
//     *
//     * @param updates A list of DTOs containing the updates for each employee.
//     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
//     */
//    @PatchMapping("/batch")
//    public ResponseEntity<ApiResponse> batchUpdateEmployees(@Valid @RequestBody List<EmployeePatchDto> updates) {
//        employeeService.batchUpdateEmployees(updates);
//        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
//    }

    /**
     * Deletes an employee by their ID.
     *
     * @param id The ID of the employee to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Delete an employee",
            description = "Deletes the employee record with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<ApiResponse> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" has been deleted"));
    }

    /**
     * Assigns a manager to an employee.
     *
     * @param employeeId The ID of the employee.
     * @param managerId  The ID of the manager to assign.
     * @return A {@link ResponseEntity} containing the updated employee DTO and HTTP status 200 (OK).
     */
    @PutMapping("/employeeId/{employeeId}/managerId/{managerId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
    @Operation(
            summary = "Assign a manager",
            description = "Sets the given manager as the reporting manager of the given employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Manager assigned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee or manager with the given id")
    })
    public ResponseEntity<EmployeeDto> assignManager(@PathVariable Long employeeId, @PathVariable Long managerId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.assignManager(employeeId, managerId));
    }

    /**
     * Removes the manager assignment from an employee.
     *
     * @param employeeId The ID of the employee.
     * @return A {@link ResponseEntity} containing the updated employee DTO and HTTP status 200 (OK).
     */
    @DeleteMapping("/employeeId/{employeeId}/manager")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
    @Operation(
            summary = "Remove a manager assignment",
            description = "Clears the reporting manager on the given employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Manager removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<EmployeeDto> removeManager(@PathVariable Long employeeId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.removeManager(employeeId));
    }

    /**
     * Retrieves all direct subordinates of a manager.
     *
     * @param managerId The ID of the manager.
     * @return A {@link ResponseEntity} containing a list of employee DTOs who report to the manager and HTTP status 200 (OK).
     */
    @GetMapping("/managerId/{managerId}/subordinates")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List a manager's direct subordinates",
            description = "Returns every employee who reports directly to the given manager."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subordinates returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No manager with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> getDirectSubordinates(@PathVariable Long managerId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.getDirectSubordinates(managerId));
    }

    @GetMapping("/managers")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List all managers",
            description = "Returns every employee flagged as a manager in the current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Managers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeDto>> getAllTheManagers() {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.readAllTheManagers());
    }

    @GetMapping("/managers/organizationId/{organizationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List managers for an organization",
            description = "Returns every employee flagged as a manager within the given organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Managers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> getAllManagersForAnOrganization(@PathVariable Long organizationId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.readAllTheManagersByOrganizationId(organizationId));
    }

    @PostMapping("/{employeeId}/roles")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
    @Operation(
            summary = "Assign an org role",
            description = "Grants the given organization role to the employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role assigned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<EmployeeDto> assignOrgRole(@PathVariable Long employeeId, @Valid @RequestBody OrgRoleAssignmentDto dto) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.assignOrgRole(employeeId, dto.getRole()));
    }

    @DeleteMapping("/{employeeId}/roles/{role}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
    @Operation(
            summary = "Remove an org role",
            description = "Revokes the given organization role from the employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<EmployeeDto> removeOrgRole(@PathVariable Long employeeId, @PathVariable OrgRole role) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.removeOrgRole(employeeId, role));
    }

    @GetMapping("/{employeeId}/roles")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get an employee's org roles",
            description = "Returns the set of organization roles held by the given employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Roles returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<Set<OrgRole>> getOrgRoles(@PathVariable Long employeeId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.getOrgRoles(employeeId));
    }

}
