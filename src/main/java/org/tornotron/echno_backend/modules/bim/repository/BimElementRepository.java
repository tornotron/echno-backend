package org.tornotron.echno_backend.modules.bim.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;

@Repository
public interface BimElementRepository extends JpaRepository<BimElement, UUID> {

    @Query("SELECT e FROM BimElement e WHERE e.id = :id")
    Optional<BimElement> findByIdScoped(@Param("id") UUID id);

    Optional<BimElement> findByModelIdAndGlobalId(UUID modelId, String globalId);

    List<BimElement> findByModelId(UUID modelId);

    @Query("SELECT e FROM BimElement e WHERE e.modelId = :modelId "
            + "AND (:storeyGlobalId IS NULL OR e.storeyGlobalId = :storeyGlobalId) "
            + "AND (:includeRetired = TRUE OR e.retired = FALSE) "
            + "ORDER BY e.storeyGlobalId, e.ifcType, e.name")
    Page<BimElement> search(@Param("modelId") UUID modelId,
                            @Param("storeyGlobalId") String storeyGlobalId,
                            @Param("includeRetired") boolean includeRetired,
                            Pageable pageable);

    long countByModelIdAndRetiredFalse(UUID modelId);

    long countByModelIdAndRetiredTrue(UUID modelId);

    List<BimElement> findByModelIdAndSpatialNodeIdIsNotNull(UUID modelId);

}
