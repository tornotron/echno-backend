package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/employee/web")
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
    @PostMapping("/joinOrganization/{userId}/{orgId}")
    @PreAuthorize("hasAuthority('employee:create') or hasAuthority('employee:admin')")
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
    @PreAuthorize("hasAuthority('employee:read') or hasAuthority('employee:admin')")
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
    public ResponseEntity<ApiResponse> partialUpdateAnEmployee(@RequestBody Map<String,Object> updates, @PathVariable Long id) {
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
    public ResponseEntity<ApiResponse> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" has been deleted"));
    }

}
