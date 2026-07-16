package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountTreeDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateAccountRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.AccountMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository repo;
    private final AccountMapper mapper;
    private final AccountCodeGenerator codeGenerator;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public List<AccountDto> findAllAccounts() {
        return  mapper.toDtos(repo.findAll());
    }

    @Transactional(readOnly = true)
    public List<AccountDto> findAllActiveAccounts() {
        return mapper.toDtos(repo.findByActiveTrue());
    }

    /**
     * Returns the full chart of accounts as a nested tree, ordered by code.
     * Includes inactive accounts (flagged via {@code active}). Built from a
     * single fetch and assembled in memory to avoid per-node lazy queries.
     */
    @Transactional(readOnly = true)
    public List<AccountTreeDto> findAccountTree() {
        List<Account> all = repo.findAll(Sort.by("code"));

        // Group children under their parent id; roots have no parent.
        // Reading the LAZY parent's id does not trigger a query (the proxy knows its own id).
        Map<UUID, List<Account>> childrenByParent = all.stream()
                .filter(a -> a.getParent() != null)
                .collect(Collectors.groupingBy(a -> a.getParent().getId()));

        return all.stream()
                .filter(a -> a.getParent() == null)
                .map(root -> toTreeNode(root, childrenByParent))
                .toList();
    }

    private AccountTreeDto toTreeNode(Account account, Map<UUID, List<Account>> childrenByParent) {
        List<AccountTreeDto> children = childrenByParent
                .getOrDefault(account.getId(), List.of())
                .stream()
                .map(child -> toTreeNode(child, childrenByParent))
                .toList();

        return new AccountTreeDto(
                account.getId(),
                account.getCode(),
                account.getName(),
                account.getType(),
                account.isActive(),
                account.getDescription(),
                children.isEmpty(),   // postable: only leaf accounts accept journal lines
                children
        );
    }

    @Transactional(readOnly = true)
    public AccountDto findAccountById(UUID id) {
        return mapper.toDto(repo.findScopedById(id)
                .orElseThrow(() -> new AccountNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public AccountDto findByAccountByCode(String code) {
        return mapper.toDto(repo.findByCode(code)
                .orElseThrow(() -> new AccountNotFoundException(code)));
    }

    @Transactional
    public AccountDto create(CreateAccountRequest req) {
        Account parent = null;
        if(req.parentId() != null) {
            parent = repo.findScopedById(req.parentId())
                    .orElseThrow(() -> new AccountNotFoundException(req.parentId()));
        }

        // A child's type is determined by its branch: derive it from the parent so a
        // mismatched branch is unrepresentable. If the caller also supplied a type,
        // reject loudly when it disagrees rather than silently overriding it.
        AccountType type;
        if(parent != null) {
            type = parent.getType();
            if(req.type() != null && !req.type().isBlank() && AccountType.valueOf(req.type()) != type) {
                throw new InvalidJournalException("Child account type must match parent type");
            }
        } else {
            type = AccountType.valueOf(req.type());
        }

        // Auto-generate the code from parent + siblings unless one was supplied.
        String code = (req.code() == null || req.code().isBlank())
                ? generateCode(parent, type)
                : req.code().trim();

        if(repo.existsByCode(code)) {
            throw new InvalidJournalException("Account code already exists: " + code);
        }

        Account account = new Account();
        account.setCode(code);
        account.setName(req.name());
        account.setType(type);
        account.setDescription(req.description());
        account.setActive(true);
        account.setParent(parent);
        account.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return mapper.toDto(repo.save(account));
    }

    private String generateCode(Account parent, AccountType type) {
        List<Account> siblings = (parent == null)
                ? repo.findByParentIsNullAndType(type)
                : repo.findByParent(parent);
        return codeGenerator.nextCode(parent, type, siblings);
    }

    @Transactional
    public AccountDto deactivate(UUID id) {
        Account account = repo.findScopedById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.setActive(false);
        return mapper.toDto(account);
    }
}
