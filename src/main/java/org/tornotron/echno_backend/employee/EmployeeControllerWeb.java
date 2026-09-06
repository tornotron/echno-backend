package org.tornotron.echno_backend.employee;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeLookupDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;
import org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto;
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
    @PreAuthorize("@orgSecurity.isCurrentTenant(#orgId)"
            + " and @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Add a user to an organization as an employee",
            description = "Creates an employee record linking the given user to the organization named "
                    + "in the path, which must be the organization the caller's session is scoped to. "
                    + "Uses the employment details supplied in the request body."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee record created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant, or named an organization that is not it"),
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
                    + "populating selection widgets. Narrow the feed with search, which matches "
                    + "the employee name or the employee id, and size it with limit, which is "
                    + "capped at 500. X-Total-Count carries the true match count and "
                    + "X-Result-Capped is set when rows were left out. Readable by any tenant member."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeLookupDto>> lookupEmployees(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "500") int limit) {
        return UnpagedResultCap.respond(employeeService.lookupEmployees(search, limit));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List all employees",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeDto>> readAllEmployees() {
        return UnpagedResultCap.respond(employeeService.displayAllEmployees(
                0, UnpagedResultCap.MAX_ROWS, null, null, null));
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
            @Valid @ParameterObject PageQuery pageQuery,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String department) {
        return new ResponseEntity<>(employeeService.displayAllEmployees(pageQuery.getPageNo(), pageQuery.getPageSize(), search, status, department), HttpStatus.OK);
    }

    /**
     * Retrieves a single employee by their ID.
     *
     * <p>The self branch is what makes this read at least as open as the {@code PATCH} beside it,
     * which has always been {@code isSelfOrHasAnyOrgRole}. Without it a person could edit their
     * own record and then be refused reading it back, and any screen that shows the record before
     * offering to edit it failed on the read rather than the write. Same shape as the project
     * reads repaired in {@code eec6c37}. Nothing else widens: a member holding no elevated role
     * still cannot open a colleague's record, which carries salary, date of birth and address.
     * See #710.
     *
     * @param id The ID of the employee to retrieve.
     * @return A {@link ResponseEntity} containing the employee DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin', 'project-manager')")
    @Operation(
            summary = "Get an employee by id",
            description = "Returns a single employee's full details. Callable by the employee themselves "
                    + "or by a system admin, HR admin or project manager."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee nor a system admin, HR admin or project manager"),
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
    public ResponseEntity<ApiResponse> partialUpdateAnEmployee(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = EmployeeUpdateFieldsDto.class)))
            @RequestBody Map<String, Object> updates,
            @PathVariable Long id) {
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
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#managerId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "List a manager's direct subordinates",
            description = "Returns every employee who reports directly to the given manager. Callable "
                    + "by that manager, or by a system or HR admin for anybody."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subordinates returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the manager named nor a system or HR admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No manager with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> getDirectSubordinates(@PathVariable Long managerId) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.getDirectSubordinates(managerId));
    }

    @GetMapping("/managers")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List all managers",
            description = "Returns every employee holding a manager organization role in the current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Managers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<EmployeeDto>> getAllTheManagers() {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.readAllTheManagers());
    }

    @GetMapping("/managers/organizationId/{organizationId}")
    @PreAuthorize("@orgSecurity.isCurrentTenant(#organizationId) "
            + "and @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List managers for an organization",
            description = "Returns every employee holding a manager organization role within the given "
                    + "organization, which has to be the caller's own. Identical to the list beside it, "
                    + "which takes the organization from the session instead."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Managers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not entitled to the organization named, or lacks the required role in it"),
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

    /**
     * The roles one colleague holds, readable by any member of the organization.
     *
     * <p>Membership is deliberate rather than left over. Knowing who the site manager or the HR
     * admin is, is how a person finds who to route a request to, and a directory that hides it is
     * worse than one that shows it. The answer crosses no tenant, since {@code Employee} carries
     * the org filter, and grants nothing: reading a role is not holding it. What it does give away
     * is the shape of the organization's permissions to anyone who walks employee ids, which is
     * reconnaissance rather than access, and the ids are already enumerable through the lookup
     * list. #710 raised it and it was closed as a decision on that reasoning. The residual
     * question, whether the full role set per person is the right granularity or whether a
     * "contactable roles" view would serve the directory better, is a product one and is not
     * answered by narrowing this to an admin role.
     */
    @GetMapping("/{employeeId}/roles")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get an employee's org roles",
            description = "Returns the set of organization roles held by the given employee. Open to any "
                    + "member of the organization, since knowing who holds which role is how work is "
                    + "routed."
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
