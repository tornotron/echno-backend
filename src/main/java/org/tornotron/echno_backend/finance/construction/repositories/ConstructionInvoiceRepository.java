package org.tornotron.echno_backend.finance.construction.repositories;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConstructionInvoiceRepository
        extends JpaRepository<ConstructionInvoice, UUID>, JpaSpecificationExecutor<ConstructionInvoice> {

    /**
     * Org-scoped lookup by id with its lines eagerly fetched. Uses JPQL (not
     * {@code find()} by primary key) so the Hibernate {@code orgFilter} is applied,
     * preventing cross-tenant reads.
     */
    @EntityGraph(attributePaths = {"lines"})
    @Query("SELECT ci FROM ConstructionInvoice ci WHERE ci.id = :id")
    Optional<ConstructionInvoice> findByIdWithLines(@Param("id") UUID id);
}
