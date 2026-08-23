package org.tornotron.echno_backend.finance.construction.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.service.ConstructionPaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/construction-payments/web")
@RequiredArgsConstructor
@Tag(
        name = "Construction Payments",
        description = "Payment vouchers recording money paid out on construction projects to vendors, "
                + "subcontractors, labour and employees. A voucher captures the amount, method and payee "
                + "details and moves through a pending and verified lifecycle. This increment does not post "
                + "ledger journal entries. All endpoints are tenant scoped and limited to system "
                + "administrators and project managers."
)
public class ConstructionPaymentControllerWeb {

    private final ConstructionPaymentService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a construction payment voucher",
            description = "Creates a payment voucher in pending status. The payment number is generated "
                    + "server side from the CPMT sequence. The status cannot be set on creation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Voucher created and returned in pending status"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ConstructionPaymentDto> create(@Valid @RequestBody CreateConstructionPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a construction payment voucher by id",
            description = "Returns a single payment voucher with its amount, method and payee details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No voucher with the given id in the current tenant")
    })
    public ConstructionPaymentDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List construction payment vouchers",
            description = "Returns a paged list of payment vouchers in the current tenant. The optional "
                    + "project, vendor, status, type and payee type parameters narrow the result; omitting "
                    + "a parameter leaves that dimension unfiltered."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching vouchers"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<ConstructionPaymentDto> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) ConstructionPaymentVoucherStatus status,
                                             @RequestParam(required = false) ConstructionPaymentType type,
                                             @RequestParam(required = false) ConstructionPayeeType payeeType,
                                             Pageable pageable) {
        return service.findAll(projectId, vendorId, status, type, payeeType, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update a construction payment voucher",
            description = "Replaces the editable fields of a payment voucher, including its status, which "
                    + "is set directly. No ledger journal entry is created on any status transition."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the voucher is not in an editable state"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No voucher with the given id in the current tenant")
    })
    public ConstructionPaymentDto update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateConstructionPaymentRequest req) {
        return service.update(id, req);
    }
}
