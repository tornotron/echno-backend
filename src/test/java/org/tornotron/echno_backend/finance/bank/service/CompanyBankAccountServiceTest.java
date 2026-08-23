package org.tornotron.echno_backend.finance.bank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.bank.dtos.CreateCompanyBankAccountRequest;
import org.tornotron.echno_backend.finance.bank.mapper.CompanyBankAccountMapper;
import org.tornotron.echno_backend.finance.bank.repositories.CompanyBankAccountRepository;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CompanyBankAccountService. Repositories, the mapper, and the tenant
 * helper are mocked. The focus is the small amount of logic this service owns: create
 * must resolve and attach the tenant-scoped ledger account (and reject an unknown one
 * before saving), stamp the current organization; deactivate flips the active flag by
 * dirty-checking the loaded entity; and the scoped reads reject a missing id.
 */
@ExtendWith(MockitoExtension.class)
class CompanyBankAccountServiceTest {

    @Mock private CompanyBankAccountRepository repository;
    @Mock private AccountRepository accountRepository;
    @Mock private CompanyBankAccountMapper mapper;
    @Mock private TenantEntityHelper tenantEntityHelper;

    private CompanyBankAccountService service;

    @BeforeEach
    void setUp() {
        service = new CompanyBankAccountService(repository, accountRepository, mapper, tenantEntityHelper);
        lenient().when(repository.save(any(CompanyBankAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateCompanyBankAccountRequest request(UUID ledgerAccountId) {
        return new CreateCompanyBankAccountRequest("HDFC", "000111222", "Echno Pvt Ltd",
                "HDFC0000123", "HDFCINBB", true, ledgerAccountId);
    }

    @Test
    void create_unknownLedgerAccount_throws() {
        UUID ledgerId = UUID.randomUUID();
        when(accountRepository.findScopedById(ledgerId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(AccountNotFoundException.class)
                .isThrownBy(() -> service.create(request(ledgerId)));

        verify(repository, never()).save(any());
    }

    @Test
    void create_resolvesLedgerAndStampsOrganization() {
        UUID ledgerId = UUID.randomUUID();
        Account ledger = new Account();
        ledger.setId(ledgerId);
        when(accountRepository.findScopedById(ledgerId)).thenReturn(Optional.of(ledger));
        Organization org = new Organization();
        org.setId(100L);
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);

        service.create(request(ledgerId));

        ArgumentCaptor<CompanyBankAccount> captor = ArgumentCaptor.forClass(CompanyBankAccount.class);
        verify(repository).save(captor.capture());
        CompanyBankAccount saved = captor.getValue();
        assertThat(saved.getBankName()).isEqualTo("HDFC");
        assertThat(saved.getLedgerAccount()).isSameAs(ledger);
        assertThat(saved.getOrganization()).isSameAs(org);
        assertThat(saved.isDefault()).isTrue();
    }

    @Test
    void deactivate_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findScopedById(id)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.deactivate(id));
    }

    @Test
    void deactivate_flipsActiveFlag() {
        UUID id = UUID.randomUUID();
        CompanyBankAccount account = new CompanyBankAccount();
        account.setActive(true);
        when(repository.findScopedById(id)).thenReturn(Optional.of(account));

        service.deactivate(id);

        assertThat(account.isActive()).isFalse();
        verify(mapper).toDto(account);
    }

    @Test
    void findById_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findScopedById(id)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.findById(id));
    }
}
