package org.tornotron.echno_backend.material.threshold;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialLocationThresholdRepository extends JpaRepository<MaterialLocationThreshold, Long> {

    List<MaterialLocationThreshold> findByMaterial_IdAndOrganization_Id(Long materialId, Long organizationId);

    Optional<MaterialLocationThreshold> findByMaterial_IdAndStorageLocation_IdAndOrganization_Id(
            Long materialId, Long storageLocationId, Long organizationId);
}
