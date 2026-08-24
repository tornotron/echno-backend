package org.tornotron.echno_backend.finance.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.dtos.CoaImportSummary;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.service.AccountCsvService;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the chart-of-accounts CSV interchange against a real CockroachDB: importing the file that
 * export just produced is idempotent (every account already exists, so nothing is created and the
 * chart is unchanged), and importing a new child listed before its parent still resolves the parent.
 *
 * <p>Runs without the rolled-back ambient transaction so the imports commit; every write is cleaned
 * up in the after-each, which keeps the counts a comparison of this tenant's chart before and after
 * rather than an absolute number.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AccountCsvService.class, ChartOfAccountsSeeder.class, TenantEntityHelper.class,
        JpaAuditingConfig.class})
class AccountCsvServiceIT extends AbstractIntegrationTest {

    @Autowired
    private AccountCsvService csvService;

    @Autowired
    private ChartOfAccountsSeeder seeder;

    @Autowired
    private AccountRepository accountRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Csv Org");
            entityManager.flush();
            orgId = org.getId();
            TenantContext.setCurrentOrgId(orgId);
            seeder.seedDefaults();
        });
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (orgId == null) {
            return;
        }
        inCommittedTx(() -> {
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    @Test
    void exportThenImport_isIdempotent() {
        long before = accountRepo.findAll().size();
        assertThat(before).isGreaterThan(0);

        String exported = csvService.exportCsv();

        CoaImportSummary summary = csvService.importCsv(exported);
        assertThat(summary.created()).isZero();
        assertThat(summary.updated()).isEqualTo((int) before);
        assertThat(summary.errors()).isEmpty();

        // The chart is unchanged and the file exports identically the second time.
        assertThat(accountRepo.findAll()).hasSize((int) before);
        assertThat(csvService.exportCsv()).isEqualTo(exported);
    }

    @Test
    void import_createsNewChildListedBeforeItsParent() {
        long before = accountRepo.findAll().size();

        // The child row precedes its parent row in the file; the import must still apply the parent first.
        String csv = "code,name,type,parentCode,active\n"
                + "5110,Cement Purchases,EXPENSE,5100,true\n"
                + "5120,Steel Purchases,EXPENSE,5100,true\n";

        CoaImportSummary summary = csvService.importCsv(csv);
        assertThat(summary.errors()).isEmpty();
        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.updated()).isZero();

        assertThat(accountRepo.findByCodeAndOrganization_Id("5110", orgId)).isPresent();
        // Read the lazy parent inside a transaction.
        String parentCode = inReadTx(() -> accountRepo.findByCodeAndOrganization_Id("5110", orgId)
                .orElseThrow().getParent().getCode());
        assertThat(parentCode).isEqualTo("5100");
        assertThat(accountRepo.findAll()).hasSize((int) before + 2);
    }

    @Test
    void import_rejectsRowWhoseTypeMismatchesParent() {
        String csv = "code,name,type,parentCode,active\n"
                + "5115,Bad Child,INCOME,5100,true\n";

        CoaImportSummary summary = csvService.importCsv(csv);
        assertThat(summary.created()).isZero();
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("does not match parent");
    }

    // --- Helpers ----------------------------------------------------------

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }

    private void exec(String sql) {
        entityManager.createNativeQuery(sql).setParameter("org", orgId).executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private <T> T inReadTx(java.util.function.Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setReadOnly(true);
        return tt.execute(status -> work.get());
    }
}
