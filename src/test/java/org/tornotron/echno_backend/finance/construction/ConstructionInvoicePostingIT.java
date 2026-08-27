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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
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
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntryLine;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
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
 *
 * <p>The AR route is exercised here too, because it is the only place both documents are
 * real: a sales invoice on a project with a client materializes an AR invoice whose total
 * must equal the construction invoice's to the paisa, since the entry the construction
 * invoice adopts is the AR invoice's. The two cancellation doors are pinned alongside it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConstructionInvoiceService.class, ConstructionInvoiceMapperImpl.class,
        org.tornotron.echno_backend.finance.invoice.service.InvoiceService.class,
        org.tornotron.echno_backend.finance.invoice.mapper.InvoiceMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class,
        JournalPostingService.class, JournalEntryMapperImpl.class, ConstructionPostingProperties.class,
        InvoicePostingProperties.class, UserContextService.class, ChartOfAccountsSeeder.class,
        org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver.class,
        org.tornotron.echno_backend.finance.settings.FinanceSettingsService.class})
class ConstructionInvoicePostingIT extends AbstractIntegrationTest {

    private static final long PROJECT_ID = 4001L;

    @Autowired
    private ConstructionInvoiceService service;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private ChartOfAccountsSeeder seeder;

    @Autowired
    private JournalEntryRepository journalRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private UUID customerId;

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
            exec("DELETE FROM invoice_lines WHERE invoice_id IN "
                    + "(SELECT id FROM invoices WHERE organization_id = :org)");
            exec("DELETE FROM invoices WHERE organization_id = :org");
            exec("DELETE FROM customers WHERE organization_id = :org");
            exec("DELETE FROM project WHERE organization_id = :org");
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
        // Remove the Accounts Payable control account the purchase posting needs, in a
        // committed transaction before the invoice is created. Deleting it inside the live
        // test transaction instead contends for the row lock with the service's own
        // REQUIRES_NEW numbering connection, which blocks until the statement timeout under a
        // slow CI runner. Committing it first makes the later approve resolve it as missing
        // cleanly: the account is gone before the invoice transaction takes its snapshot.
        inCommittedTx(() -> exec("DELETE FROM accounts WHERE organization_id = :org AND code = '2100'"));

        ConstructionInvoiceDto created = service.create(request(ConstructionInvoiceType.PURCHASE));
        service.submit(created.id());

        assertThatExceptionOfType(AccountNotFoundException.class)
                .isThrownBy(() -> service.approve(created.id()));
    }

    // The two AR tests below commit their writes instead of running inside the rolled-back
    // ambient transaction. The AR path writes an invoice whose lines reference the committed
    // chart of accounts, and holding those writes open leaves the cleanup's DELETE FROM
    // accounts waiting on the test's own transaction until the statement timeout. Committing
    // them is what ConstructionInvoiceAutoApprovalIT does for the same reason; the cleanup
    // already deletes every table they touch.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approve_salesInvoiceForAProjectClient_materializesAnArInvoiceThatTotalsTheSame() {
        ConstructionInvoiceDto created = service.create(clientRequest(seedClientProject()));
        service.submit(created.id());

        ConstructionInvoiceDto approved = service.approve(created.id());
        assertThat(approved.arInvoiceId()).isNotNull();

        InvoiceDto ar = invoiceService.findById(approved.arInvoiceId());
        assertThat(ar.customerId()).isEqualTo(customerId);
        // Pinned so the equality below cannot pass on two trivially equal numbers: 12.36%
        // tax on a 7.5%-discounted line lands well inside the store scale.
        assertThat(approved.totalAmount()).isEqualByComparingTo("6638.5453");
        // The whole design rests on this: the construction invoice adopts the AR invoice's
        // entry, so a receivable posted for a different amount than the invoice claims would
        // be a silent divergence on the money path.
        assertThat(ar.total()).isEqualByComparingTo(approved.totalAmount());
        assertThat(approved.journalEntryId()).isEqualTo(ar.journalEntryId());

        withJournalEntry(approved.journalEntryId(), je -> {
            assertThat(je.getSourceType()).isEqualTo("INVOICE");
            assertThat(debit(je, "1200")).isEqualByComparingTo(approved.totalAmount());
            assertBalanced(je);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancel_theArInvoiceOnItsOwn_isRefused_whileCancellingTheConstructionInvoiceUnwindsBoth() {
        ConstructionInvoiceDto created = service.create(clientRequest(seedClientProject()));
        service.submit(created.id());
        ConstructionInvoiceDto approved = service.approve(created.id());
        UUID arInvoiceId = approved.arInvoiceId();

        // The AR door is shut: the construction invoice owns the entry they share.
        assertThatExceptionOfType(InvalidJournalException.class)
                .isThrownBy(() -> invoiceService.cancel(arInvoiceId, "Cancelled from the AR module"))
                .withMessageContaining(created.invoiceNumber());

        ConstructionInvoiceDto cancelled = service.cancel(created.id(), "Client withdrew the claim");
        assertThat(cancelled.status()).isEqualTo(ConstructionInvoiceStatus.CANCELLED);
        assertThat(cancelled.reversalJournalEntryId()).isNotNull();
        assertThat(invoiceService.findById(arInvoiceId).status()).isEqualTo(InvoiceStatus.CANCELLED);

        withJournalEntry(approved.journalEntryId(),
                je -> assertThat(je.getStatus()).isEqualTo(JournalStatus.REVERSED));
        withJournalEntry(cancelled.reversalJournalEntryId(), this::assertBalanced);
    }

    // --- Helpers ----------------------------------------------------------

    /**
     * Commits a customer and a project that names it as its client, for the AR tests to bill
     * against. Committed rather than left in a test transaction because those tests run
     * outside one, for the reason given on {@link #approve_salesInvoiceForAProjectClient_materializesAnArInvoiceThatTotalsTheSame}.
     *
     * @return The id of the project, for the invoice request to bill against.
     */
    private Long seedClientProject() {
        Long[] projectId = new Long[1];
        inCommittedTx(() -> {
            Organization org = entityManager.getReference(Organization.class, orgId);

            Customer client = new Customer();
            client.setCode("CUST-POST-1");
            client.setName("Asset Homes Pvt Ltd");
            client.setOrganization(org);
            entityManager.persist(client);

            Project project = new Project();
            project.setProjectName("Tower B, Riverside Residences");
            project.setProjectAddress("12 Marina Road, Chennai");
            project.setOrganization(org);
            project.setCustomerId(client.getId());
            entityManager.persist(project);
            entityManager.flush();

            customerId = client.getId();
            projectId[0] = project.getId();
        });
        return projectId[0];
    }

    /** Reads a posted entry with its lines and accounts inside a transaction, for assertions. */
    private void withJournalEntry(UUID journalEntryId, java.util.function.Consumer<JournalEntry> assertions) {
        inCommittedTx(() -> assertions.accept(
                journalRepo.findByIdWithLines(journalEntryId).orElseThrow()));
    }

    /**
     * A sales invoice on the project that names a client, with one discounted line and one
     * undiscounted line at a tax rate that does not divide evenly, so the two documents have
     * to round the same way rather than merely happening to agree on round numbers.
     */
    private CreateConstructionInvoiceRequest clientRequest(Long projectId) {
        return new CreateConstructionInvoiceRequest(
                ConstructionInvoiceType.SALES,
                projectId,
                null, null, null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "Net 30", "Bank Transfer", "29ABCDE1234F1Z5", "GST",
                "AR materialization test", "Standard terms apply",
                List.of(
                        new ConstructionInvoiceLineRequest("Piling works", bd("7"), "lot",
                                bd("333.33"), bd("12.36"), bd("7.5"), null, null, null, null),
                        new ConstructionInvoiceLineRequest("Site supervision", bd("3"), "month",
                                bd("1249.99"), bd("12.36"), null, null, null, null, null)));
    }

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
                        bd("100"), bd("18"), null, null, null, null, null)));
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
