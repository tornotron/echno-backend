package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.inspections.ObservationOutcomeKind;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObservationRepository extends JpaRepository<Observation, UUID>, JpaSpecificationExecutor<Observation> {

    /** Org-scoped lookup by id: JPQL rather than {@code find()} so the Hibernate orgFilter applies. */
    @Query("SELECT o FROM Observation o WHERE o.id = :id")
    Optional<Observation> findByIdScoped(@Param("id") UUID id);

    /** The producer's own reference, scoped to the tenant by the filter; the partial unique index is the guarantee. */
    @Query("SELECT o FROM Observation o WHERE o.externalRef = :externalRef")
    Optional<Observation> findByExternalRefScoped(@Param("externalRef") String externalRef);

    @Query("SELECT o FROM Observation o WHERE o.outcomeKind = :kind AND o.outcomeRef = :ref")
    List<Observation> findByOutcomeScoped(@Param("kind") ObservationOutcomeKind kind, @Param("ref") UUID ref);

    @Query("SELECT o FROM Observation o WHERE o.inspectionId = :inspectionId ORDER BY o.observedAt")
    List<Observation> findByInspectionScoped(@Param("inspectionId") UUID inspectionId);

    /** Organization-explicit existence check, for reads that must not lean on the session filter. */
    boolean existsByIdAndOrganization_Id(UUID id, Long organizationId);
}
