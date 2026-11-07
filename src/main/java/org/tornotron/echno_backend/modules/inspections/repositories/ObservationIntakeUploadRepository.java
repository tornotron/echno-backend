package org.tornotron.echno_backend.modules.inspections.repositories;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;

public interface ObservationIntakeUploadRepository extends JpaRepository<ObservationIntakeUpload, UUID> {

    /** Org-scoped lookup by id: JPQL rather than {@code find()} so the Hibernate orgFilter applies. */
    @Query("SELECT u FROM ObservationIntakeUpload u WHERE u.id = :id")
    Optional<ObservationIntakeUpload> findByIdScoped(@Param("id") UUID id);

    /** The organizations with an upload at all: the sweep's list, before it pins a tenant for each. */
    @Query("SELECT DISTINCT u.organization.id FROM ObservationIntakeUpload u ORDER BY u.organization.id")
    List<Long> findOrganizationIdsWithUploads(Pageable pageable);

    /** The current tenant's intake images older than the cutoff, oldest first. */
    @Query("SELECT a FROM Attachment a WHERE a.entityType = :entityType AND a.createdAt < :cutoff ORDER BY a.id")
    List<Attachment> findAttachmentsOlderThan(@Param("entityType") String entityType,
                                              @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** How many of the tenant's observations cite this JSON, an {@code evidenceRefs} array to look for. */
    @Query(value = "SELECT COUNT(*) FROM inspection_observations WHERE organization_id = :organizationId "
            + "AND evidence_refs @> CAST(:evidenceRef AS JSONB)", nativeQuery = true)
    long countObservationsCiting(@Param("organizationId") Long organizationId, @Param("evidenceRef") String evidenceRef);

    /** Presign calls older than the cutoff with no attachment left. */
    @Modifying
    @Query("DELETE FROM ObservationIntakeUpload u WHERE u.organization.id = :organizationId AND u.createdAt < :cutoff "
            + "AND NOT EXISTS (SELECT 1 FROM Attachment a WHERE a.entityType = :entityType AND a.entityUuid = u.id)")
    int deleteEmptyOlderThan(@Param("organizationId") Long organizationId, @Param("entityType") String entityType,
                             @Param("cutoff") LocalDateTime cutoff);
}
