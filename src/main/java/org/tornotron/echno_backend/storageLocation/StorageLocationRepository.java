package org.tornotron.echno_backend.storageLocation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {


    Optional<StorageLocation> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<StorageLocation> findByProjectId(Long projectId);

    Page<StorageLocation> findByProjectId(Long projectId, Pageable pageable);

    List<StorageLocation> findByLocationType(StorageLocationType locationType);

    Page<StorageLocation> findByLocationType(StorageLocationType locationType, Pageable pageable);

    boolean existsByLocationNameAndOrganization_Id(String locationName, Long organizationId);
}
