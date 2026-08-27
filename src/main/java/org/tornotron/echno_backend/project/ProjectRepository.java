package org.tornotron.echno_backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}