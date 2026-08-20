package org.tornotron.echno_backend.finance.ledger.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the default chart-of-accounts seed against a real CockroachDB: seeding an
 * empty tenant creates the fixed tree, the control-account codes other modules point at
 * land as postable leaves (not headers), and a second run is a no-op.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChartOfAccountsSeeder.class, TenantEntityHelper.class, JpaAuditingConfig.class})
class ChartOfAccountsSeederIT extends AbstractIntegrationTest {

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
            Organization org = persistOrganization("Chart Org");
            entityManager.flush();
            orgId = org.getId();
        });
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void cleanup() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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
    void seedDefaults_createsPostableLeavesForTheReferencedControlAccounts() {
        int created = seeder.seedDefaults();
        assertThat(created).isEqualTo(24);

        List<Account> all = accountRepo.findAll();
        assertThat(all).hasSize(24);

        // Header accounts are those named as a parent by another account; a leaf has none.
        List<UUID> allIds = all.stream().map(Account::getId).toList();
        Set<UUID> headerIds = Set.copyOf(accountRepo.findHeaderIdsAmong(allIds));

        // The codes finance.invoice.* and finance.construction.* post to must be leaves.
        for (String leafCode : List.of("1200", "2210", "2100", "1410", "4100", "5100")) {
            Account leaf = accountRepo.findByCode(leafCode).orElseThrow();
            assertThat(leaf.isActive()).isTrue();
            assertThat(headerIds).doesNotContain(leaf.getId());
        }

        // Sanity: a type root is a header, not postable.
        Account root = accountRepo.findByCode("1000").orElseThrow();
        assertThat(headerIds).contains(root.getId());

        // A child's type matches its parent's branch.
        assertThat(accountRepo.findByCode("1410").orElseThrow().getParent().getCode()).isEqualTo("1400");
    }

    @Test
    void seedDefaults_isIdempotent() {
        assertThat(seeder.seedDefaults()).isEqualTo(24);
        assertThat(seeder.seedDefaults()).isZero();
        assertThat(accountRepo.findAll()).hasSize(24);
    }

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
}
