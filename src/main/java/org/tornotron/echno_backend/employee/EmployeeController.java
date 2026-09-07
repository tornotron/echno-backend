package org.tornotron.echno_backend.employee;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto;

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
     * Adds a user to the caller's own organization as an employee.
     *
     * <p>This is an administrative action, not the onboarding route. A person with no membership
     * yet joins by redeeming an invite code at
     * {@code POST /api/v1/invitation/web/validate/userId/{userId}}, which is guarded by
     * {@code @orgSecurity.isSelfUser} precisely because the redeemer has no tenant, and which
     * reaches {@link EmployeeService#joinOrganization} in process rather than through this route.
     * Nothing about onboarding passes through here, so binding the guard to an organization
     * costs that flow nothing.
     *
     * <p>{@code employee:create} and {@code employee:admin} are Keycloak resource permissions
     * carried on the RPT, and {@code docs/org-scoped-roles.md} calls them global: they say what
     * the holder may do and nothing at all about where. On a route that names an organization in
     * its path and then loads {@link org.tornotron.echno_backend.organization.Organization} by
     * that id, "what" on its own is not an answer. The tenant root is the one entity neither
     * ambient defence covers, so the id has to be checked here or not at all, which is what
     * {@code isCurrentTenant} does.
     *
     * @param userId             The ID of the user joining.
     * @param orgId              The organization named by the caller, which must be the one their
     *                           session is scoped to.
     * @param employeeJoinOrgDto DTO containing additional employment details.
     * @return A {@link ResponseEntity} with the created employee's DTO and HTTP status 201 (Created).
     */
    @PostMapping("/joinOrganization/{userId}/{orgId}")
    @PreAuthorize("@orgSecurity.isCurrentTenant(#orgId)"
            + " and (hasAuthority('employee:create') or hasAuthority('employee:admin'))")
    @Operation(
            summary = "Add a user to an organization as an employee",
            description = "Creates an employee record that links the given user to the organization "
                    + "named in the path, which must be the organization the caller's session is "
                    + "scoped to. Uses the supplied employment details. Returns the created employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee record created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The employment details failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the employee create or admin authority, or named an organization that is not the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user or organization with the given id")
    })
    public ResponseEntity<EmployeeDto> joinOrganization(@PathVariable Long userId, @PathVariable Long orgId, @Valid @RequestBody EmployeeJoinOrgDto employeeJoinOrgDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.joinOrganization(userId, orgId, employeeJoinOrgDto));
    }

    /**
     * Creates a new employee.
     *
     * <p><b>Proposed for removal under #675 and #716; deliberately left refusing until that is
     * decided, and not to be repaired by analogy with the guards around it.</b> The other guards
     * on this controller named an authority the realm cannot issue and were repaired, because each
     * had work behind it that somebody needs. This one has neither a caller nor a working body:
     *
     * <ul>
     *   <li>Nothing calls it. echno-core publishes no direct employee create and pins that with a
     *       regression test; echno-web has no create call site and no create form, only the
     *       invitation flow; the Flutter client's one employee call is
     *       {@code GET /employee/organization/{id}}. The route is reachable through the web BFF's
     *       catch-all proxy, so this is an absence of callers rather than an absence of reach.
     *   <li>It cannot succeed. {@code addEmployee} never sets {@code user}, and
     *       {@code Employee.user} is a {@code nullable = false} join column, so the save ends in a
     *       constraint violation whatever the caller sends.
     *   <li>It discards what it demands. {@code EmployeeObjectMapper} writes neither
     *       {@code designation} nor {@code department}, both {@code @NotBlank} on
     *       {@link EmployeeCreationDto}, along with {@code joiningDate}, {@code employeeId} and
     *       {@code salary}. That is the defect {@code status} was removed from the payload for.
     *   <li>It picks the tenant out of the body. {@code addEmployee} resolves the organization by
     *       {@code organizationName}, and {@code Organization} is the tenant root, so no ambient
     *       defence narrows that lookup to the caller's own. Repairing the guard alone would turn
     *       a route nobody can reach into a cross-tenant write.
     * </ul>
     *
     * <p>An administrator adding somebody who has never signed in is a real gap, and the answer to
     * it is a designed route that says what it does about the Keycloak user, not this one. The
     * route that works today is {@link #joinOrganization}, reached by an invite code redeemed at
     * {@code POST /api/v1/invitation/web/validate/userId/{userId}}.
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
     * <p>The employee directory. {@code EmployeeDto} carries salary, date of birth, address and
     * phone number, so this is the personnel record rather than a picker feed, and the roles that
     * read it are the ones the web twin's listings already name: {@code system-admin},
     * {@code hr-admin} and {@code project-manager}. A member who only needs to pick a colleague
     * out of a list has {@code GET /api/v1/employee/web/lookup}, which any member may read
     * because it exposes none of that.
     *
     * <p>{@code employee:read} and {@code employee:admin}, which this asked for until now, cannot
     * be issued: see {@link #readAnEmployee} for the mechanism. Repaired to what the web twin
     * says rather than deleted, per #716.
     *
     * @return A {@link ResponseEntity} containing the list of employee DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List all employees",
            description = "Returns at most 500 rows of the caller's own organization. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a system admin, HR admin or project manager in the current organization")
    })
    public ResponseEntity<List<EmployeeDto>> readAllEmployees() {
        return UnpagedResultCap.respond(employeeService.displayAllEmployees(
                0, UnpagedResultCap.MAX_ROWS, null, null, null));
    }

    /**
     * Retrieves a single employee by their ID.
     *
     * <p>{@code employee:read} and {@code employee:admin}, which this asked for until now, are
     * bare {@code resource:scope} authorities. {@code JwtAuthConverter} mints those in exactly one
     * place, {@code extractPermissions}, which reads the {@code authorization} claim of an RPT, so
     * each needs a Keycloak Authorization Services resource named {@code employee} carrying the
     * matching scope. The only automated provisioner registers a scopeless Default Resource and a
     * scopeless Default Permission, and a permission with no scopes yields no
     * {@code resource:scope} authority at all; the multi-tenancy audit of 2026-08-18 confirmed
     * zero scopes realm-wide against the live staging Keycloak. This endpoint therefore refused
     * every caller from the day the annotation was written. Same phantom-guard mechanism as #684,
     * and repaired the same way: the guard now says what the web twin's says, so the mobile client
     * gets the surface the web one has rather than losing the endpoint. See #710.
     *
     * @param id The ID of the employee to retrieve.
     * @return A {@link ResponseEntity} containing the employee DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin', 'project-manager')")
    @Operation(
            summary = "Get an employee by id",
            description = "Returns a single employee including their personal details, roles and status. "
                    + "Callable by the employee themselves or by a system admin, HR admin or project manager."
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

    
    /**
     * Retrieves all employees belonging to a specific organization.
     *
     * <p>The same directory as {@link #readAllEmployees}, reached by naming the organization
     * rather than leaving it to the session, so it takes the same three roles. The organization
     * clause changes though, and not as a substitution. The old guard paired the phantom
     * authority with {@code isMember(#id)}, which establishes that the caller belongs to the
     * organization they named and leaves the organization the request is scoped to unexamined; a
     * caller who belongs to two organizations could therefore read the other one's directory
     * while their session was scoped here. {@code isCurrentTenant} is the check for that, and it
     * has to be made here because {@code Organization} is the tenant root: it implements no
     * {@code TenantScopedEntity}, so {@code TenantIsolationLoadListener} returns on its first
     * line for it and a caller-supplied organization id carries no ambient protection.
     *
     * @param id The ID of the organization, which must be the one the caller's session is scoped to.
     * @return A {@link ResponseEntity} containing a list of employee DTOs for the specified organization and HTTP status 200 (OK).
     */
    @GetMapping("/organization/{id}")
    @PreAuthorize("@orgSecurity.isCurrentTenant(#id)"
            + " and @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @Operation(
            summary = "List employees in an organization",
            description = "Returns the employees belonging to the given organization, which must be the "
                    + "organization the caller's session is scoped to."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller named an organization that is not the current tenant, or is not a system admin, HR admin or project manager in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> readEmployeesByOrganizationId(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(employeeService.displayEmployeesByOrganization(id));
    }

    /**
     * Partially updates an existing employee.
     *
     * <p>Gated exactly as the web twin's PATCH is, so a person may maintain their own record from
     * the phone and {@code system-admin} or {@code hr-admin} may maintain anyone's. Keeping the
     * two twins identical also keeps this endpoint level with the {@link #readAnEmployee} beside
     * it, which #710 set to the same expression: a guard that let someone edit a record they
     * could not then read back is the asymmetry that issue was about.
     *
     * <p>The service loads the employee with
     * {@code findByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())}, the same id the
     * guard resolves and the same organization, so the guard and the write cannot land on
     * different rows.
     *
     * <p><b>The guard decides the record, not the field.</b> A caller holding neither role reaches
     * this endpoint only through the self clause, and the map they may send is narrowed to what is
     * genuinely theirs by {@link EmployeePatchFieldScope}, which runs in the service and therefore
     * covers this endpoint, its web twin and the batch alike. Naming a field outside that set is
     * refused with a 403 rather than dropped. See #735 for why the answer is the field list and
     * not the guard.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the employee to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("{id}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Partially update an employee",
            description = "Applies the supplied map of fields to the employee with the given id, "
                    + "changing only the fields present in the request. Callable by the employee "
                    + "themselves or by a system admin or HR admin. An employee editing their own "
                    + "record may change employeeName, phoneNumber, emailAddress and dateOfBirth; "
                    + "the remaining fields are set by a system admin or an HR admin, and naming "
                    + "one of them without those roles is refused."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the supplied fields is not valid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee nor a system admin or HR admin, or is the employee and named a field only those roles may set"),
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

    /**
     * Updates multiple employees in a batch.
     *
     * <p>Personnel maintenance across a set of people at once: moving a crew to a new department
     * at handover, closing out a set of records at the end of a contract. That is the work
     * {@code system-admin} and {@code hr-admin} do, and it is the pair the single-record PATCH
     * already admits.
     *
     * <p>The self clause the single-record PATCH carries has no counterpart here and is not
     * simply omitted for convenience. The ids arrive inside the request body rather than on the
     * path, so there is nothing for {@code #id} to bind to, and a person maintaining their own
     * record has the single-record route to do it on. A guard that cannot name what it is
     * guarding is the shape to avoid, so the roles decide and nothing pretends to check the ids.
     *
     * <p>What does check them is underneath: {@code batchUpdateEmployees} reads the employees
     * inside a {@code @Transactional} method, so the {@code orgFilter} is on and
     * {@code TenantIsolationLoadListener} runs on every row it materialises. An id belonging to
     * another organization is either filtered out of the read or refused at load, so a
     * {@code hr-admin} of one tenant cannot reach into another by listing its ids.
     *
     * @param updates A list of DTOs containing the updates for each employee.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Batch update employees",
            description = "Applies partial updates to several employees of the caller's organization in "
                    + "one call. Each entry names an employee id and the map of fields to change on that "
                    + "employee."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a system admin or HR admin in the current organization")
    })
    public ResponseEntity<ApiResponse> batchUpdateEmployees(@Valid @RequestBody List<EmployeePatchDto> updates) {
        employeeService.batchUpdateEmployees(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    /**
     * Deletes an employee by their ID.
     *
     * <p>Removing a person from the organization, which also takes their Keycloak group
     * membership away and so ends their access. That is administration rather than anything a
     * manager does day to day, and the web twin already reserves it to {@code system-admin} and
     * {@code hr-admin}. There is deliberately no self clause: nobody deletes their own
     * membership from the phone.
     *
     * <p>The guard names no id, so there is no id for it to disagree with the service about.
     * {@code deleteAnEmployee} finds the row with
     * {@code findByIdAndOrganizationId(id, TenantContext.getCurrentOrgId())} and a foreign id
     * simply does not resolve.
     *
     * @param id The ID of the employee to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Delete an employee",
            description = "Deletes the employee with the given id from the caller's organization, and "
                    + "removes their membership of it in Keycloak."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a system admin or HR admin in the current organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<ApiResponse> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" has been deleted"));
    }
}