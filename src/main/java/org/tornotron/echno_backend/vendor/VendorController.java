package org.tornotron.echno_backend.vendor;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.vendor.dto.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vendors")
@Validated
public class VendorController {

    private final VendorService vendorService;
    private final VendorSummaryService vendorSummaryService;

    public VendorController(VendorService vendorService, VendorSummaryService vendorSummaryService) {
        this.vendorService = vendorService;
        this.vendorSummaryService = vendorSummaryService;
    }

    // ==================== Vendor Endpoints ====================

    @PostMapping
    @PreAuthorize("hasAuthority('vendor:create') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> createVendor(@Valid @RequestBody VendorCreationDto creationDto) {
        VendorDto created = vendorService.createVendor(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> getVendorById(@PathVariable Long id) {
        VendorDto vendor = vendorService.getVendorById(id);
        return ResponseEntity.ok(vendor);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorDto>> getAllVendors() {
        List<VendorDto> vendors = vendorService.getAllVendors();
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<Page<VendorDto>> getAllVendorsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<VendorDto> vendors = vendorService.getAllVendors(pageNo, pageSize);
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorDto>> searchVendors(@RequestParam String name) {
        List<VendorDto> vendors = vendorService.searchVendorsByName(name);
        return ResponseEntity.ok(vendors);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> updateVendor(
            @PathVariable Long id,
            @Valid @RequestBody VendorCreationDto updateDto
    ) {
        VendorDto updated = vendorService.updateVendor(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deleteVendor(@PathVariable Long id) {
        vendorService.deleteVendor(id);
        return ResponseEntity.ok(new ApiResponse("Vendor with id: " + id + " deleted successfully"));
    }

    // ==================== Summary Endpoint ====================

    @GetMapping("/{vendorId}/summary")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorSummaryDto> getVendorSummary(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorSummaryService.getVendorSummary(vendorId));
    }

    // ==================== Contact Endpoints ====================

    @GetMapping("/{vendorId}/contacts")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorContactDto>> getContacts(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getContactsByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/contacts")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorContactDto> addContact(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorContactCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addContact(vendorId, dto), HttpStatus.CREATED);
    }

    @PutMapping("/{vendorId}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorContactDto> updateContact(
            @PathVariable Long vendorId,
            @PathVariable Long contactId,
            @Valid @RequestBody VendorContactCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateContact(vendorId, contactId, dto));
    }

    @DeleteMapping("/{vendorId}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deleteContact(
            @PathVariable Long vendorId,
            @PathVariable Long contactId
    ) {
        vendorService.deleteContact(vendorId, contactId);
        return ResponseEntity.ok(new ApiResponse("Contact deleted successfully"));
    }

    // ==================== Tax Identifier Endpoints ====================

    @GetMapping("/{vendorId}/tax-identifiers")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorTaxIdentifierDto>> getTaxIdentifiers(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getTaxIdentifiersByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/tax-identifiers")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorTaxIdentifierDto> addTaxIdentifier(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorTaxIdentifierCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addTaxIdentifier(vendorId, dto), HttpStatus.CREATED);
    }

    @PutMapping("/{vendorId}/tax-identifiers/{taxIdId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorTaxIdentifierDto> updateTaxIdentifier(
            @PathVariable Long vendorId,
            @PathVariable Long taxIdId,
            @Valid @RequestBody VendorTaxIdentifierCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateTaxIdentifier(vendorId, taxIdId, dto));
    }

    @DeleteMapping("/{vendorId}/tax-identifiers/{taxIdId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deleteTaxIdentifier(
            @PathVariable Long vendorId,
            @PathVariable Long taxIdId
    ) {
        vendorService.deleteTaxIdentifier(vendorId, taxIdId);
        return ResponseEntity.ok(new ApiResponse("Tax identifier deleted successfully"));
    }

    // ==================== Bank Account Endpoints ====================

    @GetMapping("/{vendorId}/bank-accounts")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorBankAccountDto>> getBankAccounts(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getBankAccountsByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/bank-accounts")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorBankAccountDto> addBankAccount(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorBankAccountCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addBankAccount(vendorId, dto), HttpStatus.CREATED);
    }

    @PutMapping("/{vendorId}/bank-accounts/{accountId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorBankAccountDto> updateBankAccount(
            @PathVariable Long vendorId,
            @PathVariable Long accountId,
            @Valid @RequestBody VendorBankAccountCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateBankAccount(vendorId, accountId, dto));
    }

    @DeleteMapping("/{vendorId}/bank-accounts/{accountId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deleteBankAccount(
            @PathVariable Long vendorId,
            @PathVariable Long accountId
    ) {
        vendorService.deleteBankAccount(vendorId, accountId);
        return ResponseEntity.ok(new ApiResponse("Bank account deleted successfully"));
    }

    // ==================== Payment Terms Endpoints ====================

    @GetMapping("/{vendorId}/payment-terms")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorPaymentTermsDto> getPaymentTerms(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getPaymentTermsByVendorId(vendorId));
    }

    @PutMapping("/{vendorId}/payment-terms")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorPaymentTermsDto> setPaymentTerms(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorPaymentTermsCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.setPaymentTerms(vendorId, dto));
    }

    @DeleteMapping("/{vendorId}/payment-terms")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deletePaymentTerms(@PathVariable Long vendorId) {
        vendorService.deletePaymentTerms(vendorId);
        return ResponseEntity.ok(new ApiResponse("Payment terms deleted successfully"));
    }
}
