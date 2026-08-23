package org.tornotron.echno_backend.payable;

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
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.dto.PaymentRecordDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payables")
@Validated
@Tag(
        name = "Payables",
        description = "Amounts owed to vendors, tracked from creation through to settlement. A payable "
                + "carries its vendor, amount and outstanding balance, and accepts payment records that "
                + "reduce that balance. Endpoints cover creating payables, recording payments, browsing "
                + "and filtering by vendor, and listing outstanding items. Access is tenant scoped and "
                + "gated by the payable authorities, with an admin authority that grants all operations."
)
public class PayableController {

    private final PayableService payableService;

    public PayableController(PayableService payableService) {
        this.payableService = payableService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payable:create') or hasAuthority('payable:admin')")
    @Operation(
            summary = "Create a payable",
            description = "Creates a payable owed to a vendor with its amount and details."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Payable created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable create or admin authority")
    })
    public ResponseEntity<PayableDto> createPayable(@Valid @RequestBody PayableCreationDto creationDto) {
        PayableDto created = payableService.createPayable(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('payable:update') or hasAuthority('payable:admin')")
    @Operation(
            summary = "Record a payment",
            description = "Records a payment against the given payable, reducing its outstanding balance."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No payable with the given id")
    })
    public ResponseEntity<PayableDto> recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody PaymentRecordDto paymentDto
    ) {
        PayableDto updated = payableService.recordPayment(id, paymentDto.getPaymentAmount());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    @Operation(
            summary = "Get a payable by id",
            description = "Returns a single payable."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payable found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No payable with the given id")
    })
    public ResponseEntity<PayableDto> getPayableById(@PathVariable Long id) {
        PayableDto payable = payableService.getPayableById(id);
        return ResponseEntity.ok(payable);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    @Operation(
            summary = "List payables",
            description = "Returns every payable in the current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable read or admin authority")
    })
    public ResponseEntity<List<PayableDto>> getAllPayables() {
        List<PayableDto> payables = payableService.getAllPayables();
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    @Operation(
            summary = "List payables (paged)",
            description = "Returns a single page of payables controlled by the pageNo and pageSize parameters."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable read or admin authority")
    })
    public ResponseEntity<Page<PayableDto>> getAllPayablesPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<PayableDto> payables = payableService.getAllPayables(pageNo, pageSize);
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    @Operation(
            summary = "List payables for a vendor",
            description = "Returns the payables owed to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable read or admin authority")
    })
    public ResponseEntity<List<PayableDto>> getPayablesByVendor(@PathVariable Long vendorId) {
        List<PayableDto> payables = payableService.getPayablesByVendor(vendorId);
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/outstanding")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    @Operation(
            summary = "List outstanding payables",
            description = "Returns the payables that still have an outstanding balance."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Outstanding payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the payable read or admin authority")
    })
    public ResponseEntity<List<PayableDto>> getOutstandingPayables() {
        List<PayableDto> payables = payableService.getOutstandingPayables();
        return ResponseEntity.ok(payables);
    }
}
