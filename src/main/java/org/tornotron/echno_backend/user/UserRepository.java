package org.tornotron.echno_backend.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.organization.Organization;

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
     * Finds a user by their name.
     *
     * @param name The name of the user to find. Must not be blank and must be between 3 and 50 characters.
     * @return An {@link Optional} containing the found {@link User}, or {@link Optional#empty()} if no user with the given name exists.
     */
    Optional<User> findUserByName(@NotBlank(message = "name is required") @Size(min = 3,max = 50,message = "name must be between 3 and 50 characters") String name);

    Optional<User> findUserByKeycloakId(String keycloakId);
}