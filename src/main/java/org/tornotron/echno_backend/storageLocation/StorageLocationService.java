package org.tornotron.echno_backend.storageLocation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.StorageLocationDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class StorageLocationService {

    private final StorageLocationRepository storageLocationRepository;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;

    public StorageLocationService(StorageLocationRepository storageLocationRepository,
                                   ProjectRepository projectRepository,
                                   TenantEntityHelper tenantEntityHelper) {
        this.storageLocationRepository = storageLocationRepository;
        this.projectRepository = projectRepository;
        this.tenantEntityHelper = tenantEntityHelper;
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
        storageLocation.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Project is optional - central warehouses/godowns may serve multiple projects
        if (creationDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(
                            creationDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project not found with id: " + creationDto.getProjectId()));
            storageLocation.setProject(project);
        }

        storageLocation = storageLocationRepository.save(storageLocation);
        return StorageLocationDtoConvertor.convertToDto(storageLocation);
    }

    @Transactional(readOnly = true)
    public StorageLocationDto getStorageLocationById(Long id) {
        StorageLocation storageLocation = storageLocationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found with id: " + id));
        return StorageLocationDtoConvertor.convertToDto(storageLocation);
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getAllStorageLocations() {
        return storageLocationRepository.findAll().stream()
                .map(StorageLocationDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<StorageLocationDto> getAllStorageLocations(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "locationName"));
        return storageLocationRepository.findAll(pageable)
                .map(StorageLocationDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByProject(Long projectId) {
        return storageLocationRepository.findByProjectId(projectId).stream()
                .map(StorageLocationDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StorageLocationDto> getStorageLocationsByType(StorageLocationType locationType) {
        return storageLocationRepository.findByLocationType(locationType).stream()
                .map(StorageLocationDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }
}
