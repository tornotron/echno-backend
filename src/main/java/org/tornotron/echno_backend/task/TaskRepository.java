package org.tornotron.echno_backend.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.IssueComment.IssueComment;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.util.List;
import java.util.Optional;


/**
 * Repository interface for {@link Task} entities.
 * Provides methods to perform database operations on tasks.
 */
public interface TaskRepository extends JpaRepository<Task,Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);
    /**
     * Finds a task by its title.
     *
     * @param title The title of the task to find. Must not be blank and must be between 3 and 50 characters.
     * @return The {@link Task} with the given title, or null if not found.
     */
    Task findTaskByTitle(@NotBlank(message = "title is required") @Size(min = 3,max = 50,message = "title must be between 3 and 50 characters") String title);

    Optional<Task> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    List<Task> findByProject_Id(Long projectId);

    List<Task> findAllByProject_IdAndOrganization_Id(Long projectId, Long organizationId);

    /**
     * Finds tasks under optional project and free-text filters, one page at a time.
     *
     * <p>Every filter is optional: a null argument drops its clause, so the query serves the
     * unfiltered listing as well as a project-scoped or searched one. The search pattern is a
     * pre-lowercased {@code %...%} LIKE built by the service, which escapes any wildcard the user
     * typed; {@code ESCAPE '\'} is what makes that escaping take effect.
     *
     * <p>The tenant filter applies to this query exactly as it does to a derived finder.
     *
     * @param projectId Optional project id.
     * @param search    Optional lower-cased LIKE pattern for title or description.
     * @param pageable  The page to return.
     * @return A page of matching tasks.
     */
    @Query("""
            SELECT t FROM Task t WHERE
              (:projectId IS NULL OR t.project.id = :projectId) AND
              (:search IS NULL
                 OR LOWER(t.title) LIKE :search ESCAPE '\\'
                 OR LOWER(t.description) LIKE :search ESCAPE '\\')
            """)
    Page<Task> search(@Param("projectId") Long projectId,
                      @Param("search") String search,
                      Pageable pageable);

    /**
     * Counts the current tenant's tasks by status.
     *
     * <p>Aggregated in the database rather than by loading every task and grouping in memory, so
     * the cost stays flat as a tenant's task history grows. The tenant filter applies to the query
     * exactly as it does to a finder.
     *
     * @return One row per status present, each carrying the status and its count.
     */
    @Query("select t.status as status, count(t) as total from Task t group by t.status")
    List<TaskStatusCount> countByStatus();

    /** Row of {@link #countByStatus()}: one task status and how many tasks hold it. */
    interface TaskStatusCount {
        TaskStatus getStatus();

        long getTotal();
    }


//    List<Task> findTaskByEmployee_IdAndProject_Id(@NotNull(message = "employeeId is required") Long employeeId, @NotNull(message = "projectId is required") Long projectId);
}