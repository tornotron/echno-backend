package org.tornotron.echno_backend.finance.payment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.payment.dtos.RecordPaymentRequest;
import org.tornotron.echno_backend.finance.payment.service.PaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/payments/web")
@RequiredArgsConstructor
@Tag(
        name = "Customer Payments",
        description = "Payments received from customers and allocated across their outstanding invoices. "
                + "Recording a payment posts the receipt to the ledger and reduces the balance due on each "
                + "allocated invoice. All endpoints are tenant scoped and limited to system administrators "
                + "and project managers."
)
public class PaymentControllerWeb {

    private final PaymentService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Record a customer payment",
            description = "Records a payment received from a customer and allocates it across the invoices "
                    + "named in the request. The allocations must sum to the payment amount. An optional "
                    + "Idempotency-Key header lets a retried request return the original result instead of "
                    + "creating a duplicate payment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment recorded and allocated"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the allocations do not reconcile with the amount or the invoice balances"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "A referenced customer, bank account or invoice was not found in the current tenant")
    })
    public ResponseEntity<PaymentDto> record(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest req
            ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(req, idempotencyKey));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a payment by id",
            description = "Returns a single customer payment with its invoice allocations."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No payment with the given id in the current tenant")
    })
    public PaymentDto get(@PathVariable UUID id) {
        return service.findById(id);
    }
}
