package org.tornotron.echno_backend.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.user.User;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Query("SELECT DISTINCT e FROM Employee e JOIN e.orgRoles r WHERE r IN :roles")
    List<Employee> findEmployeesByOrgRoles(@Param("roles") Set<OrgRole> roles);

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

    @Query("SELECT e FROM Employee e WHERE e.id = :id AND e.organization.id = :orgId")
    Optional<Employee> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("SELECT e FROM Employee e JOIN FETCH e.user WHERE e.id = :id AND e.organization.id = :orgId")
    Optional<Employee> findByIdAndOrganizationIdWithUser(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("SELECT e FROM Employee e WHERE e.user.id = :userId AND e.organization.id = :orgId")
    Optional<Employee> findByUserIdAndOrganizationId(@Param("userId") Long userId, @Param("orgId") Long orgId);
    /**
     * Finds employees by a list of their names.
     *
     * @param employeeNames The list of employee names to find.
     * @return A list of {@link Employee}s matching the given names.
     */
    List<Employee> findByEmployeeNameIn(List<String> employeeNames);

    Employee findEmployeesByEmailAddress(String emailAddress);

    List<Employee> findByManager_Id(Long managerId);

    boolean existsByManager_Id(Long managerId);


    List<Employee> findEmployeesByIsManager(boolean isManager);

    List<Employee> findEmployeesByOrganization_IdAndIsManager(Long organizationId, boolean isManager);

    @Query("select e.id from Employee e where e.isManager = true")
    List<Long> findAllManagerIds();

    @Query("SELECT e FROM Employee e JOIN e.orgRoles r WHERE e.organization.id = :orgId AND r = :role")
    List<Employee> findByOrganizationIdAndOrgRole(Long orgId, OrgRole role);

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM Employee e JOIN e.orgRoles r WHERE e.id = :employeeId AND r IN :roles")
    boolean existsByIdAndOrgRolesIn(@Param("employeeId") Long employeeId, @Param("roles") Set<OrgRole> roles);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Paginated employee search. Every filter is optional (a null argument
     * disables that clause); the tenant orgFilter still applies. {@code search}
     * matches name, email, phone, or the human-facing employee id,
     * case-insensitively.
     */
    @Query("""
            SELECT e FROM Employee e WHERE
              (:search IS NULL
                 OR LOWER(e.employeeName) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(e.emailAddress) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(e.phoneNumber) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :search, '%'))) AND
              (:status IS NULL OR e.status = :status) AND
              (:department IS NULL OR e.department = :department)
            """)
    Page<Employee> search(
            @Param("search") String search,
            @Param("status") EmployeeStatus status,
            @Param("department") String department,
            Pageable pageable);
}