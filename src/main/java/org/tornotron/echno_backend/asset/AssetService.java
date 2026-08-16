package org.tornotron.echno_backend.asset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.asset.dto.AssetCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.asset.mapper.AssetMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.StorageLocationRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AssetService {

    private final AssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final VendorRepository vendorRepository;
    private final StorageLocationRepository storageLocationRepository;

    public AssetService(AssetRepository assetRepository,
                        AssetMapper assetMapper,
                        TenantEntityHelper tenantEntityHelper,
                        VendorRepository vendorRepository,
                        StorageLocationRepository storageLocationRepository) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.vendorRepository = vendorRepository;
        this.storageLocationRepository = storageLocationRepository;
    }

    @Transactional
    public AssetDto createAsset(AssetCreationDto creationDto) {
        Asset asset = new Asset();
        applyFields(asset, creationDto);
        asset.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return assetMapper.toDto(assetRepository.save(asset));
    }

    @Transactional(readOnly = true)
    public AssetDto getAssetById(Long id) {
        Asset asset = assetRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset with ID " + id + " was not found in this organization"));
        return assetMapper.toDto(asset);
    }

    @Transactional(readOnly = true)
    public List<AssetDto> getAllAssets() {
        return assetRepository.findAll().stream()
                .map(asset -> assetMapper.toDto(asset))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<AssetDto> getAllAssets(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return assetRepository.findAll(pageable)
                .map(asset -> assetMapper.toDto(asset));
    }

    @Transactional
    public AssetDto updateAsset(Long id, AssetCreationDto creationDto) {
        Asset asset = assetRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset with ID " + id + " was not found in this organization"));
        applyFields(asset, creationDto);
        return assetMapper.toDto(assetRepository.save(asset));
    }

    @Transactional
    public void deleteAsset(Long id) {
        Asset asset = assetRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset with ID " + id + " was not found in this organization"));
        assetRepository.delete(asset);
    }

    /** Copies the mutable fields from the creation DTO onto the asset, resolving the vendor and location. */
    private void applyFields(Asset asset, AssetCreationDto dto) {
        asset.setAssetId(dto.getAssetId());
        asset.setName(dto.getName());
        asset.setDescription(dto.getDescription());
        asset.setType(dto.getType());
        asset.setCategory(dto.getCategory());
        asset.setStatus(dto.getStatus());
        asset.setAssetCondition(dto.getAssetCondition());
        asset.setPurchaseDate(dto.getPurchaseDate());
        asset.setPurchasePrice(dto.getPurchasePrice());
        asset.setCurrentValue(dto.getCurrentValue());
        asset.setDepreciationRate(dto.getDepreciationRate());
        asset.setAssignedTo(dto.getAssignedTo());
        asset.setAssignedProject(dto.getAssignedProject());
        asset.setManufacturer(dto.getManufacturer());
        asset.setModel(dto.getModel());
        asset.setSerialNumber(dto.getSerialNumber());
        asset.setRegistrationNumber(dto.getRegistrationNumber());
        asset.setWarrantyExpiry(dto.getWarrantyExpiry());
        asset.setLastMaintenanceDate(dto.getLastMaintenanceDate());
        asset.setNextMaintenanceDate(dto.getNextMaintenanceDate());
        asset.setInsuranceExpiry(dto.getInsuranceExpiry());
        asset.setMaintenanceSchedule(dto.getMaintenanceSchedule());
        asset.setUsageHours(dto.getUsageHours());
        asset.setMaxUsageHours(dto.getMaxUsageHours());
        asset.setFuelType(dto.getFuelType());
        asset.setInsuranceProvider(dto.getInsuranceProvider());
        asset.setPolicyNumber(dto.getPolicyNumber());
        asset.setNotes(dto.getNotes());

        Vendor vendor = null;
        if (dto.getVendorId() != null) {
            vendor = vendorRepository.findByIdAndOrganization_Id(dto.getVendorId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + dto.getVendorId() + " was not found in this organization"));
        }
        asset.setVendor(vendor);

        StorageLocation location = null;
        if (dto.getLocationId() != null) {
            location = storageLocationRepository.findByIdAndOrganization_Id(dto.getLocationId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Storage location with ID " + dto.getLocationId() + " was not found in this organization"));
        }
        asset.setLocation(location);
    }
}
