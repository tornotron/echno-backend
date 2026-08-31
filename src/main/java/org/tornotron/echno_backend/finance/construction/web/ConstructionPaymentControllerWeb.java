package org.tornotron.echno_backend.finance.construction.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.dtos.CancelConstructionPaymentRequest;
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
                + "details and moves through a pending and verified lifecycle. Verification is its own "
                + "action, stamped from the session, and is not part of the update payload. This increment "
                + "does not post ledger journal entries. All endpoints are tenant scoped and limited to "
                + "system administrators and project managers."
)
public class ConstructionPaymentControllerWeb {

    /**
     * Rows this listing answers with when the caller names no page size.
     *
     * <p>Twenty is what the endpoint has been returning, though nothing here said so: it took a
     * Spring {@code Pageable}, whose default comes from
     * {@code spring.data.web.pageable.default-page-size}, and that property is not set. The point
     * of issue #638 is that the number was invisible and unreachable, not that it was twenty, so
     * it is written down here rather than changed. Folding the endpoint onto
     * {@link PageQuery#DEFAULT_PAGE_SIZE} would halve what every caller who omits the parameter
     * receives today, which is a contract change with nothing to do with the fix.
     */
    private static final int SHIPPED_PAGE_SIZE = 20;

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
                    + "project, vendor, status, type, payee type, employee, verifier and raiser parameters "
                    + "narrow the result; omitting a parameter leaves that dimension unfiltered.\n\n"
                    + "Narrow here rather than in the client. A filter applied to the rows one page came "
                    + "back in narrows that page and not the register, so it answers a different question "
                    + "from the one it appears to, and an empty result then reads as a complete answer.\n\n"
                    + "The three person parameters carry ids from two different sequences and are not "
                    + "interchangeable. employeeId is the payee on a salary or advance voucher and is an "
                    + "employee id; verifiedBy and raisedBy are platform user ids, the same ids the "
                    + "response returns beside verifiedByName and raisedByName."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching vouchers"),
            @ApiResponse(responseCode = "400", description = "pageNo or pageSize is out of range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<ConstructionPaymentDto> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) ConstructionPaymentVoucherStatus status,
                                             @RequestParam(required = false) ConstructionPaymentType type,
                                             @RequestParam(required = false) ConstructionPayeeType payeeType,
                                             @RequestParam(required = false) Long employeeId,
                                             @RequestParam(required = false) Long verifiedBy,
                                             @RequestParam(required = false) Long raisedBy,
                                             PageQuery pageQuery) {
        return service.findAll(projectId, vendorId, status, type, payeeType,
                employeeId, verifiedBy, raisedBy,
                pageQuery.getPageNo(), pageQuery.pageSizeOr(SHIPPED_PAGE_SIZE));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update a construction payment voucher",
            description = "Replaces the editable fields of a payment voucher, including its status, which "
                    + "is set directly. No ledger journal entry is created on any status transition. A "
                    + "verified voucher is frozen and cannot be edited, because the verification would "
                    + "then stand against figures nobody checked; cancel it and raise a replacement. The "
                    + "cancelled status cannot be set here either: use the cancel action, which records why."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed, the voucher is verified or cancelled, or the payload asks for the cancelled status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No voucher with the given id in the current tenant")
    })
    public ConstructionPaymentDto update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateConstructionPaymentRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Verify a construction payment voucher",
            description = "Records that the voucher has been checked. The verifier is the signed-in user "
                    + "and the time is the server clock; neither can be supplied, so a voucher cannot be "
                    + "recorded as verified by somebody who did not verify it. Whoever raised the voucher "
                    + "cannot verify it, and a verification already recorded is not replaced."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher verified, and returned naming the verifier"),
            @ApiResponse(responseCode = "400", description = "The voucher is cancelled, already verified, "
                    + "or is being verified by whoever raised it"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No voucher with the given id in the current tenant")
    })
    public ConstructionPaymentDto verify(@PathVariable UUID id) {
        return service.verify(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Cancel a construction payment voucher",
            description = "Voids a voucher and records why. This is the only route to the cancelled "
                    + "status, and it is how a verified voucher is corrected, since a verified voucher "
                    + "can no longer be edited: cancel it and raise a replacement. The verification stamp "
                    + "stays on the cancelled voucher, so the record still shows who checked it. "
                    + "Cancelling is one-way, and a cancelled voucher's reason is not replaced."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher cancelled, and returned carrying the reason"),
            @ApiResponse(responseCode = "400", description = "No reason given, or the voucher is already cancelled"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No voucher with the given id in the current tenant")
    })
    public ConstructionPaymentDto cancel(@PathVariable UUID id,
                                          @Valid @RequestBody CancelConstructionPaymentRequest req) {
        return service.cancel(id, req.reason());
    }
}
