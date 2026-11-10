package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.DrawingEntry;

@Repository
public interface DrawingEntryRepository extends JpaRepository<DrawingEntry, UUID> {

    @Query("SELECT d FROM DrawingEntry d WHERE d.id = :id")
    Optional<DrawingEntry> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT d FROM DrawingEntry d WHERE d.packageId = :packageId AND d.numberKey = :numberKey "
            + "AND d.revisionKey = :revisionKey AND d.status = :status")
    Optional<DrawingEntry> findRow(@Param("packageId") UUID packageId, @Param("numberKey") String numberKey,
                                   @Param("revisionKey") String revisionKey,
                                   @Param("status") DrawingEntry.Status status);

    /** Every revision of one number in a package, in no set order; a chain is a handful of rows. */
    @Query("SELECT d FROM DrawingEntry d WHERE d.packageId = :packageId AND d.numberKey = :numberKey")
    List<DrawingEntry> findChain(@Param("packageId") UUID packageId, @Param("numberKey") String numberKey);

    @Query("SELECT d FROM DrawingEntry d WHERE d.packageId = :packageId "
            + "AND (:currentOnly = false OR d.current = true) ORDER BY d.numberKey, d.createdAt, d.id")
    Page<DrawingEntry> findByPackage(@Param("packageId") UUID packageId, @Param("currentOnly") boolean currentOnly,
                                     Pageable pageable);
}
