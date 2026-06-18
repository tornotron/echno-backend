package org.tornotron.echno_backend.finance.payment.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.payment.dtos.RecordPaymentRequest;
import org.tornotron.echno_backend.finance.payment.service.PaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/payments/web")
@RequiredArgsConstructor
public class PaymentControllerWeb {

    private final PaymentService service;

    @PostMapping
    public ResponseEntity<PaymentDto> record(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest req
            ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(req, idempotencyKey));
    }

    @GetMapping("/{id}")
    public PaymentDto get(@PathVariable UUID id) {
        return service.findById(id);
    }
}
