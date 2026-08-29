package org.tornotron.echno_backend.finance.invoice.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes for customer (accounts-receivable) invoices.
 *
 * <p>The list read goes through {@link JpaSpecificationExecutor}, whose
 * {@code findAll(Specification, Pageable)} is a criteria query on the entity and therefore carries
 * the Hibernate {@code orgFilter} declared on {@link Invoice}. That is what keeps one tenant's
 * listing from enumerating another's, and it is why the filters are built as predicates rather
 * than as derived query methods: a derived method per filter combination multiplies the surface
 * that has to be proven scoped, and it cannot express two filters at once without a method for
 * every pair.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    /**
     * Org-scoped lookup by id with the lines, their revenue accounts and the customer fetched.
     * Uses JPQL rather than a primary-key {@code find()} so the {@code orgFilter} is applied.
     */
    @EntityGraph(attributePaths = {"lines", "lines.revenueAccount", "customer"})
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdWithLines(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);
}
