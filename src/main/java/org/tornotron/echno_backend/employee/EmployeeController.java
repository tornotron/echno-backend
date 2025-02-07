package org.tornotron.echno_backend.employee;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;

import java.util.List;

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
        boolean created = employeeService.addEmployee(employeeCreationDto);
        if(created) {
            return ResponseEntity.status(HttpStatus.CREATED).body("Employee created successfully");
        }
        throw new DatabaseOperationException("Employee could not be created");
    }

    @GetMapping
    public ResponseEntity<List<EmployeeCreationDto>> readAllEmployees() {
        return new ResponseEntity<>(employeeService.displayAllEmployees(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<EmployeeCreationDto> readAnEmployee(@PathVariable Long id) {
        EmployeeCreationDto employee = employeeService.displayAnEmployee(id);
        if(employee!=null) {
            ResponseEntity.status(HttpStatus.OK).body(employee);
        }
        throw new ResourceNotFoundException("Employee not found with id: "+id);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateEmployee(@RequestBody EmployeeCreationDto updatedEmployee,@PathVariable Long id) {
        boolean updated = employeeService.updateAnEmployee(updatedEmployee,id);
        if(updated) {
            return new ResponseEntity<>("Employee with id: "+id+" has been updated",HttpStatus.OK);
        }
        return new ResponseEntity<>("Employee with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteAnEmployee(id);
        return new ResponseEntity<>("Employee with id: "+id+" deleted",HttpStatus.OK);
    }
}
