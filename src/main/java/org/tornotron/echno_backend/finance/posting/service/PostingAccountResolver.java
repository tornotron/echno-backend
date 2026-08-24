package org.tornotron.echno_backend.finance.posting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.finance.construction.ConstructionPostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.domain.PostingAccountMapping;
import org.tornotron.echno_backend.finance.posting.repositories.PostingAccountMappingRepository;

import java.util.Optional;

/**
 * Resolves a {@link PostingRole} to the concrete {@link Account} the finance postings should use
 * for the current tenant.
 *
 * <p>An organization may map a role to any postable leaf account through a
 * {@link PostingAccountMapping} row; where it has, that account is used. Where it has not, the
 * resolver falls back to the code configured on {@link InvoicePostingProperties} (receivable side)
 * and {@link ConstructionPostingProperties} (payable and default sides), resolved against the
 * current tenant's chart of accounts. The fallback reproduces exactly what the services did before
 * the mapping existed, so an organization that sets no mapping posts to the same accounts as before.
 */
@Service
@RequiredArgsConstructor
public class PostingAccountResolver {

    private final PostingAccountMappingRepository mappingRepo;
    private final AccountRepository accountRepo;
    private final InvoicePostingProperties invoiceProps;
    private final ConstructionPostingProperties constructionProps;

    /**
     * The source of the account a role resolves to: an explicit per-org mapping, or the
     * configured default code.
     */
    public enum Source {
        MAPPED,
        DEFAULT
    }

    /** A resolved role: the effective account and where it came from. */
    public record Resolved(PostingRole role, Account account, Source source) {}

    /**
     * Resolves a role to the account the postings should use for the current tenant.
     *
     * @param role The posting role to resolve.
     * @return The effective account.
     * @throws AccountNotFoundException if no mapping is set and the fallback code does not resolve
     *         to an account in the current tenant.
     */
    @Transactional(readOnly = true)
    public Account resolve(PostingRole role) {
        return resolveWithSource(role).account();
    }

    /**
     * Resolves a role to its effective account and reports whether it came from a mapping or the
     * configured default.
     *
     * @param role The posting role to resolve.
     * @return The resolved account and its source.
     * @throws AccountNotFoundException if no mapping is set and the fallback code does not resolve
     *         to an account in the current tenant.
     */
    @Transactional(readOnly = true)
    public Resolved resolveWithSource(PostingRole role) {
        Long orgId = TenantContext.getCurrentOrgId();

        Optional<PostingAccountMapping> mapping = mappingRepo.findByRoleAndOrganization_Id(role, orgId);
        if (mapping.isPresent()) {
            return new Resolved(role, mapping.get().getAccount(), Source.MAPPED);
        }

        String code = defaultCodeFor(role);
        Account account = accountRepo.findByCodeAndOrganization_Id(code, orgId)
                .orElseThrow(() -> new AccountNotFoundException(code));
        return new Resolved(role, account, Source.DEFAULT);
    }

    /**
     * The configured fallback account code for a role, used when the tenant has set no mapping.
     */
    public String defaultCodeFor(PostingRole role) {
        return switch (role) {
            case ACCOUNTS_RECEIVABLE -> invoiceProps.getArAccountCode();
            case GST_OUTPUT -> invoiceProps.getGstOutputCode();
            case ACCOUNTS_PAYABLE -> constructionProps.getApAccountCode();
            case GST_INPUT -> constructionProps.getGstInputCode();
            case DEFAULT_REVENUE -> constructionProps.getDefaultRevenueCode();
            case DEFAULT_EXPENSE -> constructionProps.getDefaultExpenseCode();
        };
    }
}
