package org.tornotron.echno_backend.finance.payment.service;

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
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
import org.tornotron.echno_backend.finance.payment.dtos.PaymentDto;
import org.tornotron.echno_backend.finance.payment.dtos.RecordPaymentRequest;
import org.tornotron.echno_backend.finance.payment.mapper.PaymentMapperImpl;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link PaymentService#record} against a real CockroachDB:
 * the cash-application path that settles receivables. These pin the rules that keep
 * cash and the ledger in step: allocations must equal the payment and never exceed an
 * invoice's balance, only open invoices of the paying customer accept it, the invoice
 * moves to PARTIALLY_PAID/PAID exactly, and the same idempotency key never double-posts.
 *
 * <p>Each {@code record} call runs in its own committed transaction: it takes a
 * pessimistic {@code SELECT ... FOR UPDATE} on the invoice and synchronously drives the
 * REQUIRES_NEW entry-number generator, which self-deadlocks if the lock is held open by
 * the never-committing test transaction. Committing releases it, so invoice state is then
 * read back with a committed native query rather than the test's persistence context.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentService.class, PaymentMapperImpl.class, JournalPostingService.class,
        JournalEntryMapperImpl.class, EntryNumberGenerator.class, TenantEntityHelper.class,
        InvoicePostingProperties.class, JpaAuditingConfig.class,
        org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver.class,
        org.tornotron.echno_backend.finance.construction.ConstructionPostingProperties.class})
class PaymentServiceIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService service;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private UUID customerId;
    private UUID otherCustomerId;
    private UUID bankAccountId;
    private UUID inactiveBankAccountId;
    private UUID issuedInvoiceId;   // total 295, unpaid, ISSUED, belongs to customer
    private UUID draftInvoiceId;    // total 100, DRAFT (not open for payment)

    // Committed seed: EntryNumberGenerator.next() runs in REQUIRES_NEW and the posting
    // looks up the AR account, so orgs/customers/accounts/invoices must be committed,
    // not held in the rolled-back test transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inTx(() -> {
            Organization org = persistOrganization("Payment Org");
            Customer customer = persistCustomer(org, "CUST-1");
            Customer other = persistCustomer(org, "CUST-2");
            // AR control account looked up by code in InvoicePostingProperties (default 1200)
            Account ar = persistAccount(org, "1200", "Accounts Receivable");
            Account bankLedger = persistAccount(org, "1000", "Bank");
            CompanyBankAccount bank = persistBank(org, bankLedger, true);
            CompanyBankAccount inactiveBank = persistBank(org, bankLedger, false);
            Invoice issued = persistInvoice(org, customer, "INV-1", InvoiceStatus.ISSUED, bd("295"));
            Invoice draft = persistInvoice(org, customer, "INV-2", InvoiceStatus.DRAFT, bd("100"));

            entityManager.flush();
            orgId = org.getId();
            customerId = customer.getId();
            otherCustomerId = other.getId();
            bankAccountId = bank.getId();
            inactiveBankAccountId = inactiveBank.getId();
            issuedInvoiceId = issued.getId();
            draftInvoiceId = draft.getId();
            return null;
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
        inTx(() -> {
            exec("DELETE FROM payment_allocations WHERE payment_id IN "
                    + "(SELECT id FROM payments WHERE organization_id = :org)");
            exec("DELETE FROM payments WHERE organization_id = :org");
            exec("DELETE FROM journal_entry_lines WHERE journal_entry_id IN "
                    + "(SELECT id FROM journal_entries WHERE organization_id = :org)");
            exec("DELETE FROM journal_entries WHERE organization_id = :org");
            exec("DELETE FROM invoices WHERE organization_id = :org");
            exec("DELETE FROM company_bank_accounts WHERE organization_id = :org");
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM customers WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
            return null;
        });
    }

    // --- Happy paths ------------------------------------------------------

    @Test
    void record_fullAllocation_settlesInvoiceAndPostsJournal() {
        PaymentDto dto = inTx(() -> service.record(request(bd("295"), alloc(issuedInvoiceId, "295")), null));

        assertThat(dto.paymentNumber()).startsWith("PAY");
        assertThat(dto.amount()).isEqualByComparingTo("295");
        assertThat(dto.journalEntryId()).isNotNull();
        assertThat(dto.allocations()).hasSize(1);

        assertThat(invoiceStatus(issuedInvoiceId)).isEqualTo(InvoiceStatus.PAID.name());
        assertThat(paidAmount(issuedInvoiceId)).isEqualByComparingTo("295");
    }

    @Test
    void record_partialAllocation_marksInvoicePartiallyPaid() {
        inTx(() -> service.record(request(bd("100"), alloc(issuedInvoiceId, "100")), null));

        assertThat(invoiceStatus(issuedInvoiceId)).isEqualTo(InvoiceStatus.PARTIALLY_PAID.name());
        assertThat(paidAmount(issuedInvoiceId)).isEqualByComparingTo("100");
    }

    // --- Allocation invariants -------------------------------------------

    @Test
    void record_allocationsNotEqualToAmount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                inTx(() -> service.record(request(bd("295"), alloc(issuedInvoiceId, "200")), null)));
    }

    @Test
    void record_allocationExceedsInvoiceBalance_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                inTx(() -> service.record(request(bd("400"), alloc(issuedInvoiceId, "400")), null)));
    }

    // --- Invoice eligibility ---------------------------------------------

    @Test
    void record_againstNonOpenInvoice_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                inTx(() -> service.record(request(bd("100"), alloc(draftInvoiceId, "100")), null)));
    }

    @Test
    void record_invoiceOfDifferentCustomer_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                inTx(() -> service.record(new RecordPaymentRequest(otherCustomerId,
                        LocalDate.of(2026, 8, 15), bd("295"), bankAccountId, null, null,
                        alloc(issuedInvoiceId, "295")), null)));
    }

    // --- Bank account -----------------------------------------------------

    @Test
    void record_throughInactiveBankAccount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                inTx(() -> service.record(new RecordPaymentRequest(customerId,
                        LocalDate.of(2026, 8, 15), bd("295"), inactiveBankAccountId, null, null,
                        alloc(issuedInvoiceId, "295")), null)));
    }

    // --- Idempotency ------------------------------------------------------

    @Test
    void record_sameIdempotencyKey_returnsExistingPaymentWithoutDoublePosting() {
        PaymentDto first = inTx(() -> service.record(request(bd("100"), alloc(issuedInvoiceId, "100")), "key-1"));
        PaymentDto second = inTx(() -> service.record(request(bd("100"), alloc(issuedInvoiceId, "100")), "key-1"));

        assertThat(second.id()).isEqualTo(first.id());
        // The invoice was paid once (100), not twice.
        assertThat(paidAmount(issuedInvoiceId)).isEqualByComparingTo("100");
    }

    // --- Helpers ----------------------------------------------------------

    private RecordPaymentRequest request(BigDecimal amount, List<RecordPaymentRequest.AllocationRequest> allocations) {
        return new RecordPaymentRequest(customerId, LocalDate.of(2026, 8, 15), amount,
                bankAccountId, "UTR123", null, allocations);
    }

    private List<RecordPaymentRequest.AllocationRequest> alloc(UUID invoiceId, String amount) {
        return List.of(new RecordPaymentRequest.AllocationRequest(invoiceId, new BigDecimal(amount)));
    }

    private String invoiceStatus(UUID id) {
        return (String) inTx(() -> entityManager
                .createNativeQuery("SELECT status FROM invoices WHERE id = :id")
                .setParameter("id", id).getSingleResult());
    }

    private BigDecimal paidAmount(UUID id) {
        Object value = inTx(() -> entityManager
                .createNativeQuery("SELECT amount_paid FROM invoices WHERE id = :id")
                .setParameter("id", id).getSingleResult());
        return new BigDecimal(value.toString());
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

    private Customer persistCustomer(Organization org, String code) {
        Customer customer = new Customer();
        customer.setCode(code);
        customer.setName("Customer " + code);
        customer.setActive(true);
        customer.setOrganization(org);
        entityManager.persist(customer);
        return customer;
    }

    private Account persistAccount(Organization org, String code, String name) {
        Account account = new Account();
        account.setCode(code);
        account.setName(name);
        account.setType(AccountType.ASSET);
        account.setActive(true);
        account.setOrganization(org);
        entityManager.persist(account);
        return account;
    }

    private CompanyBankAccount persistBank(Organization org, Account ledger, boolean active) {
        CompanyBankAccount bank = new CompanyBankAccount();
        bank.setBankName("Test Bank");
        bank.setAccountNumber("00011122233");
        bank.setAccountHolderName("Echno");
        bank.setLedgerAccount(ledger);
        bank.setActive(active);
        bank.setOrganization(org);
        entityManager.persist(bank);
        return bank;
    }

    private Invoice persistInvoice(Organization org, Customer customer, String number,
                                   InvoiceStatus status, BigDecimal total) {
        Invoice inv = new Invoice();
        inv.setInvoiceNumber(number);
        inv.setCustomer(customer);
        inv.setInvoiceDate(LocalDate.of(2026, 8, 1));
        inv.setDueDate(LocalDate.of(2026, 8, 31));
        inv.setStatus(status);
        inv.setSubtotal(total);
        inv.setTaxTotal(BigDecimal.ZERO);
        inv.setTotal(total);
        inv.setAmountPaid(BigDecimal.ZERO);
        inv.setOrganization(org);
        entityManager.persist(inv);
        return inv;
    }

    private void exec(String sql) {
        entityManager.createNativeQuery(sql).setParameter("org", orgId).executeUpdate();
    }

    // Runs work in a committed REQUIRES_NEW transaction so its writes and pessimistic
    // locks commit independently of the rolled-back test transaction.
    private <T> T inTx(Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> work.get());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
