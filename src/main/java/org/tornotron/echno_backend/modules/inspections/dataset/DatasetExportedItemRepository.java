package org.tornotron.echno_backend.modules.inspections.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DatasetExportedItemRepository extends JpaRepository<DatasetExportedItem, UUID> {

    /** Every source reference of one kind already exported for the organization. */
    @Query("SELECT i.sourceRef FROM DatasetExportedItem i "
            + "WHERE i.organization.id = :organizationId AND i.sourceKind = :kind")
    List<String> findSourceRefs(@Param("organizationId") Long organizationId,
                                @Param("kind") DatasetSourceKind kind);

    List<DatasetExportedItem> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
