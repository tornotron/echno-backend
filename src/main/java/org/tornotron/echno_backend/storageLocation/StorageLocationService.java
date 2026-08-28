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
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationUpdateDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StorageLocationService {

    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final StorageLocationMapper storageLocationMapper;

    public StorageLocationService(StorageLocationRepository storageLocationRepository,
                                  ProjectRepository projectRepository,
                                  TenantEntityHelper tenantEntityHelper, StorageLocationMapper storageLocationMapper) {
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.storageLocationMapper = storageLocationMapper;
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
        return storageLocationMapper.toDto(storageLocation);
    }

    @Transactional(readOnly = true)
    public StorageLocationDto getStorageLocationById(Long id) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + id + " was not found in this organization"));
        return storageLocationMapper.toDto(storageLocation);
    }


    @Transactional(readOnly = true)
    public Page<StorageLocationDto> getAllStorageLocations(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "locationName"));
        return storageLocationRepository.findAll(pageable)
                .map(storageLocation -> storageLocationMapper.toDto(storageLocation));
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByProject(Long projectId) {
        return storageLocationRepository.findByProjectId(projectId).stream()
                .map(storageLocation -> storageLocationMapper.toDto(storageLocation))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByType(StorageLocationType locationType) {
        return storageLocationRepository.findByLocationType(locationType).stream()
                .map(storageLocation -> storageLocationMapper.toDto(storageLocation))
                .collect(Collectors.toList());
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
        return storageLocationMapper.toDto(storageLocation);
    }

    @Transactional
    public void deleteStorageLocation(Long id) {
        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + id + " was not found in this organization"));
        storageLocationRepository.delete(storageLocation);
    }
}
