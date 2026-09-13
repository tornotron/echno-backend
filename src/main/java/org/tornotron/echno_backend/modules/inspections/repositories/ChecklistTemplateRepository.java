package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChecklistTemplateRepository
        extends JpaRepository<ChecklistTemplate, UUID>, JpaSpecificationExecutor<ChecklistTemplate> {

    /**
     * The paged list with each row's {@code tradeRef} fetched in the same select. The
     * mapper reads the trade's code, name and group for every row, and with the lazy
     * association that was one extra select per distinct trade on the page.
     */
    @Override
    @EntityGraph(attributePaths = "tradeRef")
    Page<ChecklistTemplate> findAll(Specification<ChecklistTemplate> spec, Pageable pageable);

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads. The
     * items load lazily while the transaction is still open.
     */
    @Query("SELECT t FROM ChecklistTemplate t WHERE t.id = :id")
    Optional<ChecklistTemplate> findByIdScoped(@Param("id") UUID id);

    /**
     * The template an inspection of this trade is created from. Only an active
     * template is instantiated: deactivating one retires the checklist for new
     * inspections without touching the ones already carried out against it. The
     * {@code orgFilter} restricts this to the calling tenant, and the unique
     * constraint on (organization, trade) makes at most one row match.
     */
    Optional<ChecklistTemplate> findByTradeRefAndActiveTrue(OrgTrade trade);

    /** Guard for the one-template-per-trade rule, checked before an insert. */
    boolean existsByTradeRef(OrgTrade trade);
}
