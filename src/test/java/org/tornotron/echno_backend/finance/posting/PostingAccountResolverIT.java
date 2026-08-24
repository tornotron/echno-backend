package org.tornotron.echno_backend.finance.posting;

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
import org.tornotron.echno_backend.finance.construction.ConstructionPostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.posting.domain.PostingAccountMapping;
import org.tornotron.echno_backend.finance.posting.repositories.PostingAccountMappingRepository;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the posting-account resolution against a real CockroachDB: with no mapping the resolver
 * falls back to the configured default code, and with a mapping set it returns the mapped account
 * and flags the source as MAPPED.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Commit the writes (no rolled-back ambient transaction) so cleanup can delete the mapping and the
// account it references without contending with an open write intent.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostingAccountResolver.class, TenantEntityHelper.class, JpaAuditingConfig.class,
        ConstructionPostingProperties.class, InvoicePostingProperties.class, ChartOfAccountsSeeder.class})
class PostingAccountResolverIT extends AbstractIntegrationTest {

    @Autowired
    private PostingAccountResolver resolver;

    @Autowired
    private ChartOfAccountsSeeder seeder;

    @Autowired
    private AccountRepository accountRepo;

    @Autowired
    private PostingAccountMappingRepository mappingRepo;

    @Autowired
    private TenantEntityHelper tenantEntityHelper;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Resolver Org");
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
            exec("DELETE FROM posting_account_mapping WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    @Test
    void resolve_withNoMapping_returnsConfiguredDefaultAccount() {
        PostingAccountResolver.Resolved payable = resolver.resolveWithSource(PostingRole.ACCOUNTS_PAYABLE);
        assertThat(payable.source()).isEqualTo(PostingAccountResolver.Source.DEFAULT);
        assertThat(payable.account().getCode()).isEqualTo("2100");

        assertThat(resolver.resolve(PostingRole.ACCOUNTS_RECEIVABLE).getCode()).isEqualTo("1200");
        assertThat(resolver.resolve(PostingRole.GST_OUTPUT).getCode()).isEqualTo("2210");
        assertThat(resolver.resolve(PostingRole.GST_INPUT).getCode()).isEqualTo("1410");
        assertThat(resolver.resolve(PostingRole.DEFAULT_REVENUE).getCode()).isEqualTo("4100");
        assertThat(resolver.resolve(PostingRole.DEFAULT_EXPENSE).getCode()).isEqualTo("5100");
    }

    @Test
    void resolve_withMapping_returnsMappedAccount() {
        // Point ACCOUNTS_PAYABLE at a different account than the configured default 2100. Committed so
        // that resolving reads it back and cleanup can delete it cleanly.
        inCommittedTx(() -> {
            Account other = accountRepo.findByCodeAndOrganization_Id("5100", orgId).orElseThrow();
            PostingAccountMapping mapping = new PostingAccountMapping();
            mapping.setRole(PostingRole.ACCOUNTS_PAYABLE);
            mapping.setAccount(other);
            mapping.setOrganization(other.getOrganization());
            mappingRepo.save(mapping);
        });

        // Resolve inside a transaction so the lazily loaded mapped account can be read.
        inReadTx(() -> {
            PostingAccountResolver.Resolved payable = resolver.resolveWithSource(PostingRole.ACCOUNTS_PAYABLE);
            assertThat(payable.source()).isEqualTo(PostingAccountResolver.Source.MAPPED);
            assertThat(payable.account().getCode()).isEqualTo("5100");

            // An unmapped role still falls back to its default.
            PostingAccountResolver.Resolved gstInput = resolver.resolveWithSource(PostingRole.GST_INPUT);
            assertThat(gstInput.source()).isEqualTo(PostingAccountResolver.Source.DEFAULT);
            assertThat(gstInput.account().getCode()).isEqualTo("1410");
        });
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

    private void inReadTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setReadOnly(true);
        tt.executeWithoutResult(status -> work.run());
    }
}
