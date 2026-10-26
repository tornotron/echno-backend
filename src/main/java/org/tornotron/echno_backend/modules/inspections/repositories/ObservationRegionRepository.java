package org.tornotron.echno_backend.modules.inspections.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationRegion;

/** Regions are read through JPQL only, so the Hibernate orgFilter applies to every query. */
public interface ObservationRegionRepository extends JpaRepository<ObservationRegion, UUID> {

    /** Every row of one observation, retired ones included, in draw order. */
    @Query("SELECT r FROM ObservationRegion r WHERE r.observationId = :observationId "
            + "ORDER BY r.lineOrder, r.createdAt")
    List<ObservationRegion> findByObservationScoped(@Param("observationId") UUID observationId);

    /** The active rows of a page of observations, for the list read. */
    @Query("SELECT r FROM ObservationRegion r WHERE r.observationId IN :observationIds AND r.retiredAt IS NULL "
            + "ORDER BY r.lineOrder, r.createdAt")
    List<ObservationRegion> findActiveByObservationsScoped(@Param("observationIds") Collection<UUID> observationIds);

    /** Observations whose regions, active or retired, are drawn on the attachment. */
    @Query("SELECT DISTINCT r.observationId FROM ObservationRegion r WHERE r.attachmentId = :attachmentId")
    List<UUID> findObservationIdsByAttachmentScoped(@Param("attachmentId") Long attachmentId);
}
