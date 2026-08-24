package org.tornotron.echno_backend.finance.budget.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;

/**
 * Seeds a default set of budget heads (cost categories) for the current tenant, each mapped to the
 * matching seeded expense account by code.
 *
 * <p>The heads mirror the construction expense accounts: Materials (5100), Subcontractor (5200),
 * Labour (5300), Plant and Equipment (5400), and Other (5900). The expense account is resolved by
 * code in the tenant; when an org has retuned its chart and a code is absent the head is still seeded,
 * only without the account mapping.
 *
 * <p>Idempotent: if the tenant already owns any cost category the seed is skipped, so it is safe to
 * run on organization creation and to re-run to back-fill an existing org.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostCategorySeeder {

    private final CostCategoryRepository categoryRepo;
    private final AccountRepository accountRepo;
    private final TenantEntityHelper tenantEntityHelper;

    /** One default head: its name, optional code, and the expense account code it maps to. */
    private record SeedCategory(String name, String code, String expenseAccountCode) {}

    private static final List<SeedCategory> DEFAULT_CATEGORIES = List.of(
            new SeedCategory("Materials", "MAT", "5100"),
            new SeedCategory("Subcontractor", "SUB", "5200"),
            new SeedCategory("Labour", "LAB", "5300"),
            new SeedCategory("Plant and Equipment", "PLT", "5400"),
            new SeedCategory("Other", "OTH", "5900")
    );

    /**
     * Seeds the default budget heads for the current tenant if it has none yet.
     *
     * @return the number of categories created (0 when the org already had any).
     */
    @Transactional
    public int seedDefaults() {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();

        if (categoryRepo.existsByOrganizationId(org.getId())) {
            log.debug("Cost categories already present for organization {}, skipping seed", org.getId());
            return 0;
        }

        Long orgId = TenantContext.getCurrentOrgId();
        int created = 0;
        for (SeedCategory seed : DEFAULT_CATEGORIES) {
            Account account = accountRepo.findByCodeAndOrganization_Id(seed.expenseAccountCode(), orgId)
                    .orElse(null);
            if (account == null) {
                log.debug("Expense account {} not found for organization {}, seeding head '{}' unmapped",
                        seed.expenseAccountCode(), org.getId(), seed.name());
            }

            CostCategory category = new CostCategory();
            category.setName(seed.name());
            category.setCode(seed.code());
            category.setExpenseAccount(account);
            category.setActive(true);
            category.setOrganization(org);
            categoryRepo.save(category);
            created++;
        }

        log.info("Seeded {} default cost categories for organization {}", created, org.getId());
        return created;
    }
}
