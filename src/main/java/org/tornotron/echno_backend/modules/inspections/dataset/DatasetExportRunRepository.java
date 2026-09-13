package org.tornotron.echno_backend.modules.inspections.dataset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetExportRunRepository extends JpaRepository<DatasetExportRun, UUID> {

    List<DatasetExportRun> findByOrganization_IdOrderByStartedAtDesc(Long organizationId);

    Optional<DatasetExportRun> findByIdAndOrganization_Id(UUID id, Long organizationId);
}
