package org.tornotron.echno_backend.vendor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Vendors",
        description = "Suppliers that materials are purchased from, along with their contacts, tax "
                + "identifiers, bank accounts and payment terms. A vendor carries a name and the nested "
                + "records used for procurement and payables. Endpoints cover creating, browsing, "
                + "searching, updating and deleting vendors and their sub-records, and reading a vendor "
                + "summary. Access is tenant scoped and gated by the vendor authorities, with an admin "
                + "authority that grants all operations."
)
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
    @Operation(
            summary = "Create a vendor",
            description = "Creates a vendor record in the current tenant with its identifying details."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Vendor created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor create or admin authority")
    })
    public ResponseEntity<VendorDto> createVendor(@Valid @RequestBody VendorCreationDto creationDto) {
        VendorDto created = vendorService.createVendor(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Get a vendor by id",
            description = "Returns a single vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vendor found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorDto> getVendorById(@PathVariable Long id) {
        VendorDto vendor = vendorService.getVendorById(id);
        return ResponseEntity.ok(vendor);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "List vendors",
            description = "Returns every vendor in the current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vendors returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority")
    })
    public ResponseEntity<List<VendorDto>> getAllVendors() {
        List<VendorDto> vendors = vendorService.getAllVendors();
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "List vendors (paged)",
            description = "Returns a single page of vendors controlled by the pageNo and pageSize parameters."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of vendors returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority")
    })
    public ResponseEntity<Page<VendorDto>> getAllVendorsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<VendorDto> vendors = vendorService.getAllVendors(pageNo, pageSize);
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Search vendors by name",
            description = "Returns vendors whose name matches the given search term."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching vendors returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority")
    })
    public ResponseEntity<List<VendorDto>> searchVendors(@RequestParam String name) {
        List<VendorDto> vendors = vendorService.searchVendorsByName(name);
        return ResponseEntity.ok(vendors);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Update a vendor",
            description = "Replaces the details of the vendor with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vendor updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorDto> updateVendor(
            @PathVariable Long id,
            @Valid @RequestBody VendorCreationDto updateDto
    ) {
        VendorDto updated = vendorService.updateVendor(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Delete a vendor",
            description = "Deletes the vendor with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vendor deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<ApiResponse> deleteVendor(@PathVariable Long id) {
        vendorService.deleteVendor(id);
        return ResponseEntity.ok(new ApiResponse("Vendor with id: " + id + " deleted successfully"));
    }

    // ==================== Summary Endpoint ====================

    @GetMapping("/{vendorId}/summary")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Get a vendor summary",
            description = "Returns aggregated summary figures for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Vendor summary returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorSummaryDto> getVendorSummary(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorSummaryService.getVendorSummary(vendorId));
    }

    // ==================== Contact Endpoints ====================

    @GetMapping("/{vendorId}/contacts")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "List vendor contacts",
            description = "Returns the contacts recorded for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Contacts returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<List<VendorContactDto>> getContacts(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getContactsByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/contacts")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Add a vendor contact",
            description = "Adds a contact to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Contact created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorContactDto> addContact(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorContactCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addContact(vendorId, dto), HttpStatus.CREATED);
    }

    @PatchMapping("/{vendorId}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Update a vendor contact",
            description = "Replaces the details of a contact belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Contact updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or contact with the given id")
    })
    public ResponseEntity<VendorContactDto> updateContact(
            @PathVariable Long vendorId,
            @PathVariable Long contactId,
            @Valid @RequestBody VendorContactCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateContact(vendorId, contactId, dto));
    }

    @DeleteMapping("/{vendorId}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Delete a vendor contact",
            description = "Deletes a contact belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Contact deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or contact with the given id")
    })
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
    @Operation(
            summary = "List vendor tax identifiers",
            description = "Returns the tax identifiers recorded for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tax identifiers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<List<VendorTaxIdentifierDto>> getTaxIdentifiers(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getTaxIdentifiersByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/tax-identifiers")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Add a vendor tax identifier",
            description = "Adds a tax identifier to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tax identifier created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorTaxIdentifierDto> addTaxIdentifier(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorTaxIdentifierCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addTaxIdentifier(vendorId, dto), HttpStatus.CREATED);
    }

    @PatchMapping("/{vendorId}/tax-identifiers/{taxIdId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Update a vendor tax identifier",
            description = "Replaces the details of a tax identifier belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tax identifier updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or tax identifier with the given id")
    })
    public ResponseEntity<VendorTaxIdentifierDto> updateTaxIdentifier(
            @PathVariable Long vendorId,
            @PathVariable Long taxIdId,
            @Valid @RequestBody VendorTaxIdentifierCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateTaxIdentifier(vendorId, taxIdId, dto));
    }

    @DeleteMapping("/{vendorId}/tax-identifiers/{taxIdId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Delete a vendor tax identifier",
            description = "Deletes a tax identifier belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tax identifier deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or tax identifier with the given id")
    })
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
    @Operation(
            summary = "List vendor bank accounts",
            description = "Returns the bank accounts recorded for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Bank accounts returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<List<VendorBankAccountDto>> getBankAccounts(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getBankAccountsByVendorId(vendorId));
    }

    @PostMapping("/{vendorId}/bank-accounts")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Add a vendor bank account",
            description = "Adds a bank account to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Bank account created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorBankAccountDto> addBankAccount(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorBankAccountCreationDto dto
    ) {
        return new ResponseEntity<>(vendorService.addBankAccount(vendorId, dto), HttpStatus.CREATED);
    }

    @PatchMapping("/{vendorId}/bank-accounts/{accountId}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Update a vendor bank account",
            description = "Replaces the details of a bank account belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Bank account updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or bank account with the given id")
    })
    public ResponseEntity<VendorBankAccountDto> updateBankAccount(
            @PathVariable Long vendorId,
            @PathVariable Long accountId,
            @Valid @RequestBody VendorBankAccountCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.updateBankAccount(vendorId, accountId, dto));
    }

    @DeleteMapping("/{vendorId}/bank-accounts/{accountId}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Delete a vendor bank account",
            description = "Deletes a bank account belonging to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Bank account deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor or bank account with the given id")
    })
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
    @Operation(
            summary = "Get vendor payment terms",
            description = "Returns the payment terms recorded for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment terms returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorPaymentTermsDto> getPaymentTerms(@PathVariable Long vendorId) {
        return ResponseEntity.ok(vendorService.getPaymentTermsByVendorId(vendorId));
    }

    @PutMapping("/{vendorId}/payment-terms")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Set vendor payment terms",
            description = "Sets or replaces the payment terms for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment terms set"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<VendorPaymentTermsDto> setPaymentTerms(
            @PathVariable Long vendorId,
            @Valid @RequestBody VendorPaymentTermsCreationDto dto
    ) {
        return ResponseEntity.ok(vendorService.setPaymentTerms(vendorId, dto));
    }

    @DeleteMapping("/{vendorId}/payment-terms")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    @Operation(
            summary = "Delete vendor payment terms",
            description = "Deletes the payment terms recorded for the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment terms deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the vendor delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No vendor with the given id")
    })
    public ResponseEntity<ApiResponse> deletePaymentTerms(@PathVariable Long vendorId) {
        vendorService.deletePaymentTerms(vendorId);
        return ResponseEntity.ok(new ApiResponse("Payment terms deleted successfully"));
    }
}
