package org.tornotron.echno_backend.finance.construction;

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
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapperImpl;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntryLine;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pins the money path of the construction invoice approval workflow against a real
 * CockroachDB: approving a purchase invoice posts DR expense + DR GST input / CR
 * accounts payable; approving a sales invoice posts DR accounts receivable / CR revenue
 * + CR GST output; both store the journal-entry id. Cancel reverses the posted entry,
 * approving from the wrong status is refused, and a missing control account aborts.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConstructionInvoiceService.class, ConstructionInvoiceMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class,
        JournalPostingService.class, JournalEntryMapperImpl.class, ConstructionPostingProperties.class,
        InvoicePostingProperties.class, UserContextService.class, ChartOfAccountsSeeder.class})
class ConstructionInvoicePostingIT extends AbstractIntegrationTest {

    private static final long PROJECT_ID = 4001L;

    @Autowired
    private ConstructionInvoiceService service;

    @Autowired
    private ChartOfAccountsSeeder seeder;

    @Autowired
    private JournalEntryRepository journalRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;

    // The org and the chart of accounts must be committed, not held in the rolled-back
    // test transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW (document_sequence
    // has an FK to organization), and the journal-entry lines reference committed accounts.
    // The invoice and its journal entry stay in the rolled-back test transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Posting Org");
            entityManager.flush();
            orgId = org.getId();
            TenantContext.setCurrentOrgId(orgId);
            seeder.seedDefaults();
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
            exec("DELETE FROM journal_entry_lines WHERE journal_entry_id IN "
                    + "(SELECT id FROM journal_entries WHERE organization_id = :org)");
            exec("DELETE FROM journal_entries WHERE organization_id = :org");
            exec("DELETE FROM construction_invoice_lines WHERE invoice_id IN "
                    + "(SELECT id FROM construction_invoices WHERE organization_id = :org)");
            exec("DELETE FROM construction_invoices WHERE organization_id = :org");
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    @Test
    void approve_purchaseInvoice_postsExpenseGstInputAgainstPayable() {
        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.PURCHASE));
        ConstructionInvoiceDto pending = service.submit(created.id());
        assertThat(pending.status()).isEqualTo(ConstructionInvoiceStatus.PENDING);

        ConstructionInvoiceDto approved = service.approve(created.id());
        assertThat(approved.status()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(approved.journalEntryId()).isNotNull();

        JournalEntry je = journalRepo.findByIdWithLines(approved.journalEntryId()).orElseThrow();
        assertThat(je.getSourceType()).isEqualTo("CONSTRUCTION_INVOICE");
        assertThat(je.getSourceId()).isEqualTo(created.id());
        assertThat(debit(je, "5100")).isEqualByComparingTo("1000");   // net (expense)
        assertThat(debit(je, "1410")).isEqualByComparingTo("180");    // GST input
        assertThat(credit(je, "2100")).isEqualByComparingTo("1180");  // gross (payable)
        assertBalanced(je);
    }

    @Test
    void approve_salesInvoice_postsReceivableAgainstRevenueAndGstOutput() {
        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.SALES));
        service.submit(created.id());

        ConstructionInvoiceDto approved = service.approve(created.id());
        assertThat(approved.status()).isEqualTo(ConstructionInvoiceStatus.APPROVED);
        assertThat(approved.journalEntryId()).isNotNull();

        JournalEntry je = journalRepo.findByIdWithLines(approved.journalEntryId()).orElseThrow();
        assertThat(debit(je, "1200")).isEqualByComparingTo("1180");   // gross (receivable)
        assertThat(credit(je, "4100")).isEqualByComparingTo("1000");  // net (revenue)
        assertThat(credit(je, "2210")).isEqualByComparingTo("180");   // GST output
        assertBalanced(je);
    }

    @Test
    void approve_fromNonPendingStatus_isRejected() {
        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.PURCHASE));
        // A freshly created invoice is DRAFT, not PENDING.
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.approve(created.id()));
    }

    @Test
    void cancel_approvedInvoice_reversesTheJournalEntry() {
        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.PURCHASE));
        service.submit(created.id());
        ConstructionInvoiceDto approved = service.approve(created.id());
        UUID originalJeId = approved.journalEntryId();

        ConstructionInvoiceDto cancelled = service.cancel(created.id(), "Ordered in error");
        assertThat(cancelled.status()).isEqualTo(ConstructionInvoiceStatus.CANCELLED);
        assertThat(cancelled.reversalJournalEntryId()).isNotNull();

        JournalEntry original = journalRepo.findByIdWithLines(originalJeId).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);

        JournalEntry reversal = journalRepo.findByIdWithLines(cancelled.reversalJournalEntryId()).orElseThrow();
        // The payable was credited on the original; on the reversal it is debited back.
        assertThat(debit(reversal, "2100")).isEqualByComparingTo("1180");
        assertBalanced(reversal);
    }

    @Test
    void approve_withMissingControlAccount_isRejected() {
        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.PURCHASE));
        service.submit(created.id());

        // Remove the Accounts Payable control account the purchase posting needs. Delete
        // within the test transaction (not a committed one) so the approve query, which runs
        // in the same long-lived transaction, sees it gone under CockroachDB's serializable
        // snapshot.
        entityManager.createNativeQuery("DELETE FROM accounts WHERE organization_id = :org AND code = '2100'")
                .setParameter("org", orgId).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThatExceptionOfType(AccountNotFoundException.class)
                .isThrownBy(() -> service.approve(created.id()));
    }

    // --- Helpers ----------------------------------------------------------

    private CreateConstructionInvoiceRequest request(ConstructionInvoiceType type) {
        return new CreateConstructionInvoiceRequest(
                type,
                PROJECT_ID,
                null, null, null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "Net 30", "Bank Transfer", "29ABCDE1234F1Z5", "GST",
                "Posting test", "Standard terms apply",
                // 10 * 100 = 1000 subtotal, 18% tax = 180, no discount -> gross 1180
                List.of(new ConstructionInvoiceLineRequest("Cement supply", bd("10"), "nos",
                        bd("100"), bd("18"), null, null, null, null)));
    }

    private BigDecimal debit(JournalEntry je, String code) {
        return lineFor(je, code).getDebit();
    }

    private BigDecimal credit(JournalEntry je, String code) {
        return lineFor(je, code).getCredit();
    }

    private JournalEntryLine lineFor(JournalEntry je, String code) {
        return je.getLines().stream()
                .filter(l -> l.getAccount().getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No journal line for account " + code));
    }

    private void assertBalanced(JournalEntry je) {
        BigDecimal debits = je.getLines().stream().map(JournalEntryLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = je.getLines().stream().map(JournalEntryLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
