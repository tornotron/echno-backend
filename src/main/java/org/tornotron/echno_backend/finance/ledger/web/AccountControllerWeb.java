package org.tornotron.echno_backend.finance.ledger.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountTreeDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateAccountRequest;
import org.tornotron.echno_backend.finance.ledger.service.AccountService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/accounts/web")
@RequiredArgsConstructor
public class AccountControllerWeb {

    private final AccountService service;

    @GetMapping
    public List<AccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActiveAccounts() : service.findAllAccounts();
    }

    @GetMapping("/tree")
    public List<AccountTreeDto> tree() {
        return service.findAccountTree();
    }

    @GetMapping("/{id}")
    public AccountDto get(@PathVariable UUID id) {
        return service.findAccountById(id);
    }

    @GetMapping("/by-code/{code}")
    public AccountDto getByCode(@PathVariable String code) {
        return service.findByAccountByCode(code);
    }

    @PostMapping
    public ResponseEntity<AccountDto> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    public AccountDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

}
