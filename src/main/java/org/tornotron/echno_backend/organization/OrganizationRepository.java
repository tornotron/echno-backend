package org.tornotron.echno_backend.organization;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link Organization} entities.
 * Provides methods to perform database operations on organizations.
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    /**
     * Finds an organization by its name.
     *
     * @param organizationName The name of the organization to find. Must not be blank and must be between 3 and 50 characters.
     * @return An {@link Optional} containing the found {@link Organization}, or {@link Optional#empty()} if no organization with the given name exists.
     */
    Optional<Organization> findOrganizationByOrganizationName(@NotBlank(message = "organizationName is required") @Size(min = 3, max = 50,message = "organizationName must be between 3 and 50 characters") String organizationName);

    /**
     * Finds all organizations created by a specific user.
     *
     * @param creatorId The ID of the user who created the organizations.
     * @return A list of {@link Organization}s created by the specified user.
     */
    List<Organization> findOrganizationsByCreatorId(Integer creatorId);

    boolean existsByOrganizationEmail(String organizationEmail);

    /**
     * Whether the given user has ever created an organization.
     *
     * @param creatorId The ID of the user to check.
     * @return {@code true} if at least one organization records them as its creator.
     */
    boolean existsByCreatorId(Integer creatorId);

    @Query(
            "SELECT DISTINCT o FROM Organization o " +
                    "JOIN o.employees e " +
                    "JOIN e.user u " +
                    "WHERE u.email = :email"
    )
    List<Organization> findAllByUserEmail(@Param("email") String email);

    @Query(
            "SELECT o FROM Organization o " +
                    "JOIN o.employees e " +
                    "JOIN e.user u " +
                    "WHERE o.id = :organizationId AND u.email = :email"
    )
    Optional<Organization> findByIdAndUserEmail(@Param("organizationId") Long organizationId, @Param("email") String email);

}