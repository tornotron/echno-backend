package org.tornotron.echno_backend.finance.posting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.domain.PostingAccountMapping;
import org.tornotron.echno_backend.finance.posting.dtos.PostingAccountMappingDto;
import org.tornotron.echno_backend.finance.posting.repositories.PostingAccountMappingRepository;

import java.util.List;
import java.util.UUID;

/**
 * Manages the per-organization mapping of posting roles to accounts.
 *
 * <p>Reading returns every role with the account it currently resolves to, flagged as coming from
 * an explicit mapping or the configured default. Writing upserts a role's mapping after checking
 * the target account is a postable leaf in the tenant; deleting reverts a role to its default.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostingAccountMappingService {

    private final PostingAccountMappingRepository mappingRepo;
    private final AccountRepository accountRepo;
    private final PostingAccountResolver resolver;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Returns every posting role with its effective account (mapped where set, otherwise the
     * configured default), including the source flag.
     */
    @Transactional(readOnly = true)
    public List<PostingAccountMappingDto> listEffective() {
        return java.util.Arrays.stream(PostingRole.values())
                .map(role -> {
                    PostingAccountResolver.Resolved resolved = resolver.resolveWithSource(role);
                    Account account = resolved.account();
                    return new PostingAccountMappingDto(role, resolved.source(),
                            account.getId(), account.getCode(), account.getName());
                })
                .toList();
    }

    /**
     * Points a posting role at an account for the current tenant, creating the mapping row or
     * updating it in place. The account must be a postable (active, leaf) account in the tenant.
     *
     * @param role The posting role to map.
     * @param accountId The target account id.
     * @return The role's effective mapping after the upsert.
     * @throws AccountNotFoundException if the account does not exist in the current tenant.
     * @throws InvalidRequestException if the account is inactive or is a header (non-leaf) account.
     */
    @Transactional
    public PostingAccountMappingDto upsert(PostingRole role, UUID accountId) {
        Long orgId = TenantContext.getCurrentOrgId();

        Account account = accountRepo.findScopedById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive()) {
            throw new InvalidRequestException(
                    "Account '" + account.getCode() + "' is inactive and cannot be used for posting");
        }
        boolean isHeader = !accountRepo.findHeaderIdsAmong(List.of(accountId)).isEmpty();
        if (isHeader) {
            throw new InvalidRequestException(
                    "Account '" + account.getCode() + "' (" + account.getName()
                            + ") is a header account and cannot be used for posting; choose a leaf account");
        }

        PostingAccountMapping mapping = mappingRepo.findByRoleAndOrganization_Id(role, orgId)
                .orElseGet(() -> {
                    PostingAccountMapping created = new PostingAccountMapping();
                    created.setRole(role);
                    created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                    return created;
                });
        mapping.setAccount(account);
        mappingRepo.save(mapping);
        log.info("Mapped posting role {} to account {} for organization {}", role, account.getCode(), orgId);

        PostingAccountResolver.Resolved resolved = resolver.resolveWithSource(role);
        return new PostingAccountMappingDto(role, resolved.source(),
                resolved.account().getId(), resolved.account().getCode(), resolved.account().getName());
    }

    /**
     * Removes the mapping override for a role, reverting it to the configured default. A no-op when
     * the role has no mapping, since the default already applies.
     *
     * @param role The posting role to revert.
     * @return The role's effective mapping after the delete (now the default).
     */
    @Transactional
    public PostingAccountMappingDto delete(PostingRole role) {
        Long orgId = TenantContext.getCurrentOrgId();
        mappingRepo.findByRoleAndOrganization_Id(role, orgId).ifPresent(mappingRepo::delete);
        log.info("Removed posting-role mapping {} for organization {}", role, orgId);

        PostingAccountResolver.Resolved resolved = resolver.resolveWithSource(role);
        return new PostingAccountMappingDto(role, resolved.source(),
                resolved.account().getId(), resolved.account().getCode(), resolved.account().getName());
    }
}
