package org.tornotron.echno_backend.modules.workprogress.billing.payment.authorizer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectPaymentAuthorizerRepository extends JpaRepository<ProjectPaymentAuthorizer, UUID> {

    @Query("SELECT a FROM ProjectPaymentAuthorizer a WHERE a.organization.id = :orgId AND a.projectId = :projectId")
    Optional<ProjectPaymentAuthorizer> findForProject(@Param("orgId") Long orgId, @Param("projectId") Long projectId);

    // Serializes two people changing the same project's authorizer, so the history records each change
    // against the one it replaced.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ProjectPaymentAuthorizer a WHERE a.organization.id = :orgId AND a.projectId = :projectId")
    Optional<ProjectPaymentAuthorizer> lockForProject(@Param("orgId") Long orgId, @Param("projectId") Long projectId);
}
