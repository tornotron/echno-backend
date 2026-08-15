package org.tornotron.echno_backend.vendor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.vendor.dto.*;
import org.tornotron.echno_backend.vendor.enums.PaymentTermsType;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;
import org.tornotron.echno_backend.vendor.mapper.VendorBankAccountMapper;
import org.tornotron.echno_backend.vendor.mapper.VendorContactMapper;
import org.tornotron.echno_backend.vendor.mapper.VendorPaymentTermsMapper;
import org.tornotron.echno_backend.vendor.mapper.VendorTaxIdentifierMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages a vendor's child entities: contacts, tax identifiers, bank accounts,
 * and payment terms. Each is a small, structurally identical CRUD cluster
 * (load the parent vendor, mutate one child collection, save), so they group
 * cleanly here, away from core Vendor CRUD in {@link VendorService}.
 *
 * {@link VendorService} exposes these operations as thin delegates so the
 * controllers keep talking to a single service; the actual logic lives here.
 */
@Service
public class VendorSubEntityService {

    private final VendorRepository vendorRepository;
    private final VendorContactRepository vendorContactRepository;
    private final VendorBankAccountRepository vendorBankAccountRepository;
    private final VendorTaxIdentifierRepository vendorTaxIdentifierRepository;
    private final VendorPaymentTermsRepository vendorPaymentTermsRepository;
    private final VendorContactMapper vendorContactMapper;
    private final VendorTaxIdentifierMapper vendorTaxIdentifierMapper;
    private final VendorBankAccountMapper vendorBankAccountMapper;
    private final VendorPaymentTermsMapper vendorPaymentTermsMapper;

    public VendorSubEntityService(VendorRepository vendorRepository,
                                  VendorContactRepository vendorContactRepository,
                                  VendorBankAccountRepository vendorBankAccountRepository,
                                  VendorTaxIdentifierRepository vendorTaxIdentifierRepository,
                                  VendorPaymentTermsRepository vendorPaymentTermsRepository,
                                  VendorContactMapper vendorContactMapper,
                                  VendorTaxIdentifierMapper vendorTaxIdentifierMapper,
                                  VendorBankAccountMapper vendorBankAccountMapper,
                                  VendorPaymentTermsMapper vendorPaymentTermsMapper) {
        this.vendorRepository = vendorRepository;
        this.vendorContactRepository = vendorContactRepository;
        this.vendorBankAccountRepository = vendorBankAccountRepository;
        this.vendorTaxIdentifierRepository = vendorTaxIdentifierRepository;
        this.vendorPaymentTermsRepository = vendorPaymentTermsRepository;
        this.vendorContactMapper = vendorContactMapper;
        this.vendorTaxIdentifierMapper = vendorTaxIdentifierMapper;
        this.vendorBankAccountMapper = vendorBankAccountMapper;
        this.vendorPaymentTermsMapper = vendorPaymentTermsMapper;
    }

    // ==================== Contact CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorContactDto> getContactsByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorContactRepository.findByVendor_Id(vendorId).stream()
                .map(vendorContactMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorContactDto addContact(Long vendorId, VendorContactCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorContact contact = mapToContactEntity(dto);
        contact.setOrganization(vendor.getOrganization());
        vendor.addContact(contact);
        vendorRepository.save(vendor);
        return vendorContactMapper.toDto(contact);
    }

    @Transactional
    public VendorContactDto updateContact(Long vendorId, Long contactId, VendorContactCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorContact contact = vendorContactRepository.findByIdAndOrganization_Id(contactId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Contact with ID " + contactId + " was not found in this organization"));
        if (!contact.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Contact with ID " + contactId + " does not belong to vendor with ID " + vendorId);
        }
        contact.setContactPerson(dto.getContactPerson());
        contact.setEmail(dto.getEmail());
        contact.setPhone(dto.getPhone());
        contact.setAlternatePhone(dto.getAlternatePhone());
        contact.setPrimary(dto.isPrimary());
        contact = vendorContactRepository.save(contact);
        return vendorContactMapper.toDto(contact);
    }

    @Transactional
    public void deleteContact(Long vendorId, Long contactId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorContact contact = vendorContactRepository.findByIdAndOrganization_Id(contactId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Contact with ID " + contactId + " was not found in this organization"));
        if (!contact.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Contact with ID " + contactId + " does not belong to vendor with ID " + vendorId);
        }
        vendor.getContacts().remove(contact);
        vendorContactRepository.delete(contact);
    }

    // ==================== Tax Identifier CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorTaxIdentifierDto> getTaxIdentifiersByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorTaxIdentifierRepository.findByVendor_Id(vendorId).stream()
                .map(vendorTaxIdentifierMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorTaxIdentifierDto addTaxIdentifier(Long vendorId, VendorTaxIdentifierCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = mapToTaxIdentifierEntity(dto);
        taxId.setOrganization(vendor.getOrganization());
        vendor.addTaxIdentifier(taxId);
        vendorRepository.save(vendor);
        return vendorTaxIdentifierMapper.toDto(taxId);
    }

    @Transactional
    public VendorTaxIdentifierDto updateTaxIdentifier(Long vendorId, Long taxIdId, VendorTaxIdentifierCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = vendorTaxIdentifierRepository.findById(taxIdId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax identifier with ID " + taxIdId + " was not found"));
        if (!taxId.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Tax identifier with ID " + taxIdId + " does not belong to vendor with ID " + vendorId);
        }
        taxId.setType(TaxIdentifierType.valueOf(dto.getType()));
        taxId.setValue(dto.getValue());
        taxId = vendorTaxIdentifierRepository.save(taxId);
        return vendorTaxIdentifierMapper.toDto(taxId);
    }

    @Transactional
    public void deleteTaxIdentifier(Long vendorId, Long taxIdId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorTaxIdentifier taxId = vendorTaxIdentifierRepository.findById(taxIdId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax identifier with ID " + taxIdId + " was not found"));
        if (!taxId.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Tax identifier with ID " + taxIdId + " does not belong to vendor with ID " + vendorId);
        }
        vendor.getTaxIdentifiers().remove(taxId);
        vendorTaxIdentifierRepository.delete(taxId);
    }

    // ==================== Bank Account CRUD ====================

    @Transactional(readOnly = true)
    public List<VendorBankAccountDto> getBankAccountsByVendorId(Long vendorId) {
        findVendorByIdAndOrg(vendorId);
        return vendorBankAccountRepository.findByVendor_Id(vendorId).stream()
                .map(vendorBankAccountMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public VendorBankAccountDto addBankAccount(Long vendorId, VendorBankAccountCreationDto dto) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = mapToBankAccountEntity(dto);
        account.setOrganization(vendor.getOrganization());
        vendor.addBankAccount(account);
        vendorRepository.save(vendor);
        return vendorBankAccountMapper.toDto(account);
    }

    @Transactional
    public VendorBankAccountDto updateBankAccount(Long vendorId, Long accountId, VendorBankAccountCreationDto dto) {
        findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = vendorBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account with ID " + accountId + " was not found"));
        if (!account.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Bank account with ID " + accountId + " does not belong to vendor with ID " + vendorId);
        }
        account.setBankName(dto.getBankName());
        account.setAccountNumber(dto.getAccountNumber());
        account.setIfscCode(dto.getIfscCode());
        account.setAccountHolderName(dto.getAccountHolderName());
        account.setSwift(dto.getSwift());
        account.setDefault(dto.isDefault());
        account = vendorBankAccountRepository.save(account);
        return vendorBankAccountMapper.toDto(account);
    }

    @Transactional
    public void deleteBankAccount(Long vendorId, Long accountId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorBankAccount account = vendorBankAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account with ID " + accountId + " was not found"));
        if (!account.getVendor().getId().equals(vendorId)) {
            throw new ResourceNotFoundException("Bank account with ID " + accountId + " does not belong to vendor with ID " + vendorId);
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
        return vendorPaymentTermsMapper.toDto(terms);
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
        return vendorPaymentTermsMapper.toDto(terms);
    }

    @Transactional
    public void deletePaymentTerms(Long vendorId) {
        Vendor vendor = findVendorByIdAndOrg(vendorId);
        VendorPaymentTerms terms = vendorPaymentTermsRepository.findByVendor_Id(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment terms are configured for vendor with ID " + vendorId));
        vendor.setPaymentTerms(null);
        vendorPaymentTermsRepository.delete(terms);
    }

    // ==================== Helper Methods ====================

    private Vendor findVendorByIdAndOrg(Long id) {
        return vendorRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor with ID " + id + " was not found in this organization"));
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
