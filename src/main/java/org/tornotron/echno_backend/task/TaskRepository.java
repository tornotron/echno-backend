package org.tornotron.echno_backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


/**
 * Repository interface for {@link Task} entities.
 * Provides methods to perform database operations on tasks.
 */
public interface TaskRepository extends JpaRepository<Task,Long> {
    /**
     * Finds a task by its title.
     *
     * @param title The title of the task to find. Must not be blank and must be between 3 and 50 characters.
     * @return The {@link Task} with the given title, or null if not found.
     */
    Task findTaskByTitle(@NotBlank(message = "title is required") @Size(min = 3,max = 50,message = "title must be between 3 and 50 characters") String title);

    Optional<Task> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);
            

//    List<Task> findTaskByEmployee_IdAndProject_Id(@NotNull(message = "employeeId is required") Long employeeId, @NotNull(message = "projectId is required") Long projectId);
}