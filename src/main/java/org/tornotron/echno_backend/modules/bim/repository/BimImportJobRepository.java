package org.tornotron.echno_backend.modules.bim.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;

@Repository
public interface BimImportJobRepository extends JpaRepository<BimImportJob, UUID> {

    @Query("SELECT j FROM BimImportJob j WHERE j.id = :id")
    Optional<BimImportJob> findByIdScoped(@Param("id") UUID id);

    List<BimImportJob> findByVersionIdOrderByQueuedAtDesc(UUID versionId);

    Optional<BimImportJob> findFirstByVersionIdOrderByQueuedAtDesc(UUID versionId);
}
