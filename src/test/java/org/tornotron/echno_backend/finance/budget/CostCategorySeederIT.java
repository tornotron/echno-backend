package org.tornotron.echno_backend.finance.budget;

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
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.budget.service.CostCategorySeeder;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the default cost-category seed against a real CockroachDB: the first run creates the
 * default heads mapped to the seeded expense accounts by code, and a re-run is a no-op because the
 * tenant already owns categories, so the seed is idempotent and safe to back-fill with.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CostCategorySeeder.class, ChartOfAccountsSeeder.class, TenantEntityHelper.class,
        JpaAuditingConfig.class})
class CostCategorySeederIT extends AbstractIntegrationTest {

    @Autowired
    private CostCategorySeeder costSeeder;

    @Autowired
    private ChartOfAccountsSeeder chartSeeder;

    @Autowired
    private CostCategoryRepository categoryRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization org = persistOrganization("Seed Org");
        entityManager.flush();
        TenantContext.setCurrentOrgId(org.getId());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void seedDefaults_createsHeadsMappedToAccounts_andIsIdempotent() {
        chartSeeder.seedDefaults();

        int firstRun = costSeeder.seedDefaults();
        assertThat(firstRun).isEqualTo(5);

        entityManager.flush();
        entityManager.clear();

        assertThat(categoryRepo.findByActiveTrue()).hasSize(5);

        CostCategory materials = categoryRepo.findByActiveTrue().stream()
                .filter(c -> c.getName().equals("Materials"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Materials head was not seeded"));
        // The head is aligned to the Cost of Materials and Purchases expense account (5100).
        assertThat(materials.getExpenseAccount()).isNotNull();
        assertThat(materials.getExpenseAccount().getCode()).isEqualTo("5100");

        // Re-running is a no-op: the tenant already owns categories.
        int secondRun = costSeeder.seedDefaults();
        assertThat(secondRun).isZero();

        entityManager.flush();
        entityManager.clear();
        assertThat(categoryRepo.findByActiveTrue()).hasSize(5);
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
