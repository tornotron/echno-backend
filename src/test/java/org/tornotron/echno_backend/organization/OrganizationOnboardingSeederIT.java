package org.tornotron.echno_backend.organization;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.budget.service.CostCategorySeeder;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsRepository;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the per-org finance seeding a self-service onboarding runs for a new organization,
 * against a real CockroachDB: one call seeds the chart of accounts, the default cost categories and
 * the finance-settings row for the org, and a re-run adds nothing because every underlying seed is
 * idempotent. This is the seeding half of onboarding; the system-admin grant runs through Keycloak
 * and is covered separately.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrganizationOnboardingSeeder.class, ChartOfAccountsSeeder.class, CostCategorySeeder.class,
        FinanceSettingsService.class, TenantEntityHelper.class, JpaAuditingConfig.class})
class OrganizationOnboardingSeederIT extends AbstractIntegrationTest {

    @Autowired
    private OrganizationOnboardingSeeder onboardingSeeder;

    @Autowired
    private AccountRepository accountRepo;

    @Autowired
    private CostCategoryRepository categoryRepo;

    @Autowired
    private FinanceSettingsRepository financeSettingsRepo;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgId;

    @BeforeEach
    void newOrg() {
        TenantContext.clear();
        Organization org = persistOrganization("Onboarding Org");
        entityManager.flush();
        orgId = org.getId();
        // Keep the tenant context set to this org for the whole test: the seeder sets and restores
        // it internally, and the fail-closed tenant filter needs it set for the read-back assertions.
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void seedsChartCategoriesAndSettings_andIsIdempotent() {
        onboardingSeeder.seedFinanceDefaults(orgId);

        entityManager.flush();
        entityManager.clear();

        // Chart of accounts seeded for the new org.
        assertThat(accountRepo.existsByOrganizationId(orgId)).isTrue();
        // Cost categories seeded (the five default construction budget heads).
        assertThat(categoryRepo.findByActiveTrue()).hasSize(5);
        // Finance-settings row materialized for the org.
        assertThat(financeSettingsRepo.findByOrganization_Id(orgId)).isPresent();

        long accountsAfterFirst = accountRepo.count();

        // Re-running seeds nothing new: every underlying seed is idempotent.
        onboardingSeeder.seedFinanceDefaults(orgId);
        entityManager.flush();
        entityManager.clear();

        assertThat(categoryRepo.findByActiveTrue()).hasSize(5);
        assertThat(accountRepo.count()).isEqualTo(accountsAfterFirst);
    }

    // --- Helpers ----------------------------------------------------------

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        entityManager.persist(organization);
        return organization;
    }
}
