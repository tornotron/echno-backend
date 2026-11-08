package org.tornotron.echno_backend.payable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.dto.PaymentRecordDto;

import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/payables/web")
@Validated
@Tag(
        name = "Payables (Web)",
        description = "Web-console counterpart of the payables API, guarded by the payables permissions "
                + "instead of the flat payable authorities used by the mobile variant: read for the lists "
                + "and a single payable, create to record one and edit to record a payment against one. "
                + "Covers the same create, payment-recording, browse, filter-by-vendor and outstanding-list "
                + "operations, for use from the web console. A payable outside the caller's scope reads as "
                + "not found."
)
public class PayableControllerWeb {

    private final PayableService payableService;

    public PayableControllerWeb(PayableService payableService) {
        this.payableService = payableService;
    }

    @PostMapping
    @PreAuthorize("@access.can('payables', 'create')")
    @Operation(
            summary = "Create a payable",
            description = "Creates a payable owed to a vendor with its amount and details."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Payable created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not make this change to payables")
    })
    public ResponseEntity<PayableDto> createPayable(@Valid @RequestBody PayableCreationDto creationDto) {
        PayableDto created = payableService.createPayable(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("@access.can('payables', 'edit')")
    @Operation(
            summary = "Record a payment",
            description = "Records a payment against the given payable, reducing its outstanding balance."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not make this change to payables"),
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
    @PreAuthorize("@access.can('payables', 'read')")
    @Operation(
            summary = "Get a payable by id",
            description = "Returns a single payable."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payable found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read payables"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No payable with the given id")
    })
    public ResponseEntity<PayableDto> getPayableById(@PathVariable Long id) {
        PayableDto payable = payableService.getPayableById(id);
        return ResponseEntity.ok(payable);
    }

    @GetMapping
    @PreAuthorize("@access.can('payables', 'read')")
    @Operation(
            summary = "List payables",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read payables")
    })
    public ResponseEntity<List<PayableDto>> getAllPayables() {
        return UnpagedResultCap.respond(payableService.getAllPayables(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("@access.can('payables', 'read')")
    @Operation(
            summary = "List payables (paged)",
            description = "Returns a single page of payables controlled by the pageNo and pageSize parameters."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read payables")
    })
    public ResponseEntity<Page<PayableDto>> getAllPayablesPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<PayableDto> payables = payableService.getAllPayables(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("@access.can('payables', 'read')")
    @Operation(
            summary = "List payables for a vendor",
            description = "Returns the payables owed to the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read payables")
    })
    public ResponseEntity<List<PayableDto>> getPayablesByVendor(@PathVariable Long vendorId) {
        List<PayableDto> payables = payableService.getPayablesByVendor(vendorId);
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/outstanding")
    @PreAuthorize("@access.can('payables', 'read')")
    @Operation(
            summary = "List outstanding payables",
            description = "Returns the payables that still have an outstanding balance."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Outstanding payables returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller may not read payables")
    })
    public ResponseEntity<List<PayableDto>> getOutstandingPayables() {
        List<PayableDto> payables = payableService.getOutstandingPayables();
        return ResponseEntity.ok(payables);
    }
}
