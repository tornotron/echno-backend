package org.tornotron.echno_backend.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.organization.Organization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * Repository interface for {@link User} entities.
 * Provides methods to perform database operations on users.
 */
public interface UserRepository extends JpaRepository<User,Long> {
    /**
     * Finds all distinct organizations a user is associated with through their employment.
     *
     * @param userId The ID of the user.
     * @return A list of {@link Organization}s the user belongs to.
     */
    @Query("SELECT DISTINCT e.organization FROM Employee  e WHERE e.user.id = :userId")
    List<Organization> findOrganizationsByUserId(@Param("userId") Long userId);

    /**
     * Finds the distinct users who have an employment in the given organization.
     * Used to scope the user directory to the caller's tenant (User is not itself
     * a tenant-scoped entity, so it must be scoped via Employee).
     *
     * @param organizationId The organization to scope by.
     * @param pageable       Pagination.
     * @return A page of {@link User}s belonging to that organization.
     */
    @Query("SELECT DISTINCT e.user FROM Employee e WHERE e.organization.id = :organizationId")
    Page<User> findUsersByOrganizationId(@Param("organizationId") Long organizationId, Pageable pageable);

    /**
     * Finds the users among the given ids that belong to the organization (via an
     * Employee record). Used to scope batch operations so a caller can only touch
     * users in their own tenant: ids outside the organization simply do not come
     * back, letting the caller reject them.
     *
     * @param organizationId The organization to scope by.
     * @param userIds        The candidate user ids.
     * @return The subset of those users that are members of the organization.
     */
    @Query("SELECT DISTINCT e.user FROM Employee e "
            + "WHERE e.organization.id = :organizationId AND e.user.id IN :userIds")
    List<User> findUsersByOrganizationIdAndIdIn(@Param("organizationId") Long organizationId,
                                                @Param("userIds") List<Long> userIds);

    /**
     * Reads just enough of the given users to print a name against a document stamp.
     *
     * <p>Not scoped to an organization, and not {@code findAllById}. Not scoped because the ids
     * come from {@code submittedBy} / {@code approvedBy} columns on a document the caller has
     * already been authorised to read, and the approver may have left the organization since;
     * scoping would blank the historical records the stamp exists for. Not {@code findAllById}
     * because that returns whole {@link User} entities, and a page of stamps wants three columns,
     * not each user's attachments and employments.
     *
     * @param userIds The user ids to resolve.
     * @return One row per id that still exists; ids with no row simply do not come back.
     */
    @Query("SELECT new org.tornotron.echno_backend.user.UserDisplayName(u.id, u.name, u.email) "
            + "FROM User u WHERE u.id IN :userIds")
    List<UserDisplayName> findDisplayNamesByIdIn(@Param("userIds") Collection<Long> userIds);

    /**
     * Finds a user by their name.
     *
     * @param name The name of the user to find. Must not be blank and must be between 3 and 50 characters.
     * @return An {@link Optional} containing the found {@link User}, or {@link Optional#empty()} if no user with the given name exists.
     */
    Optional<User> findUserByName(@NotBlank(message = "name is required") @Size(min = 3,max = 50,message = "name must be between 3 and 50 characters") String name);

    Optional<User> findUserByKeycloakId(String keycloakId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.employees WHERE u.keycloakId = :keycloakId")
    Optional<User> findUserWithEmployeesByKeycloakId(@Param("keycloakId") String keycloakId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.attachments WHERE u.keycloakId = :keycloakId")
    Optional<User> findUserWithAttachmentsByKeycloakId(@Param("keycloakId") String keycloakId);

    boolean existsUserByEmail(String email);
}