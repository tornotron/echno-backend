package org.tornotron.echno_backend.finance.ledger.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT je FROM JournalEntry je WHERE je.id = :id")
    Optional<JournalEntry> findScopedById(UUID id);

    Optional<JournalEntry> findByEntryNumber(String entryNumber);

    @EntityGraph(attributePaths = {"lines", "lines.account"})
    @Query("SELECT je FROM JournalEntry je WHERE je.id = :id")
    Optional<JournalEntry> findByIdWithLines(UUID id);

    Page<JournalEntry> findByEntryDateBetweenAndStatus(LocalDate from, LocalDate to, JournalStatus status, Pageable pageable);

    List<JournalEntry> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);

    /**
     * Tenant-scoped fetch of journal entries with their lines and accounts, for CSV export.
     * The optional {@code from} and {@code to} bounds are inclusive; a null bound leaves that end
     * open. Ordered by entry date then entry number so the export is stable.
     */
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    @Query("SELECT je FROM JournalEntry je "
            + "WHERE (:from IS NULL OR je.entryDate >= :from) "
            + "AND (:to IS NULL OR je.entryDate <= :to) "
            + "ORDER BY je.entryDate ASC, je.entryNumber ASC")
    List<JournalEntry> findForExport(LocalDate from, LocalDate to);
}
