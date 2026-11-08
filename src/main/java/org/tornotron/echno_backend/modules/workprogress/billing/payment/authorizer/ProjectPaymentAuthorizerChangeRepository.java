package org.tornotron.echno_backend.modules.workprogress.billing.payment.authorizer;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Append-only: changes are saved and read, never updated or deleted. */
@Repository
public interface ProjectPaymentAuthorizerChangeRepository extends JpaRepository<ProjectPaymentAuthorizerChange, UUID> {

    // A project's changes, newest first, a page at a time.
    @Query("SELECT c FROM ProjectPaymentAuthorizerChange c WHERE c.organization.id = :orgId AND c.projectId = :projectId "
            + "ORDER BY c.changedAt DESC, c.id DESC")
    List<ProjectPaymentAuthorizerChange> findForProject(@Param("orgId") Long orgId, @Param("projectId") Long projectId,
                                                        Pageable page);
}
