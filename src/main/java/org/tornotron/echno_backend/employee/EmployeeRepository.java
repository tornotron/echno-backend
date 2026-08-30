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

import java.util.Collection;
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

    /**
     * Finds every employee holding at least one of the given organization roles.
     *
     * <p>Membership of a role is what makes someone a manager here. The roles are mirrored
     * from the Keycloak group the token carries, which is the same source the
     * {@code @PreAuthorize} checks read, so a query written this way agrees with the
     * authorization layer by construction.
     */
    @Query("SELECT DISTINCT e FROM Employee e JOIN e.orgRoles r WHERE r IN :roles")
    List<Employee> findEmployeesByOrgRoles(@Param("roles") Set<OrgRole> roles);

    /**
     * The same read, narrowed to one organization.
     *
     * @param organizationId The organization to look within.
     * @param roles          The roles that count as managing.
     */
    @Query("SELECT DISTINCT e FROM Employee e JOIN e.orgRoles r "
            + "WHERE e.organization.id = :orgId AND r IN :roles")
    List<Employee> findEmployeesByOrganizationIdAndOrgRoles(@Param("orgId") Long organizationId,
                                                            @Param("roles") Set<OrgRole> roles);

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

    /**
     * Finds the employees among the given ids that belong to one organization.
     *
     * <p>Resolves a whole set of assignees in a single query and drops anyone outside the
     * organization, so the caller can compare what came back with what it asked for and name the
     * ids that are not assignable rather than reading the organization's entire roster to find out.
     *
     * @param ids   The employee ids to resolve.
     * @param orgId The organization the employees must belong to.
     * @return The matching employees; ids outside the organization or absent altogether are simply
     *         not in the result.
     */
    @Query("SELECT e FROM Employee e WHERE e.id IN :ids AND e.organization.id = :orgId")
    List<Employee> findAllByIdInAndOrganizationId(@Param("ids") Collection<Long> ids,
                                                  @Param("orgId") Long orgId);

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

    @Query("SELECT e FROM Employee e JOIN e.orgRoles r WHERE e.organization.id = :orgId AND r = :role")
    List<Employee> findByOrganizationIdAndOrgRole(Long orgId, OrgRole role);

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM Employee e JOIN e.orgRoles r WHERE e.id = :employeeId AND r IN :roles")
    boolean existsByIdAndOrgRolesIn(@Param("employeeId") Long employeeId, @Param("roles") Set<OrgRole> roles);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Names for a known set of employee ids, in one query.
     *
     * <p>For the places that print a scalar employee reference and would otherwise
     * look each one up in a loop. The read is bounded by the ids the caller already
     * holds, and the projection keeps it to two columns rather than hydrating an
     * employee per row.
     *
     * @param organizationId The tenant. Named explicitly rather than left to a
     *                       filter, since this crosses into another module's table.
     * @param ids            The ids to resolve. Must not be empty.
     */
    @Query("SELECT e.id AS id, e.employeeName AS employeeName FROM Employee e "
            + "WHERE e.organization.id = :organizationId AND e.id IN :ids")
    List<EmployeeName> findNamesByIds(@Param("organizationId") Long organizationId,
                                      @Param("ids") Collection<Long> ids);

    /** An employee id and the name to print for it. */
    interface EmployeeName {
        Long getId();

        String getEmployeeName();
    }

    /**
     * Paginated employee search. Every filter is optional (a null argument
     * disables that clause); the tenant orgFilter still applies. {@code search}
     * matches name, email, phone, or the human-facing employee id,
     * case-insensitively. The caller passes {@code search} already lower-cased
     * and wrapped in {@code %} wildcards (or null) — building the pattern in
     * Java rather than with SQL {@code CONCAT} avoids a CockroachDB type error
     * where a null bind inside {@code ||} is inferred as bytes.
     */
    @Query("""
            SELECT e FROM Employee e WHERE
              (:search IS NULL
                 OR LOWER(e.employeeName) LIKE :search
                 OR LOWER(e.emailAddress) LIKE :search
                 OR LOWER(e.phoneNumber) LIKE :search
                 OR LOWER(e.employeeId) LIKE :search) AND
              (:status IS NULL OR e.status = :status) AND
              (:department IS NULL OR e.department = :department)
            """)
    Page<Employee> search(
            @Param("search") String search,
            @Param("status") EmployeeStatus status,
            @Param("department") String department,
            Pageable pageable);

    /**
     * Paginated employee search for the picker feed, which any tenant member may
     * read. Deliberately narrower than {@link #search}: it matches only the two
     * fields a picker displays, the employee name and the human-facing employee
     * id, and not the email or phone number. Matching a contact detail would let
     * any member confirm a guessed address or number against a returned identity,
     * which is a lookup the picker has no use for. The caller passes {@code search}
     * already lower-cased and wrapped in {@code %} wildcards (or null); see
     * {@link #search} for why the pattern is built in Java. The tenant orgFilter
     * still applies.
     */
    @Query("""
            SELECT e FROM Employee e WHERE
              (:search IS NULL
                 OR LOWER(e.employeeName) LIKE :search
                 OR LOWER(e.employeeId) LIKE :search)
            """)
    Page<Employee> searchForLookup(@Param("search") String search, Pageable pageable);
}