package org.tornotron.echno_backend.organization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.finance.budget.service.CostCategorySeeder;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;

/**
 * Seeds the per-organization finance defaults a freshly created organization needs to be usable:
 * the chart of accounts, the budget cost categories, and the finance-settings row.
 *
 * <p>Every step is idempotent (each underlying seeder skips an org that already has the data) and
 * independently guarded: a failure in one seed is logged and the remaining seeds still run, so a
 * seed problem leaves the organization usable and back-fillable rather than half-provisioned. None
 * of these seeds is critical to the organization existing; the critical work (creating the org, its
 * Keycloak group, and making the creator its system-admin) is done in the creating transaction, and
 * this seeding is deliberately run after that transaction commits so a seed failure can never roll
 * the new organization back.
 *
 * <p>Posting-account mappings are intentionally not seeded here: a posting role has no per-org row
 * until an org overrides it, and until then it resolves to the configured default account by code
 * against the chart seeded above. Seeding the chart is therefore all that posting needs to work; a
 * mapping row would only pin a role to an account the org had not chosen to fix.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationOnboardingSeeder {

    private final ChartOfAccountsSeeder chartOfAccountsSeeder;
    private final CostCategorySeeder costCategorySeeder;
    private final FinanceSettingsService financeSettingsService;

    /**
     * Seeds the finance defaults for the given organization. Sets the tenant context to that
     * organization for the duration of the seed and restores the previous context afterwards, so it
     * can be called safely outside a request that is already tenant-scoped.
     *
     * <p>The chart of accounts is seeded first because the cost categories map to its expense
     * accounts by code; the finance-settings row is materialized last.
     *
     * @param organizationId the id of the newly created organization to seed.
     */
    public void seedFinanceDefaults(Long organizationId) {
        Long previous = TenantContext.getCurrentOrgId();
        TenantContext.setCurrentOrgId(organizationId);
        try {
            runQuietly("chart of accounts", organizationId, () -> chartOfAccountsSeeder.seedDefaults());
            runQuietly("cost categories", organizationId, () -> costCategorySeeder.seedDefaults());
            runQuietly("finance settings", organizationId, () -> financeSettingsService.getOrCreate());
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentOrgId(previous);
            }
        }
    }

    /**
     * Runs one seed step, logging and swallowing any failure so the remaining steps still run. Each
     * seeder is transactional in its own right, so a failed step rolls back only its own work.
     */
    private void runQuietly(String what, Long organizationId, Runnable seed) {
        try {
            seed.run();
        } catch (Exception e) {
            log.error("Failed to seed {} for organization {}: {}", what, organizationId, e.getMessage(), e);
        }
    }
}
