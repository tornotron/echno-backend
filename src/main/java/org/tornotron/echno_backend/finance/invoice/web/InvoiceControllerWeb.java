package org.tornotron.echno_backend.finance.invoice.web;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/invoices")
@RequiredArgsConstructor
public class InvoiceControllerWeb {

    private final InvoiceService service;

    @PostMapping
    public ResponseEntity<InvoiceDto> createDraft(@Valid @RequestBody CreateInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(req));
    }

    @GetMapping("/{id}")
    public InvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/issue")
    public InvoiceDto issue(@PathVariable UUID id) {
        return service.issue(id);
    }

    @PostMapping("/{id}/cancel")
    public InvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }
}