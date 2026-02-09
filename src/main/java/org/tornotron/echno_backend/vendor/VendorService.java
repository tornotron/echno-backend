package org.tornotron.echno_backend.vendor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.VendorDtoConvertor;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.vendor.dto.VendorCreationDto;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final TenantEntityHelper tenantEntityHelper;

    public VendorService(VendorRepository vendorRepository,
                        TenantEntityHelper tenantEntityHelper) {
        this.vendorRepository = vendorRepository;
        this.tenantEntityHelper = tenantEntityHelper;
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

        vendor = vendorRepository.save(vendor);
        return VendorDtoConvertor.convertToDto(vendor);
    }

    @Transactional(readOnly = true)
    public VendorDto getVendorById(Long id) {
        Vendor vendor = vendorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));
        return VendorDtoConvertor.convertToDto(vendor);
    }

    @Transactional(readOnly = true)
    public List<VendorDto> getAllVendors() {
        return vendorRepository.findAll().stream()
                .map(VendorDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<VendorDto> getAllVendors(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "vendorName"));
        return vendorRepository.findAll(pageable)
                .map(VendorDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<VendorDto> searchVendorsByName(String name) {
        return vendorRepository.findByVendorNameContainingIgnoreCase(name).stream()
                .map(VendorDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorDto updateVendor(Long id, VendorCreationDto updateDto) {
        Vendor vendor = vendorRepository.findById(id)
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
        return VendorDtoConvertor.convertToDto(vendor);
    }

    @Transactional
    public void deleteVendor(Long id) {
        if (!vendorRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vendor not found with id: " + id);
        }
        vendorRepository.deleteById(id);
    }
}
