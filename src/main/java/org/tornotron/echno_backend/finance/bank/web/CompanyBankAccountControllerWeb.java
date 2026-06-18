package org.tornotron.echno_backend.finance.bank.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.bank.dtos.CompanyBankAccountDto;
import org.tornotron.echno_backend.finance.bank.dtos.CreateCompanyBankAccountRequest;
import org.tornotron.echno_backend.finance.bank.service.CompanyBankAccountService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/company-bank-accounts/web")
@RequiredArgsConstructor
public class CompanyBankAccountControllerWeb {

    private final CompanyBankAccountService service;

    @GetMapping
    public List<CompanyBankAccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActive() : service.findAll();
    }

    @GetMapping("/{id}")
    public CompanyBankAccountDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    public ResponseEntity<CompanyBankAccountDto> create(@Valid @RequestBody CreateCompanyBankAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    public CompanyBankAccountDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }
}
