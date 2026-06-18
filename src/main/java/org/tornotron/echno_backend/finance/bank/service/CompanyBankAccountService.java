package org.tornotron.echno_backend.finance.bank.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.bank.dtos.CompanyBankAccountDto;
import org.tornotron.echno_backend.finance.bank.dtos.CreateCompanyBankAccountRequest;
import org.tornotron.echno_backend.finance.bank.mapper.CompanyBankAccountMapper;
import org.tornotron.echno_backend.finance.bank.repositories.CompanyBankAccountRepository;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyBankAccountService {

    private final CompanyBankAccountRepository repository;
    private final AccountRepository accountRepository;
    private final CompanyBankAccountMapper mapper;

    @Transactional(readOnly = true)
    public List<CompanyBankAccountDto> findAll() {
        return mapper.toDtos(repository.findAll());
    }

    @Transactional(readOnly = true)
    public List<CompanyBankAccountDto> findAllActive() {
        return mapper.toDtos(repository.findByActiveTrue());
    }

    @Transactional(readOnly = true)
    public CompanyBankAccountDto findById(UUID id) {
        return repository.findById(id)
                .map(mapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Company Bank Account not found: " + id));
    }

    @Transactional
    public CompanyBankAccountDto create(CreateCompanyBankAccountRequest req) {
        Account ledgerAccount = accountRepository.findById(req.ledgerAccountId())
                .orElseThrow(() -> new AccountNotFoundException(req.ledgerAccountId()));

        CompanyBankAccount account = new CompanyBankAccount();
        account.setBankName(req.bankName());
        account.setAccountNumber(req.accountNumber());
        account.setAccountHolderName(req.accountHolderName());
        account.setIfscCode(req.ifscCode());
        account.setSwiftCode(req.swiftCode());
        account.setDefault(req.isDefault());
        account.setLedgerAccount(ledgerAccount);

        return mapper.toDto(repository.save(account));
    }

    @Transactional
    public CompanyBankAccountDto deactivate(UUID id) {
        CompanyBankAccount account = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company Bank Account not found: " + id));
        account.setActive(false);
        return mapper.toDto(account);
    }
}
