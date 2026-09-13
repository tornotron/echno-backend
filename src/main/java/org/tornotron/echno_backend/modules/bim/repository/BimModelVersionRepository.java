package org.tornotron.echno_backend.modules.bim.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;

@Repository
public interface BimModelVersionRepository extends JpaRepository<BimModelVersion, UUID> {

    /** Lookup by id alone, as JPQL so the {@code orgFilter} applies. */
    @Query("SELECT v FROM BimModelVersion v WHERE v.id = :id")
    Optional<BimModelVersion> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT v FROM BimModelVersion v WHERE v.id = :id AND v.modelId = :modelId")
    Optional<BimModelVersion> findByIdAndModelId(@Param("id") UUID id, @Param("modelId") UUID modelId);

    List<BimModelVersion> findByModelIdOrderByVersionNumberDesc(UUID modelId);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM BimModelVersion v WHERE v.modelId = :modelId")
    int maxVersionNumber(@Param("modelId") UUID modelId);
}
