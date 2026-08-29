package org.tornotron.echno_backend.finance.construction;

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
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapperImpl;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.finance.settings.dtos.UpdateFinanceSettingsRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the approval-threshold behaviour of construction-invoice submit against a real CockroachDB:
 * an invoice whose total is below the organization threshold is auto-approved and posted on submit,
 * while one at or above the threshold, and one with no threshold set, stay PENDING.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Commit the service writes (no rolled-back ambient transaction) so submit's own transaction reads
// the finance-settings threshold it needs, and so cleanup deletes committed rows without contending
// with an open write intent.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ConstructionInvoiceService.class, ConstructionInvoiceMapperImpl.class,
        org.tornotron.echno_backend.finance.invoice.service.InvoiceService.class,
        org.tornotron.echno_backend.finance.invoice.mapper.InvoiceMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class,
        JournalPostingService.class, JournalEntryMapperImpl.class, ConstructionPostingProperties.class,
        InvoicePostingProperties.class, UserContextService.class, ChartOfAccountsSeeder.class,
        PostingAccountResolver.class, FinanceSettingsService.class,
        SelfApprovalPolicy.class, OrganizationSecurityService.class})
class ConstructionInvoiceAutoApprovalIT extends AbstractIntegrationTest {

    // Total of the built invoice: 10 * 100 = 1000 subtotal, 18% tax = 180, gross 1180.
    private static final long PROJECT_ID = 7001L;

    @Autowired
    private ConstructionInvoiceService service;

    @Autowired
    private ChartOfAccountsSeeder seeder;

    @Autowired
    private FinanceSettingsService financeSettingsService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Auto Approve Org");
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
            exec("DELETE FROM journal_entry_lines WHERE journal_entry_id IN "
                    + "(SELECT id FROM journal_entries WHERE organization_id = :org)");
            exec("DELETE FROM journal_entries WHERE organization_id = :org");
            exec("DELETE FROM construction_invoice_lines WHERE invoice_id IN "
                    + "(SELECT id FROM construction_invoices WHERE organization_id = :org)");
            exec("DELETE FROM construction_invoices WHERE organization_id = :org");
            exec("DELETE FROM finance_settings WHERE organization_id = :org");
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    @Test
    void submit_belowOrgThreshold_autoApprovesAndPosts() {
        financeSettingsService.update(new UpdateFinanceSettingsRequest(new BigDecimal("2000")));

        ConstructionInvoiceDto created = service.create(request());
        ConstructionInvoiceDto result = service.submit(created.id());

        assertThat(result.status()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(result.journalEntryId()).isNotNull();
        assertThat(result.submittedAt()).isNotNull();
        assertThat(result.approvedAt()).isNotNull();
    }

    @Test
    void submit_atOrAboveOrgThreshold_staysPending() {
        financeSettingsService.update(new UpdateFinanceSettingsRequest(new BigDecimal("500")));

        ConstructionInvoiceDto created = service.create(request());
        ConstructionInvoiceDto result = service.submit(created.id());

        assertThat(result.status()).isEqualTo(ConstructionInvoiceStatus.PENDING);
        assertThat(result.journalEntryId()).isNull();
    }

    @Test
    void submit_withNoThreshold_staysPending() {
        ConstructionInvoiceDto created = service.create(request());
        ConstructionInvoiceDto result = service.submit(created.id());

        assertThat(result.status()).isEqualTo(ConstructionInvoiceStatus.PENDING);
        assertThat(result.journalEntryId()).isNull();
    }

    // --- Helpers ----------------------------------------------------------

    private CreateConstructionInvoiceRequest request() {
        return new CreateConstructionInvoiceRequest(
                ConstructionInvoiceType.PURCHASE,
                PROJECT_ID,
                null, null, null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "Net 30", "Bank Transfer", "29ABCDE1234F1Z5", "GST",
                "Threshold test", "Standard terms apply",
                List.of(new ConstructionInvoiceLineRequest("Cement supply", new BigDecimal("10"), "nos",
                        new BigDecimal("100"), new BigDecimal("18"), null, null, null, null, null)));
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
