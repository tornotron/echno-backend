package org.tornotron.echno_backend.storageLocation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;
import java.util.Optional;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {

    Optional<StorageLocation> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<StorageLocation> findByProjectId(Long projectId);

    List<StorageLocation> findByLocationType(StorageLocationType locationType);

    boolean existsByLocationNameAndOrganization_Id(String locationName, Long organizationId);
}
