package org.tornotron.echno_backend.finance.ledger.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountTreeDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateAccountRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.AccountMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AccountService. Repositories, mapper, and the code generator are mocked;
 * the account graph is built in memory. Focus is on the branch logic the service owns:
 * deriving a child's type from its parent (and rejecting a conflicting supplied type),
 * choosing between a supplied and an auto-generated code, duplicate-code rejection, and
 * the in-memory assembly of the account tree with the leaf-only postable flag. The mapper
 * is a mock, so create() assertions read the Account captured on save().
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository repo;
    @Mock private AccountMapper mapper;
    @Mock private AccountCodeGenerator codeGenerator;
    @Mock private TenantEntityHelper tenantEntityHelper;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(repo, mapper, codeGenerator, tenantEntityHelper);
        lenient().when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        lenient().when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Account account(UUID id, String code, AccountType type, Account parent) {
        Account a = new Account();
        a.setId(id);
        a.setCode(code);
        a.setType(type);
        a.setParent(parent);
        a.setActive(true);
        return a;
    }

    private ArgumentCaptor<Account> captureSaved() {
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(captor.capture());
        return captor;
    }

    @Test
    void create_root_usesSuppliedTypeAndCode() {
        CreateAccountRequest req = new CreateAccountRequest("1000", "Assets", "ASSET", null, "root");
        when(repo.existsByCode("1000")).thenReturn(false);

        service.create(req);

        Account saved = captureSaved().getValue();
        assertThat(saved.getType()).isEqualTo(AccountType.ASSET);
        assertThat(saved.getCode()).isEqualTo("1000");
        assertThat(saved.getParent()).isNull();
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void create_child_inheritsTypeFromParent() {
        Account parent = account(UUID.randomUUID(), "1000", AccountType.ASSET, null);
        when(repo.findScopedById(parent.getId())).thenReturn(Optional.of(parent));
        // No type on the request: it must come from the parent.
        CreateAccountRequest req = new CreateAccountRequest("1100", "Current Assets", null, parent.getId(), null);
        when(repo.existsByCode("1100")).thenReturn(false);

        service.create(req);

        assertThat(captureSaved().getValue().getType()).isEqualTo(AccountType.ASSET);
    }

    @Test
    void create_child_typeConflictingWithParent_throws() {
        Account parent = account(UUID.randomUUID(), "1000", AccountType.ASSET, null);
        when(repo.findScopedById(parent.getId())).thenReturn(Optional.of(parent));
        CreateAccountRequest req = new CreateAccountRequest("1100", "Wrong", "EXPENSE", parent.getId(), null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(InvalidJournalException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void create_blankCode_autoGeneratesFromSiblings() {
        Account parent = account(UUID.randomUUID(), "1000", AccountType.ASSET, null);
        when(repo.findScopedById(parent.getId())).thenReturn(Optional.of(parent));
        when(repo.findByParent(parent)).thenReturn(List.of());
        when(codeGenerator.nextCode(parent, AccountType.ASSET, List.of())).thenReturn("1100");
        when(repo.existsByCode("1100")).thenReturn(false);
        CreateAccountRequest req = new CreateAccountRequest("  ", "Current Assets", null, parent.getId(), null);

        service.create(req);

        assertThat(captureSaved().getValue().getCode()).isEqualTo("1100");
        verify(codeGenerator).nextCode(parent, AccountType.ASSET, List.of());
    }

    @Test
    void create_duplicateCode_throws() {
        CreateAccountRequest req = new CreateAccountRequest("1000", "Assets", "ASSET", null, null);
        when(repo.existsByCode("1000")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(InvalidJournalException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void findAccountTree_nestsChildrenAndFlagsLeavesPostable() {
        Account root = account(UUID.randomUUID(), "1000", AccountType.ASSET, null);
        Account child = account(UUID.randomUUID(), "1100", AccountType.ASSET, root);
        Account grandchild = account(UUID.randomUUID(), "1110", AccountType.ASSET, child);
        when(repo.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(root, child, grandchild));

        List<AccountTreeDto> tree = service.findAccountTree();

        assertThat(tree).hasSize(1);
        AccountTreeDto rootNode = tree.get(0);
        assertThat(rootNode.code()).isEqualTo("1000");
        assertThat(rootNode.postable()).isFalse();      // has a child
        assertThat(rootNode.children()).hasSize(1);

        AccountTreeDto childNode = rootNode.children().get(0);
        assertThat(childNode.code()).isEqualTo("1100");
        assertThat(childNode.postable()).isFalse();      // has a grandchild
        assertThat(childNode.children()).hasSize(1);

        AccountTreeDto leaf = childNode.children().get(0);
        assertThat(leaf.code()).isEqualTo("1110");
        assertThat(leaf.postable()).isTrue();            // leaf: accepts journal lines
        assertThat(leaf.children()).isEmpty();
    }

    @Test
    void deactivate_setsInactive() {
        Account a = account(UUID.randomUUID(), "1000", AccountType.ASSET, null);
        when(repo.findScopedById(a.getId())).thenReturn(Optional.of(a));

        service.deactivate(a.getId());

        assertThat(a.isActive()).isFalse();
    }
}
