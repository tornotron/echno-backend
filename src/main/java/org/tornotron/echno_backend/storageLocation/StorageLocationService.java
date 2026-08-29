package org.tornotron.echno_backend.storageLocation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.storageLocation.mapper.StorageLocationMapper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.StorageLocationItemCounts;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationUpdateDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.Collection;
import java.util.List;

@Service
public class StorageLocationService {

    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final StorageLocationMapper storageLocationMapper;
    private final InventoryService inventoryService;

    public StorageLocationService(StorageLocationRepository storageLocationRepository,
                                  ProjectRepository projectRepository,
                                  TenantEntityHelper tenantEntityHelper, StorageLocationMapper storageLocationMapper,
                                  InventoryService inventoryService) {
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.storageLocationMapper = storageLocationMapper;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public StorageLocationDto createStorageLocation(StorageLocationCreationDto creationDto) {
        // Check for duplicate name within organization
        if (storageLocationRepository.existsByLocationNameAndOrganization_Id(
                creationDto.getLocationName(), TenantContext.getCurrentOrgId())) {
            throw new DuplicateResourceException(
                    "Storage location with name '" + creationDto.getLocationName() + "' already exists");
        }

        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setLocationName(creationDto.getLocationName());
        storageLocation.setCapacity(creationDto.getCapacity());
        storageLocation.setLocationType(StorageLocationType.valueOf(creationDto.getLocationType()));
        storageLocation.setAddress(creationDto.getAddress());
        storageLocation.setLatitude(creationDto.getLatitude());
        storageLocation.setLongitude(creationDto.getLongitude());
        storageLocation.setActive(creationDto.isActive());
        storageLocation.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Project is optional - central warehouses/godowns may serve multiple projects
        if (creationDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(
                            creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project with ID " + creationDto.getProjectId() + " was not found in this organization"));
            storageLocation.setProject(project);
        }

        storageLocation = storageLocationRepository.save(storageLocation);
        return storageLocationMapper.toDto(storageLocation, itemCountsFor(List.of(storageLocation)));
    }

    @Transactional(readOnly = true)
    public StorageLocationDto getStorageLocationById(Long id) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + id + " was not found in this organization"));
        return storageLocationMapper.toDto(storageLocation, itemCountsFor(List.of(storageLocation)));
    }


    @Transactional(readOnly = true)
    public Page<StorageLocationDto> getAllStorageLocations(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "locationName"));
        Page<StorageLocation> locations = storageLocationRepository.findAll(pageable);
        StorageLocationItemCounts itemCounts = itemCountsFor(locations.getContent());
        return locations.map(storageLocation -> storageLocationMapper.toDto(storageLocation, itemCounts));
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByProject(Long projectId) {
        return toDtos(storageLocationRepository.findByProjectId(projectId));
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByType(StorageLocationType locationType) {
        return toDtos(storageLocationRepository.findByLocationType(locationType));
    }

    @Transactional
    public StorageLocationDto updateStorageLocation(Long id, StorageLocationUpdateDto updateDto) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + id + " was not found in this organization"));

        if (updateDto.getLocationName() != null) {
            // Check for duplicate name only if name is changing
            if (!updateDto.getLocationName().equals(storageLocation.getLocationName()) &&
                    storageLocationRepository.existsByLocationNameAndOrganization_Id(
                            updateDto.getLocationName(), TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException(
                        "Storage location with name '" + updateDto.getLocationName() + "' already exists");
            }
            storageLocation.setLocationName(updateDto.getLocationName());
        }

        if (updateDto.getLocationType() != null) {
            storageLocation.setLocationType(StorageLocationType.valueOf(updateDto.getLocationType()));
        }

        if (updateDto.getAddress() != null) {
            storageLocation.setAddress(updateDto.getAddress());
        }

        if (updateDto.getCapacity() != null) {
            storageLocation.setCapacity(updateDto.getCapacity());
        }

        if(updateDto.getLatitude()!=null){
            storageLocation.setLatitude(updateDto.getLatitude());
        }

        if(updateDto.getLongitude()!=null){
            storageLocation.setLongitude(updateDto.getLongitude());
        }

        if (updateDto.getIsActive() != null) {
            storageLocation.setActive(updateDto.getIsActive());
        }

        if (updateDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(
                            updateDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project with ID " + updateDto.getProjectId() + " was not found in this organization"));
            storageLocation.setProject(project);
        }

        storageLocation = storageLocationRepository.save(storageLocation);
        return storageLocationMapper.toDto(storageLocation, itemCountsFor(List.of(storageLocation)));
    }

    @Transactional
    public void deleteStorageLocation(Long id) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + id + " was not found in this organization"));
        storageLocationRepository.delete(storageLocation);
    }

    /**
     * Converts a list of storage locations, counting the materials at all of them once.
     *
     * @param locations The locations to convert.
     * @return The locations as DTOs.
     */
    private List<StorageLocationDto> toDtos(List<StorageLocation> locations) {
        StorageLocationItemCounts itemCounts = itemCountsFor(locations);
        return locations.stream()
                .map(storageLocation -> storageLocationMapper.toDto(storageLocation, itemCounts))
                .toList();
    }

    /**
     * Counts the distinct materials at every location about to be mapped, in one query.
     *
     * <p>This is the batched read that replaced the count the mapper used to issue for itself,
     * once per row, on each of the listing paths above.
     *
     * @param locations The locations being converted.
     * @return Their item counts, with anything unstocked reading as zero.
     */
    private StorageLocationItemCounts itemCountsFor(Collection<StorageLocation> locations) {
        return inventoryService.itemCountsAt(locations.stream().map(StorageLocation::getId).toList());
    }
}
