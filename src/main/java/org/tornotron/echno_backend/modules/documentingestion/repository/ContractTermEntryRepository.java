package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.ContractTermEntry;

@Repository
public interface ContractTermEntryRepository extends JpaRepository<ContractTermEntry, UUID> {

    @Query("SELECT t FROM ContractTermEntry t WHERE t.id = :id")
    Optional<ContractTermEntry> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT t FROM ContractTermEntry t WHERE t.packageId = :packageId AND t.term = :term")
    Optional<ContractTermEntry> findByPackageAndTerm(@Param("packageId") UUID packageId, @Param("term") String term);

    /** At most one row per term, so a package holds at most 19. */
    @Query("SELECT t FROM ContractTermEntry t WHERE t.packageId = :packageId ORDER BY t.term")
    List<ContractTermEntry> findByPackage(@Param("packageId") UUID packageId);
}
