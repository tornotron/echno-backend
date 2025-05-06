package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/employee")
@Validated
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createEmployee(@Valid @RequestBody EmployeeCreationDto employeeCreationDto) {
        employeeService.addEmployee(employeeCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Employee Created Successfully"));
    }

    @GetMapping
    public ResponseEntity<List<EmployeeDto>> readAllEmployees() {
        return new ResponseEntity<>(employeeService.displayAllEmployees(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<EmployeeDto> readAnEmployee(@PathVariable Long id) {
        EmployeeDto employee = employeeService.displayAnEmployee(id);
       return ResponseEntity.status(HttpStatus.OK).body(employee);
    }

    @PatchMapping("{id}")
    public ResponseEntity<ApiResponse> partialUpdateAnEmployee(@RequestBody Map<String,Object> updates,@PathVariable Long id) {
        employeeService.partialUpdateAnEmployee(updates,id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" updated"));
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse> batchUpdateEmployees(@Valid @RequestBody List<EmployeePatchDto> updates) {
        employeeService.batchUpdateEmployees(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee with id: "+id+" has been deleted"));
    }
}
