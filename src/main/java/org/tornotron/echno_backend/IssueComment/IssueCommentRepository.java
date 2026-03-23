package org.tornotron.echno_backend.IssueComment;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<IssueComment> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<IssueComment> findAllByIssue_IdAndOrganization_Id(Long issueId, Long organizationId);
}
