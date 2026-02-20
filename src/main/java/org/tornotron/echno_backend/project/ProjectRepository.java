package org.tornotron.echno_backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository interface for {@link Project} entities.
 * Provides methods to perform database operations on projects.
 */
public interface ProjectRepository extends JpaRepository<Project,Long> {
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
}