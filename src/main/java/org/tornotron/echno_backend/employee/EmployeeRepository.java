package org.tornotron.echno_backend.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee,Long> {
    Optional<Employee> findEmployeeByEmployeeName(String employeeName);
}
