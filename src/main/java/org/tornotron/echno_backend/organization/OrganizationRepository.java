package org.tornotron.echno_backend.organization;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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


    /**
     * The organizations that have recorded written consent to dataset export (#790). Read by
     * the export sweep across tenants; the organization table carries no tenant filter.
     */
    List<Organization> findByDatasetConsentTrue();

    /**
     * Reads what an organization card renders beyond the organization's own columns, for many
     * organizations in one query: the employee count, the project count and the storage key of
     * the current logo.
     *
     * <p>Native rather than HQL on purpose. The organizations asked for are the ones the caller
     * belongs to, which is usually more than the one the request is scoped to, and the
     * {@code orgFilter} applies to every {@code Employee}, {@code Project} and {@code Attachment}
     * reference an HQL query makes, subqueries included. In HQL every organization but the
     * current tenant would therefore count zero and show no logo. The full
     * {@code OrganizationDto} carries those same employees, projects and attachments for every
     * organization the caller belongs to; this reads three figures off the same rows. The tenant
     * boundary is the membership read that supplies {@code organizationIds}, not this query, so
     * pass only ids that came from {@link #findAllByUserEmail} or an equivalent check.
     *
     * <p>Every organization asked for comes back as one row: the counts are zero for an
     * organization with nothing to count and the key is null where no {@code ORGANIZATION_LOGO}
     * attachment exists. Where several exist the most recently created wins, which is the rule
     * the full view's client applies to the attachment list. Nothing here is an entity load, so
     * the load-boundary listener has nothing to inspect. Pass a non-empty collection.
     *
     * @param organizationIds The organizations to read, non-empty, already checked for membership.
     * @return One row per organization, as the four columns of
     *         {@link OrganizationSummaryTotals#fromRow}.
     */
    @Query(value = """
            SELECT o.id AS organization_id,
                   (SELECT COUNT(*) FROM employee e WHERE e.organization_id = o.id) AS employee_count,
                   (SELECT COUNT(*) FROM project p WHERE p.organization_id = o.id) AS project_count,
                   (SELECT a.storage_key FROM attachment a
                     WHERE a.entity_type = 'ORGANIZATION_LOGO' AND a.entity_id = o.id
                     ORDER BY a.created_at DESC, a.id DESC
                     LIMIT 1) AS logo_storage_key
            FROM organization o
            WHERE o.id IN (:organizationIds)
            """, nativeQuery = true)
    List<Object[]> summaryTotalsByOrganizationIds(
            @Param("organizationIds") Collection<Long> organizationIds);
}