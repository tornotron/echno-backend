package org.tornotron.echno_backend.vendor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.VendorDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.vendor.dto.VendorCreationDto;
import org.tornotron.echno_backend.vendor.dto.VendorDto;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final FileStorageService fileStorageService;

    public VendorService(VendorRepository vendorRepository,
                         TenantEntityHelper tenantEntityHelper, FileStorageService fileStorageService) {
        this.vendorRepository = vendorRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public VendorDto createVendor(VendorCreationDto creationDto) {
        // Check for duplicate email
        if (vendorRepository.existsByVendorEmail(creationDto.getVendorEmail())) {
            throw new DuplicateResourceException("Vendor with email " + creationDto.getVendorEmail() + " already exists");
        }

        Vendor vendor = new Vendor();
        vendor.setVendorName(creationDto.getVendorName());
        vendor.setVendorAddress(creationDto.getVendorAddress());
        vendor.setVendorEmail(creationDto.getVendorEmail());
        vendor.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        vendor.setCity(creationDto.getCity());
        vendor.setState(creationDto.getState());
        vendor.setPinCode(creationDto.getPinCode());
        vendor.setCountry(creationDto.getCountry());
        vendor.setWebsite(creationDto.getWebsite());
        vendor.setType(VendorType.valueOf(creationDto.getType()));
        vendor.setStatus(VendorStatus.valueOf(creationDto.getStatus()));
        vendor.setNotes(creationDto.getNotes());

        vendor = vendorRepository.save(vendor);
        return VendorDtoConvertor.convertToDto(vendor,fileStorageService);
    }

    @Transactional(readOnly = true)
    public VendorDto getVendorById(Long id) {
        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));
        return VendorDtoConvertor.convertToDto(vendor,fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<VendorDto> getAllVendors() {
        return vendorRepository.findAll().stream()
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor,fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<VendorDto> getAllVendors(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "vendorName"));
        return vendorRepository.findAll(pageable)
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor,fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<VendorDto> searchVendorsByName(String name) {
        return vendorRepository.findByVendorNameContainingIgnoreCase(name).stream()
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor,fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorDto updateVendor(Long id, VendorCreationDto updateDto) {
        Vendor vendor = vendorRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));

        // Check email uniqueness if changed
        if (!updateDto.getVendorEmail().equals(vendor.getVendorEmail())) {
            if (vendorRepository.existsByVendorEmail(updateDto.getVendorEmail())) {
                throw new DuplicateResourceException("Vendor with email " + updateDto.getVendorEmail() + " already exists");
            }
            vendor.setVendorEmail(updateDto.getVendorEmail());
        }

        vendor.setVendorName(updateDto.getVendorName());
        vendor.setVendorAddress(updateDto.getVendorAddress());

        vendor = vendorRepository.save(vendor);
        return VendorDtoConvertor.convertToDto(vendor,fileStorageService);
    }

    @Transactional
    public void deleteVendor(Long id) {
        if (!vendorRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Vendor not found with id: " + id);
        }
        vendorRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }
}
