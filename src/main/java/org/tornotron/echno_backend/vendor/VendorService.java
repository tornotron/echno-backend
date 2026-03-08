package org.tornotron.echno_backend.vendor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.*;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.vendor.dto.*;
import org.tornotron.echno_backend.vendor.enums.PaymentTermsType;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;
    private final VendorContactRepository vendorContactRepository;
    private final VendorBankAccountRepository vendorBankAccountRepository;
    private final VendorTaxIdentifierRepository vendorTaxIdentifierRepository;
    private final VendorPaymentTermsRepository vendorPaymentTermsRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final FileStorageService fileStorageService;

    public VendorService(VendorRepository vendorRepository,
                         VendorContactRepository vendorContactRepository,
                         VendorBankAccountRepository vendorBankAccountRepository,
                         VendorTaxIdentifierRepository vendorTaxIdentifierRepository,
                         VendorPaymentTermsRepository vendorPaymentTermsRepository,
                         TenantEntityHelper tenantEntityHelper,
                         FileStorageService fileStorageService) {
        this.vendorRepository = vendorRepository;
        this.vendorContactRepository = vendorContactRepository;
        this.vendorBankAccountRepository = vendorBankAccountRepository;
        this.vendorTaxIdentifierRepository = vendorTaxIdentifierRepository;
        this.vendorPaymentTermsRepository = vendorPaymentTermsRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.fileStorageService = fileStorageService;
    }

    // ==================== Vendor CRUD ====================

    @Transactional
    public VendorDto createVendor(VendorCreationDto creationDto) {
        if (vendorRepository.existsByVendorEmail(creationDto.getVendorEmail())) {
            throw new DuplicateResourceException("Vendor with email " + creationDto.getVendorEmail() + " already exists");
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
        return VendorDtoConvertor.convertToDto(vendor, fileStorageService);
    }

    @Transactional(readOnly = true)
    public VendorDto getVendorById(Long id) {
        Vendor vendor = findVendorByIdAndOrg(id);
        return VendorDtoConvertor.convertToDto(vendor, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<VendorDto> getAllVendors() {
        return vendorRepository.findAll().stream()
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<VendorDto> getAllVendors(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "vendorName"));
        return vendorRepository.findAll(pageable)
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor, fileStorageService));
    }

    @Transactional(readOnly = true)
    public List<VendorDto> searchVendorsByName(String name) {
        return vendorRepository.findByVendorNameContainingIgnoreCase(name).stream()
                .map(vendor -> VendorDtoConvertor.convertToDto(vendor, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorDto updateVendor(Long id, VendorCreationDto updateDto) {
        Vendor vendor = findVendorByIdAndOrg(id);

        // Check email uniqueness if changed
        if (!updateDto.getVendorEmail().equals(vendor.getVendorEmail())) {
            if (vendorRepository.existsByVendorEmail(updateDto.getVendorEmail())) {
                throw new DuplicateResourceException("Vendor with email " + updateDto.getVendorEmail() + " already exists");
            }
        }

        mapVendorFields(vendor, updateDto);
        vendor = vendorRepository.save(vendor);
        return VendorDtoConvertor.convertToDto(vendor, fileStorageService);
    }

    @Transactional
    public void deleteVendor(Long id) {
        if (!vendorRepository.existsByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Vendor not found with id: " + id);
        }
        vendorRepository.deleteByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId());
    }

    // ==================== Contact CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorContactDto> getContactsByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorContactRepository.findByVendor_Id(vendorId).stream()
                .map(VendorContactDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorContactDto addContact(Long vendorId, VendorContactCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorContact contact = mapToContactEntity(dto);
        contact.setOrganization(vendor.getOrganization());
        vendor.addContact(contact);
        vendorRepository.save(vendor);
        return VendorContactDtoConvertor.convertToDto(contact);
    }

    @Transactional
    public VendorContactDto updateContact(Long vendorId, Long contactId, VendorContactCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorContact contact = vendorContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found with id: " + contactId));
        if (!contact.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Contact not found with id: " + contactId + " for vendor: " + vendorId);
        }
        contact.setContactPerson(dto.getContactPerson());
        contact.setEmail(dto.getEmail());
        contact.setPhone(dto.getPhone());
        contact.setAlternatePhone(dto.getAlternatePhone());
        contact.setPrimary(dto.isPrimary());
        contact = vendorContactRepository.save(contact);
        return VendorContactDtoConvertor.convertToDto(contact);
    }

    @Transactional
    public void deleteContact(Long vendorId, Long contactId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorContact contact = vendorContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found with id: " + contactId));
        if (!contact.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Contact not found with id: " + contactId + " for vendor: " + vendorId);
        }
        vendor.getContacts().remove(contact);
        vendorContactRepository.delete(contact);
    }

    // ==================== Tax Identifier CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorTaxIdentifierDto> getTaxIdentifiersByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorTaxIdentifierRepository.findByVendor_Id(vendorId).stream()
                .map(VendorTaxIdentifierDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorTaxIdentifierDto addTaxIdentifier(Long vendorId, VendorTaxIdentifierCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = mapToTaxIdentifierEntity(dto);
        taxId.setOrganization(vendor.getOrganization());
        vendor.addTaxIdentifier(taxId);
        vendorRepository.save(vendor);
        return VendorTaxIdentifierDtoConvertor.convertToDto(taxId);
    }

    @Transactional
    public VendorTaxIdentifierDto updateTaxIdentifier(Long vendorId, Long taxIdId, VendorTaxIdentifierCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = vendorTaxIdentifierRepository.findById(taxIdId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax identifier not found with id: " + taxIdId));
        if (!taxId.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Tax identifier not found with id: " + taxIdId + " for vendor: " + vendorId);
        }
        taxId.setType(TaxIdentifierType.valueOf(dto.getType()));
        taxId.setValue(dto.getValue());
        taxId = vendorTaxIdentifierRepository.save(taxId);
        return VendorTaxIdentifierDtoConvertor.convertToDto(taxId);
    }

    @Transactional
    public void deleteTaxIdentifier(Long vendorId, Long taxIdId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = vendorTaxIdentifierRepository.findById(taxIdId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax identifier not found with id: " + taxIdId));
        if (!taxId.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Tax identifier not found with id: " + taxIdId + " for vendor: " + vendorId);
        }
        vendor.getTaxIdentifiers().remove(taxId);
        vendorTaxIdentifierRepository.delete(taxId);
    }

    // ==================== Bank Account CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorBankAccountDto> getBankAccountsByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorBankAccountRepository.findByVendor_Id(vendorId).stream()
                .map(VendorBankAccountsDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorBankAccountDto addBankAccount(Long vendorId, VendorBankAccountCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = mapToBankAccountEntity(dto);
        account.setOrganization(vendor.getOrganization());
        vendor.addBankAccount(account);
        vendorRepository.save(vendor);
        return VendorBankAccountsDtoConvertor.convertToDto(account);
    }

    @Transactional
    public VendorBankAccountDto updateBankAccount(Long vendorId, Long accountId, VendorBankAccountCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = vendorBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found with id: " + accountId));
        if (!account.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Bank account not found with id: " + accountId + " for vendor: " + vendorId);
        }
        account.setBankName(dto.getBankName());
        account.setAccountNumber(dto.getAccountNumber());
        account.setIfscCode(dto.getIfscCode());
        account.setAccountHolderName(dto.getAccountHolderName());
        account.setSwift(dto.getSwift());
        account.setDefault(dto.isDefault());
        account = vendorBankAccountRepository.save(account);
        return VendorBankAccountsDtoConvertor.convertToDto(account);
    }

    @Transactional
    public void deleteBankAccount(Long vendorId, Long accountId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = vendorBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found with id: " + accountId));
        if (!account.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Bank account not found with id: " + accountId + " for vendor: " + vendorId);
        }
        vendor.getBankAccounts().remove(account);
        vendorBankAccountRepository.delete(account);
    }

    // ==================== Payment Terms CRUD ====================

    @Transactional(readOnly = true)
    public VendorPaymentTermsDto getPaymentTermsByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        VendorPaymentTerms terms = vendorPaymentTermsRepository.findByVendor_Id(vendorId)
                .orElse(null);
        return VendorPaymentTermsDtoConvertor.convertToDto(terms);
    }

    @Transactional
    public VendorPaymentTermsDto setPaymentTerms(Long vendorId, VendorPaymentTermsCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);

        VendorPaymentTerms terms = vendorPaymentTermsRepository.findByVendor_Id(vendorId)
                .orElse(null);

        if (terms == null) {
            terms = mapToPaymentTermsEntity(dto);
            terms.setOrganization(vendor.getOrganization());
            vendor.setPaymentTerms(terms);
            vendorRepository.save(vendor);
        } else {
            terms.setPaymentTerms(PaymentTermsType.valueOf(dto.getPaymentTerms()));
            terms.setCreditLimit(dto.getCreditLimit());
            terms.setCreditDays(dto.getCreditDays());
            vendorPaymentTermsRepository.save(terms);
        }
        return VendorPaymentTermsDtoConvertor.convertToDto(terms);
    }

    @Transactional
    public void deletePaymentTerms(Long vendorId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorPaymentTerms terms = vendorPaymentTermsRepository.findByVendor_Id(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment terms not found for vendor: " + vendorId));
        vendor.setPaymentTerms(null);
        vendorPaymentTermsRepository.delete(terms);
    }

    // ==================== Helper Methods ====================

    private Vendor findVendorByIdAndOrg(Long id) {
        return vendorRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));
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
        terms.setPaymentTerms(PaymentTermsType.valueOf(dto.getPaymentTerms()));
        terms.setCreditLimit(dto.getCreditLimit());
        terms.setCreditDays(dto.getCreditDays());
        return terms;
    }
}
