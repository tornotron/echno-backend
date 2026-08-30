package org.tornotron.echno_backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link Project} entities.
 * Provides methods to perform database operations on projects.
 */
public interface ProjectRepository extends JpaRepository<Project,Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);
    /**
     * Finds a project by its name.
     *
     * @param projectName The name of the project to find. Must not be blank and must be between 3 and 50 characters.
     * @return The {@link Project} with the given name, or null if not found.
     */
    Project findProjectByProjectName(@NotBlank(message = "projectName is required") @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters") String projectName);

    boolean existsProjectByProjectName(String projectName);

    Optional<Project> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    List<Project> findByEmployees_IdAndOrganization_Id(Long employeeId, Long organizationId);

    /**
     * Finds projects under an optional free-text filter, one page at a time.
     *
     * <p>A null search drops the clause, so the query serves the unfiltered listing as well as a
     * searched one. The pattern is a pre-lowercased {@code %...%} LIKE built by the service, which
     * escapes any wildcard the user typed; {@code ESCAPE '\'} is what makes that escaping take
     * effect.
     *
     * <p>The tenant filter applies to this query exactly as it does to a derived finder.
     *
     * @param search   Optional lower-cased LIKE pattern for the project name.
     * @param pageable The page to return.
     * @return A page of matching projects.
     */
    @Query("""
            SELECT p FROM Project p
            WHERE :search IS NULL OR LOWER(p.projectName) LIKE :search ESCAPE '\\'
            """)
    Page<Project> search(@Param("search") String search, Pageable pageable);

    /**
     * Projects in one organization whose trimmed, lower-cased name equals the given key.
     *
     * <p>Returns a list rather than one project on purpose: two projects in an organization may
     * share a name, and a caller resolving a name to a reference has to be able to tell an
     * unambiguous match from a guess. It matches the rule the asset reference migration uses, so
     * a name resolves the same way at runtime as it did during the backfill.
     *
     * @param organizationId The organization to search within.
     * @param name           The project name, already trimmed and lower-cased by the caller.
     * @return Every project in that organization carrying the name.
     */
    @Query("""
            SELECT p FROM Project p
            WHERE p.organization.id = :organizationId
              AND LOWER(TRIM(p.projectName)) = :name
            """)
    List<Project> findByNormalisedName(@Param("organizationId") Long organizationId,
                                       @Param("name") String name);

    /**
     * Averages task progress for many projects in one grouped read.
     *
     * <p>What a project list needs from a project's tasks is one number. Reaching it through the
     * mapped {@code project.getTasks()} collection loads every task of every project on the page
     * so a field can be averaged and the rest thrown away, and the call site shows none of that.
     * This returns the average itself.
     *
     * <p>{@code AVG} ignores tasks whose progress is null, which is what
     * {@link ProjectProgressCalculator} does in memory. The join is outer, so a project with no
     * tasks still produces a row, with a null average;
     * {@link ProjectProgressLookup#of} drops it and reads the project back as the {@code 0.0} the
     * calculator returns for an empty list. Pass a non-empty collection: {@code IN ()} is not
     * valid SQL.
     *
     * @param projectIds The projects to average, non-empty.
     * @return One row per project asked for.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.project.ProjectProgressTotals(
                       p.id,
                       AVG(t.progress))
            FROM Project p LEFT JOIN p.tasks t
            WHERE p.id IN :projectIds
            GROUP BY p.id
            """)
    List<ProjectProgressTotals> averageTaskProgressByProjectIds(
            @Param("projectIds") Collection<Long> projectIds);
}