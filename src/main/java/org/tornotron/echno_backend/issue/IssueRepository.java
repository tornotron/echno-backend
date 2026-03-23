package org.tornotron.echno_backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<Issue> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    List<Issue> findAllByTask_IdAndOrganization_Id(Long taskId, Long organizationId);

    List<Issue> findAllByTask_Project_IdAndOrganization_Id(Long projectId, Long organizationId);

}
