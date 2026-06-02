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
    Optional<JournalEntry> findByEntryNumber(String entryNumber);

    @EntityGraph(attributePaths = {"lines", "lines.account"})
    @Query("SELECT je FROM JournalEntry je WHERE je.id = :id")
    Optional<JournalEntry> findByIdWithLines(UUID id);

    Page<JournalEntry> findByEntryDateBetweenAndStatus(LocalDate from, LocalDate to, JournalStatus status, Pageable pageable);

    List<JournalEntry> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);
}
