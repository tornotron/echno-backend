package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds a default chart of accounts for the current tenant.
 *
 * <p>The tree is fixed: five type roots (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE)
 * with a handful of headers and postable leaf accounts under each. Codes are pinned
 * explicitly rather than auto-generated because other modules reference them by code:
 * the AR invoice posting reads {@code finance.invoice.ar-account-code} (1200) and
 * {@code finance.invoice.gst-output-code} (2210), and the construction posting reads
 * {@code finance.construction.*} (2100 AP, 1410 GST input, 4100 revenue, 5100 expense).
 * Those must exist as postable leaves before any journal entry can post.
 *
 * <p>Idempotent: if the tenant already owns any account the seed is skipped, so it is
 * safe to run on organization creation and to re-run to back-fill an existing org.
 * A child's type always equals its parent's type; whether an account is a header or a
 * postable leaf is derived (a header is any account named as another's parent), so the
 * leaves below are simply the accounts with no children.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartOfAccountsSeeder {

    private final AccountRepository accountRepo;
    private final TenantEntityHelper tenantEntityHelper;

    /** One node of the default chart: its code, name, type, and parent code (null for roots). */
    private record SeedAccount(String code, String name, AccountType type, String parentCode) {}

    // Parent-first order: every parent appears before the children that reference it.
    private static final List<SeedAccount> DEFAULT_CHART = List.of(
            new SeedAccount("1000", "Assets", AccountType.ASSET, null),
            new SeedAccount("1100", "Cash and Bank", AccountType.ASSET, "1000"),
            new SeedAccount("1110", "Cash on Hand", AccountType.ASSET, "1100"),
            new SeedAccount("1120", "Bank", AccountType.ASSET, "1100"),
            new SeedAccount("1200", "Accounts Receivable", AccountType.ASSET, "1000"),
            new SeedAccount("1300", "Inventory", AccountType.ASSET, "1000"),
            new SeedAccount("1400", "Duties and Taxes Receivable", AccountType.ASSET, "1000"),
            new SeedAccount("1410", "GST Input Credit", AccountType.ASSET, "1400"),

            new SeedAccount("2000", "Liabilities", AccountType.LIABILITY, null),
            new SeedAccount("2100", "Accounts Payable", AccountType.LIABILITY, "2000"),
            new SeedAccount("2200", "Duties and Taxes Payable", AccountType.LIABILITY, "2000"),
            new SeedAccount("2210", "GST Output Payable", AccountType.LIABILITY, "2200"),

            new SeedAccount("3000", "Equity", AccountType.EQUITY, null),
            new SeedAccount("3100", "Share Capital", AccountType.EQUITY, "3000"),
            new SeedAccount("3200", "Retained Earnings", AccountType.EQUITY, "3000"),

            new SeedAccount("4000", "Income", AccountType.INCOME, null),
            new SeedAccount("4100", "Construction Revenue", AccountType.INCOME, "4000"),
            new SeedAccount("4200", "Other Income", AccountType.INCOME, "4000"),

            new SeedAccount("5000", "Expenses", AccountType.EXPENSE, null),
            new SeedAccount("5100", "Cost of Materials and Purchases", AccountType.EXPENSE, "5000"),
            new SeedAccount("5200", "Subcontractor Charges", AccountType.EXPENSE, "5000"),
            new SeedAccount("5300", "Labour", AccountType.EXPENSE, "5000"),
            new SeedAccount("5400", "Plant and Equipment Hire", AccountType.EXPENSE, "5000"),
            new SeedAccount("5900", "Other Operating Expenses", AccountType.EXPENSE, "5000")
    );

    /**
     * Seeds the default chart for the current tenant if it has no accounts yet.
     *
     * @return the number of accounts created (0 when the org already had a chart).
     */
    @Transactional
    public int seedDefaults() {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();

        if (accountRepo.existsByOrganizationId(org.getId())) {
            log.debug("Chart of accounts already present for organization {}, skipping seed", org.getId());
            return 0;
        }

        Map<String, Account> byCode = new HashMap<>();
        for (SeedAccount node : DEFAULT_CHART) {
            Account account = new Account();
            account.setCode(node.code());
            account.setName(node.name());
            account.setType(node.type());
            account.setActive(true);
            account.setParent(node.parentCode() == null ? null : byCode.get(node.parentCode()));
            account.setOrganization(org);

            Account saved = accountRepo.save(account);
            byCode.put(node.code(), saved);
        }

        log.info("Seeded default chart of accounts ({} accounts) for organization {}",
                byCode.size(), org.getId());
        return byCode.size();
    }
}
