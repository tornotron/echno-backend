package org.tornotron.echno_backend.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.User;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee,Long> {
    Optional<Employee> findEmployeeByEmployeeName(String employeeName);
    boolean existsByUserAndOrganization(User user, Organization organization);
}
