package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.Reinspection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReinspectionRepository extends JpaRepository<Reinspection, UUID> {

    @Query("SELECT r FROM Reinspection r WHERE r.id = :id")
    Optional<Reinspection> findByIdScoped(@Param("id") UUID id);

    List<Reinspection> findByNcrIdAndOrganization_IdOrderBySequenceAsc(UUID ncrId, Long organizationId);

    List<Reinspection> findByDefectIdAndOrganization_IdOrderBySequenceAsc(UUID defectId, Long organizationId);

    @Query("SELECT r.id FROM Reinspection r WHERE r.ncrId = :ncrId AND r.organization.id = :organizationId")
    List<UUID> findIdsByNcrIdAndOrganizationId(@Param("ncrId") UUID ncrId,
                                               @Param("organizationId") Long organizationId);

    long countByNcrIdAndOrganization_Id(UUID ncrId, Long organizationId);

    long countByDefectIdAndOrganization_Id(UUID defectId, Long organizationId);
}
