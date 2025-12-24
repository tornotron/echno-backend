package org.tornotron.echno_backend.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.User;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link Employee} entities.
 * Provides methods to perform database operations on employees.
 */
public interface EmployeeRepository extends JpaRepository<Employee,Long> {
    /**
     * Finds an employee by their name.
     *
     * @param employeeName The name of the employee to find.
     * @return An {@link Optional} containing the found {@link Employee}, or {@link Optional#empty()} if no employee with the given name exists.
     */
    Optional<Employee> findEmployeeByEmployeeName(String employeeName);

    /**
     * Checks if an employee record exists for a given user and organization.
     *
     * @param user         The user to check.
     * @param organization The organization to check.
     * @return {@code true} if an employee record exists, {@code false} otherwise.
     */
    boolean existsByUserAndOrganization(User user, Organization organization);

    /**
     * Finds all employees belonging to a specific organization.
     *
     * @param organizationId The ID of the organization.
     * @return A list of {@link Employee}s in the specified organization.
     */
    List<Employee> findEmployeesByOrganization_Id(Long organizationId);

    /**
     * Finds employees by a list of their names.
     *
     * @param employeeNames The list of employee names to find.
     * @return A list of {@link Employee}s matching the given names.
     */
    List<Employee> findByEmployeeNameIn(List<String> employeeNames);
}