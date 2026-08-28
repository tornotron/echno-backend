package org.tornotron.echno_backend.vendor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.vendor.mapper.VendorMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.vendor.dto.*;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD, listing, and search for vendors, plus forwarding to the sub-entity CRUD.
 *
 * <p>Manages the vendor aggregate: on create it maps the vendor fields and any nested
 * contacts, tax identifiers, bank accounts, and payment terms, stamping each with the
 * current organization. Vendor email is unique within an organization. Contact,
 * tax-identifier, bank-account, and payment-terms operations delegate to
 * {@link VendorSubEntityService} so controllers talk to a single service.
 */
@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final VendorMapper vendorMapper;
    private final VendorSubEntityService vendorSubEntityService;

    public VendorService(VendorRepository vendorRepository,
                         TenantEntityHelper tenantEntityHelper,
                         VendorMapper vendorMapper,
                         VendorSubEntityService vendorSubEntityService) {
        this.vendorRepository = vendorRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.vendorMapper = vendorMapper;
        this.vendorSubEntityService = vendorSubEntityService;
    }

    // ==================== Vendor CRUD ====================

    /**
     * Creates a vendor together with any nested contacts, tax identifiers, bank accounts,
     * and payment terms, all scoped to the current organization.
     *
     * @param creationDto The vendor details and optional child entities to create.
     * @return The created vendor DTO.
     * @throws DuplicateResourceException if a vendor with the same email already exists in the organization.
     */
    @Transactional
    public VendorDto createVendor(VendorCreationDto creationDto) {
        if (vendorRepository.existsByVendorEmailAndOrganization_Id(creationDto.getVendorEmail(),TenantContext.getCurrentOrgId())) {
            throw new DuplicateResourceException("Vendor with email '" + creationDto.getVendorEmail() + "' already exists in this organization");
        }

        Vendor vendor = new Vendor();
        mapVendorFields(vendor, creationDto);
        vendor.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Add nested child entities if provided
        if (creationDto.getContacts() != null) {
            for (VendorContactCreationDto contactDto : creationDto.getContacts()) {
                VendorContact contact = mapToContactEntity(contactDto);
                contact.setOrganization(vendor.getOrganization());
                vendor.addContact(contact);
            }
        }

        if (creationDto.getTaxIdentifiers() != null) {
            for (VendorTaxIdentifierCreationDto taxDto : creationDto.getTaxIdentifiers()) {
                VendorTaxIdentifier taxId = mapToTaxIdentifierEntity(taxDto);
                taxId.setOrganization(vendor.getOrganization());
                vendor.addTaxIdentifier(taxId);
            }
        }

        if (creationDto.getBankAccounts() != null) {
            for (VendorBankAccountCreationDto bankDto : creationDto.getBankAccounts()) {
                VendorBankAccount bankAccount = mapToBankAccountEntity(bankDto);
                bankAccount.setOrganization(vendor.getOrganization());
                vendor.addBankAccount(bankAccount);
            }
        }

        if (creationDto.getPaymentTerms() != null) {
            VendorPaymentTerms terms = mapToPaymentTermsEntity(creationDto.getPaymentTerms());
            terms.setOrganization(vendor.getOrganization());
            vendor.setPaymentTerms(terms);
        }

        vendor = vendorRepository.save(vendor);
        return vendorMapper.toDto(vendor);
    }

    /**
     * Retrieves a single vendor by its ID within the current organization.
     *
     * @param id The ID of the vendor to retrieve.
     * @return The vendor DTO.
     * @throws ResourceNotFoundException if no vendor with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public VendorDto getVendorById(Long id) {
        Vendor vendor = findVendorByIdAndOrg(id);
        return vendorMapper.toDto(vendor);
    }


    /**
     * Returns a page of vendors ordered by name.
     *
     * @param pageNo The zero-based page index.
     * @param pageSize The number of vendors per page.
     * @return A page of vendor DTOs sorted ascending by vendor name.
     */
    @Transactional(readOnly = true)
    public Page<VendorDto> getAllVendors(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "vendorName"));
        return vendorRepository.findAll(pageable)
                .map(vendor -> vendorMapper.toDto(vendor));
    }

    /**
     * Finds vendors whose name contains the given text, case-insensitively.
     *
     * @param name The substring to match against vendor names.
     * @return The list of matching vendor DTOs.
     */
    @Transactional(readOnly = true)
    public List<VendorDto> searchVendorsByName(String name) {
        return vendorRepository.findByVendorNameContainingIgnoreCase(name).stream()
                .map(vendor -> vendorMapper.toDto(vendor))
                .collect(Collectors.toList());
    }

    /**
     * Updates a vendor's own fields (child entities are managed separately).
     *
     * @param id The ID of the vendor to update.
     * @param updateDto The new vendor field values.
     * @return The updated vendor DTO.
     * @throws ResourceNotFoundException if no vendor with the given ID exists in the organization.
     * @throws DuplicateResourceException if the email is changed to one already used by another vendor in the organization.
     */
    @Transactional
    public VendorDto updateVendor(Long id, VendorCreationDto updateDto) {
        Vendor vendor = findVendorByIdAndOrg(id);

        // Check email uniqueness if changed
        if (!updateDto.getVendorEmail().equals(vendor.getVendorEmail())) {
            if (vendorRepository.existsByVendorEmailAndOrganization_Id(updateDto.getVendorEmail(),TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException("Vendor with email '" + updateDto.getVendorEmail() + "' already exists in this organization");
            }
        }

        mapVendorFields(vendor, updateDto);
        vendor = vendorRepository.save(vendor);
        return vendorMapper.toDto(vendor);
    }

    /**
     * Deletes a vendor within the current organization.
     *
     * @param id The ID of the vendor to delete.
     * @throws ResourceNotFoundException if no vendor with the given ID exists in the organization.
     */
    @Transactional
    public void deleteVendor(Long id) {
        if (!vendorRepository.existsByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Vendor with ID " + id + " was not found in this organization");
        }
        vendorRepository.deleteByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId());
    }

    // ========== Vendor child entities — delegated to VendorSubEntityService ==========
    // The contact / tax-identifier / bank-account / payment-terms CRUD lives in
    // VendorSubEntityService; these forwarders keep the controllers talking to a
    // single VendorService.

    public List<VendorContactDto> getContactsByVendorId(Long vendorId) {
        return vendorSubEntityService.getContactsByVendorId(vendorId);
    }

    public VendorContactDto addContact(Long vendorId, VendorContactCreationDto dto) {
        return vendorSubEntityService.addContact(vendorId, dto);
    }

    public VendorContactDto updateContact(Long vendorId, Long contactId, VendorContactCreationDto dto) {
        return vendorSubEntityService.updateContact(vendorId, contactId, dto);
    }

    public void deleteContact(Long vendorId, Long contactId) {
        vendorSubEntityService.deleteContact(vendorId, contactId);
    }

    public List<VendorTaxIdentifierDto> getTaxIdentifiersByVendorId(Long vendorId) {
        return vendorSubEntityService.getTaxIdentifiersByVendorId(vendorId);
    }

    public VendorTaxIdentifierDto addTaxIdentifier(Long vendorId, VendorTaxIdentifierCreationDto dto) {
        return vendorSubEntityService.addTaxIdentifier(vendorId, dto);
    }

    public VendorTaxIdentifierDto updateTaxIdentifier(Long vendorId, Long taxIdId, VendorTaxIdentifierCreationDto dto) {
        return vendorSubEntityService.updateTaxIdentifier(vendorId, taxIdId, dto);
    }

    public void deleteTaxIdentifier(Long vendorId, Long taxIdId) {
        vendorSubEntityService.deleteTaxIdentifier(vendorId, taxIdId);
    }

    public List<VendorBankAccountDto> getBankAccountsByVendorId(Long vendorId) {
        return vendorSubEntityService.getBankAccountsByVendorId(vendorId);
    }

    public VendorBankAccountDto addBankAccount(Long vendorId, VendorBankAccountCreationDto dto) {
        return vendorSubEntityService.addBankAccount(vendorId, dto);
    }

    public VendorBankAccountDto updateBankAccount(Long vendorId, Long accountId, VendorBankAccountCreationDto dto) {
        return vendorSubEntityService.updateBankAccount(vendorId, accountId, dto);
    }

    public void deleteBankAccount(Long vendorId, Long accountId) {
        vendorSubEntityService.deleteBankAccount(vendorId, accountId);
    }

    public VendorPaymentTermsDto getPaymentTermsByVendorId(Long vendorId) {
        return vendorSubEntityService.getPaymentTermsByVendorId(vendorId);
    }

    public VendorPaymentTermsDto setPaymentTerms(Long vendorId, VendorPaymentTermsCreationDto dto) {
        return vendorSubEntityService.setPaymentTerms(vendorId, dto);
    }

    public void deletePaymentTerms(Long vendorId) {
        vendorSubEntityService.deletePaymentTerms(vendorId);
    }

    // ==================== Helper Methods ====================

    private Vendor findVendorByIdAndOrg(Long id) {
        return vendorRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + id + " was not found in this organization"));
    }

    private void mapVendorFields(Vendor vendor, VendorCreationDto dto) {
        vendor.setVendorName(dto.getVendorName());
        vendor.setVendorAddress(dto.getVendorAddress());
        vendor.setVendorEmail(dto.getVendorEmail());
        vendor.setCity(dto.getCity());
        vendor.setState(dto.getState());
        vendor.setPinCode(dto.getPinCode());
        vendor.setCountry(dto.getCountry());
        vendor.setWebsite(dto.getWebsite());
        vendor.setType(VendorType.valueOf(dto.getType()));
        vendor.setStatus(VendorStatus.valueOf(dto.getStatus()));
        vendor.setNotes(dto.getNotes());
    }

    private VendorContact mapToContactEntity(VendorContactCreationDto dto) {
        VendorContact contact = new VendorContact();
        contact.setContactPerson(dto.getContactPerson());
        contact.setEmail(dto.getEmail());
        contact.setPhone(dto.getPhone());
        contact.setAlternatePhone(dto.getAlternatePhone());
        contact.setPrimary(dto.isPrimary());
        return contact;
    }

    private VendorTaxIdentifier mapToTaxIdentifierEntity(VendorTaxIdentifierCreationDto dto) {
        VendorTaxIdentifier taxId = new VendorTaxIdentifier();
        taxId.setType(TaxIdentifierType.valueOf(dto.getType()));
        taxId.setValue(dto.getValue());
        return taxId;
    }

    private VendorBankAccount mapToBankAccountEntity(VendorBankAccountCreationDto dto) {
        VendorBankAccount account = new VendorBankAccount();
        account.setBankName(dto.getBankName());
        account.setAccountNumber(dto.getAccountNumber());
        account.setIfscCode(dto.getIfscCode());
        account.setAccountHolderName(dto.getAccountHolderName());
        account.setSwift(dto.getSwift());
        account.setDefault(dto.isDefault());
        return account;
    }

    private VendorPaymentTerms mapToPaymentTermsEntity(VendorPaymentTermsCreationDto dto) {
        VendorPaymentTerms terms = new VendorPaymentTerms();
        terms.setPaymentTerms(dto.getPaymentTerms());
        terms.setCreditLimit(dto.getCreditLimit());
        terms.setCreditDays(dto.getCreditDays());
        return terms;
    }
}
