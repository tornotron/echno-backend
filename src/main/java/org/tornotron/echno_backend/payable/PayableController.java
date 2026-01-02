package org.tornotron.echno_backend.payable;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.payable.dto.PayableCreationDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.payable.dto.PaymentRecordDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payables")
@Validated
public class PayableController {

    private final PayableService payableService;

    public PayableController(PayableService payableService) {
        this.payableService = payableService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payable:create') or hasAuthority('payable:admin')")
    public ResponseEntity<PayableDto> createPayable(@Valid @RequestBody PayableCreationDto creationDto) {
        PayableDto created = payableService.createPayable(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('payable:update') or hasAuthority('payable:admin')")
    public ResponseEntity<PayableDto> recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody PaymentRecordDto paymentDto
    ) {
        PayableDto updated = payableService.recordPayment(id, paymentDto.getPaymentAmount());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    public ResponseEntity<PayableDto> getPayableById(@PathVariable Long id) {
        PayableDto payable = payableService.getPayableById(id);
        return ResponseEntity.ok(payable);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    public ResponseEntity<List<PayableDto>> getAllPayables() {
        List<PayableDto> payables = payableService.getAllPayables();
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    public ResponseEntity<Page<PayableDto>> getAllPayablesPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<PayableDto> payables = payableService.getAllPayables(pageNo, pageSize);
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    public ResponseEntity<List<PayableDto>> getPayablesByVendor(@PathVariable Long vendorId) {
        List<PayableDto> payables = payableService.getPayablesByVendor(vendorId);
        return ResponseEntity.ok(payables);
    }

    @GetMapping("/outstanding")
    @PreAuthorize("hasAuthority('payable:read') or hasAuthority('payable:admin')")
    public ResponseEntity<List<PayableDto>> getOutstandingPayables() {
        List<PayableDto> payables = payableService.getOutstandingPayables();
        return ResponseEntity.ok(payables);
    }
}
