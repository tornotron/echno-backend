package org.tornotron.echno_backend.modules.__MODULE_PKG__.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.domain.__MODULE_PASCAL__Entry;

@Repository
public interface __MODULE_PASCAL__EntryRepository extends JpaRepository<__MODULE_PASCAL__Entry, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT e FROM __MODULE_PASCAL__Entry e WHERE e.id = :id")
    Optional<__MODULE_PASCAL__Entry> findByIdScoped(@Param("id") UUID id);

    // Paged, never findAll(): an unbounded read grows with the tenant's history.
    @Query("SELECT e FROM __MODULE_PASCAL__Entry e ORDER BY e.createdAt DESC")
    Page<__MODULE_PASCAL__Entry> findPage(Pageable pageable);
}
