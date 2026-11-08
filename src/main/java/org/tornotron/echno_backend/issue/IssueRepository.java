package org.tornotron.echno_backend.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<Issue> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    List<Issue> findAllByTask_IdAndOrganization_Id(Long taskId, Long organizationId);

    /**
     * Every issue of a project: the ones linked to it directly and the ones raised on one of its
     * tasks. Both are read so a row written before {@code project_id} was filled in is not missed.
     */
    @Query("""
            SELECT i FROM Issue i
              LEFT JOIN i.project p
              LEFT JOIN i.task t
              LEFT JOIN t.project tp
            WHERE i.organization.id = :organizationId
              AND (p.id = :projectId OR tp.id = :projectId)
            """)
    List<Issue> findAllInProject(@Param("projectId") Long projectId,
                                 @Param("organizationId") Long organizationId);

    /**
     * Paginated issue search. Every filter is optional (a null argument disables
     * that clause); the tenant orgFilter still applies. {@code search} matches
     * title, description, or the creator's name, case-insensitively.
     * {@code assigneeId} and {@code creatorId} restrict to the issues assigned to
     * or created by that employee. The project is matched on the issue's own project or its
     * task's, through outer joins, so an issue with no task is found too.
     */
    @Query("""
            SELECT i FROM Issue i
              LEFT JOIN i.project p
              LEFT JOIN i.task t
              LEFT JOIN t.project tp
            WHERE
              (:projectId IS NULL OR p.id = :projectId OR tp.id = :projectId) AND
              (:search IS NULL
                 OR LOWER(i.title) LIKE :search
                 OR LOWER(i.description) LIKE :search
                 OR LOWER(i.createdBy.employeeName) LIKE :search) AND
              (:status IS NULL OR i.status = :status) AND
              (:type IS NULL OR i.type = :type) AND
              (:assigneeId IS NULL OR i.assignedTo.id = :assigneeId) AND
              (:creatorId IS NULL OR i.createdBy.id = :creatorId)
            """)
    Page<Issue> search(
            @Param("projectId") Long projectId,
            @Param("search") String search,
            @Param("status") IssueStatus status,
            @Param("type") IssueType type,
            @Param("assigneeId") Long assigneeId,
            @Param("creatorId") Long creatorId,
            Pageable pageable);

    /**
     * Counts issues per status under the same optional filters as {@link #search}
     * (status itself excluded, so the breakdown always spans every status). Each
     * row is {@code [IssueStatus, Long]}.
     */
    @Query("""
            SELECT i.status, COUNT(i) FROM Issue i
              LEFT JOIN i.project p
              LEFT JOIN i.task t
              LEFT JOIN t.project tp
            WHERE
              (:projectId IS NULL OR p.id = :projectId OR tp.id = :projectId) AND
              (:search IS NULL
                 OR LOWER(i.title) LIKE :search
                 OR LOWER(i.description) LIKE :search
                 OR LOWER(i.createdBy.employeeName) LIKE :search) AND
              (:type IS NULL OR i.type = :type)
            GROUP BY i.status
            """)
    List<Object[]> countByStatus(
            @Param("projectId") Long projectId,
            @Param("search") String search,
            @Param("type") IssueType type);

}
