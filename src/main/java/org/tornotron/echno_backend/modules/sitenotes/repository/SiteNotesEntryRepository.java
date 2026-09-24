package org.tornotron.echno_backend.modules.sitenotes.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNotesEntry;

@Repository
public interface SiteNotesEntryRepository extends JpaRepository<SiteNotesEntry, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT e FROM SiteNotesEntry e WHERE e.id = :id")
    Optional<SiteNotesEntry> findByIdScoped(@Param("id") UUID id);

    // Paged, never findAll(): an unbounded read grows with the tenant's history.
    @Query("SELECT e FROM SiteNotesEntry e ORDER BY e.createdAt DESC")
    Page<SiteNotesEntry> findPage(Pageable pageable);
}
