package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeePatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/employee")
@Validated
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    public ResponseEntity<String> createEmployee(@Valid @RequestBody EmployeeCreationDto employeeCreationDto) {
        employeeService.addEmployee(employeeCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("Employee created successfully");
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
    public ResponseEntity<String> partialUpdateAnEmployee(@RequestBody Map<String,Object> updates,@PathVariable Long id) {
        employeeService.partialUpdateAnEmployee(updates,id);
        return new ResponseEntity<>("Employee with id: "+id+" has been updated",HttpStatus.OK);
    }

    @PatchMapping("/batch")
    public ResponseEntity<String> batchUpdateEmployees(@Valid @RequestBody List<EmployeePatchDto> updates) {
        employeeService.batchUpdateEmployees(updates);
        return new ResponseEntity<>("Batch update successful",HttpStatus.OK);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return new ResponseEntity<>("Employee with id: "+id+" deleted",HttpStatus.OK);
    }
}
