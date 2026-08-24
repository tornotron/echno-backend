package org.tornotron.echno_backend.material.threshold;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.material.threshold.dto.MaterialLocationThresholdDto;
import org.tornotron.echno_backend.material.threshold.dto.MaterialLocationThresholdUpsertDto;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages per-location overrides of a material's planning thresholds.
 *
 * <p>Each override lets a storage location carry its own minimum order quantity, reorder level,
 * and safety, minimum and maximum stock, in place of the material's global levels. Every read and
 * write is scoped to the current tenant, and both the material and storage location are checked to
 * belong to that tenant before an override is created or changed.
 */
@Service
public class MaterialLocationThresholdService {

    private final MaterialLocationThresholdRepository thresholdRepository;
    private final MaterialRepository materialRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final TenantEntityHelper tenantEntityHelper;

    public MaterialLocationThresholdService(MaterialLocationThresholdRepository thresholdRepository,
                                            MaterialRepository materialRepository,
                                            StorageLocationRepository storageLocationRepository,
                                            TenantEntityHelper tenantEntityHelper) {
        this.thresholdRepository = thresholdRepository;
        this.materialRepository = materialRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    /**
     * Lists every per-location threshold override for a material within the current tenant.
     *
     * @param materialId The material whose overrides to list.
     * @return The material's overrides as DTOs, one per storage location that has an override.
     * @throws ResourceNotFoundException if no material with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public List<MaterialLocationThresholdDto> listForMaterial(Long materialId) {
        materialRepository.findByIdAndOrganization_Id(materialId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Material with ID " + materialId + " was not found in this organization"));

        return thresholdRepository
                .findByMaterial_IdAndOrganization_Id(materialId, TenantContext.getCurrentOrgId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Creates or replaces the threshold override for a material at a storage location.
     *
     * <p>If an override already exists for the material and location it is updated in place;
     * otherwise a new one is created. All five levels are set from the payload, so a field left
     * null clears that level back to the material's global value.
     *
     * @param materialId The material to override thresholds for.
     * @param storageLocationId The storage location the override applies to.
     * @param upsertDto The threshold levels to store for the location.
     * @return The saved override as a DTO.
     * @throws ResourceNotFoundException if the material or storage location is not found in this organization.
     */
    @Transactional
    public MaterialLocationThresholdDto upsert(Long materialId, Long storageLocationId,
                                               MaterialLocationThresholdUpsertDto upsertDto) {
        Long orgId = TenantContext.getCurrentOrgId();

        Material material = materialRepository.findByIdAndOrganization_Id(materialId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Material with ID " + materialId + " was not found in this organization"));

        StorageLocation storageLocation = storageLocationRepository.findByIdAndOrganization_Id(storageLocationId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Storage location with ID " + storageLocationId + " was not found in this organization"));

        MaterialLocationThreshold threshold = thresholdRepository
                .findByMaterial_IdAndStorageLocation_IdAndOrganization_Id(materialId, storageLocationId, orgId)
                .orElseGet(() -> {
                    MaterialLocationThreshold created = new MaterialLocationThreshold();
                    created.setMaterial(material);
                    created.setStorageLocation(storageLocation);
                    created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                    return created;
                });

        threshold.setMinStock(upsertDto.getMinStock());
        threshold.setMaxStock(upsertDto.getMaxStock());
        threshold.setSafetyStock(upsertDto.getSafetyStock());
        threshold.setReorderLevel(upsertDto.getReorderLevel());
        threshold.setMoq(upsertDto.getMoq());

        threshold = thresholdRepository.save(threshold);
        return toDto(threshold);
    }

    /**
     * Deletes a material's threshold override at a storage location.
     *
     * @param materialId The material whose override to delete.
     * @param storageLocationId The storage location the override applies to.
     * @throws ResourceNotFoundException if no override exists for the material and location in this organization.
     */
    @Transactional
    public void delete(Long materialId, Long storageLocationId) {
        MaterialLocationThreshold threshold = thresholdRepository
                .findByMaterial_IdAndStorageLocation_IdAndOrganization_Id(materialId, storageLocationId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No threshold override was found for material " + materialId
                                + " at storage location " + storageLocationId + " in this organization"));
        thresholdRepository.delete(threshold);
    }

    private MaterialLocationThresholdDto toDto(MaterialLocationThreshold threshold) {
        MaterialLocationThresholdDto dto = new MaterialLocationThresholdDto();
        dto.setId(threshold.getId());
        dto.setMaterialId(threshold.getMaterial().getId());
        dto.setStorageLocationId(threshold.getStorageLocation().getId());
        dto.setStorageLocationName(threshold.getStorageLocation().getLocationName());
        dto.setMinStock(threshold.getMinStock());
        dto.setMaxStock(threshold.getMaxStock());
        dto.setSafetyStock(threshold.getSafetyStock());
        dto.setReorderLevel(threshold.getReorderLevel());
        dto.setMoq(threshold.getMoq());
        return dto;
    }
}
