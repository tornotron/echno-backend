package org.tornotron.echno_backend.finance.construction.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConstructionPaymentRepository
        extends JpaRepository<ConstructionPayment, UUID>, JpaSpecificationExecutor<ConstructionPayment> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT cp FROM ConstructionPayment cp WHERE cp.id = :id")
    Optional<ConstructionPayment> findByIdScoped(@Param("id") UUID id);
}
