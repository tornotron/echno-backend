package org.tornotron.echno_backend.finance.invoice.service;

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
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.invoice.InvoicePostingProperties;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.mapper.InvoiceMapperImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for {@link InvoiceService#createDraft} against a real CockroachDB.
 * These pin the money the customer is billed: per-line tax derived from the rate, the
 * invoice subtotal/tax/total rolled up from the lines, and the guards that keep a draft
 * well-formed (date order, active customer, postable income revenue accounts).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InvoiceService.class, InvoiceMapperImpl.class, JournalPostingService.class,
        JournalEntryMapperImpl.class, EntryNumberGenerator.class, TenantEntityHelper.class,
        InvoicePostingProperties.class, JpaAuditingConfig.class,
        org.tornotron.echno_backend.finance.posting.service.PostingAccountResolver.class,
        org.tornotron.echno_backend.finance.construction.ConstructionPostingProperties.class})
class InvoiceServiceIT extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService service;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private UUID customerId;
    private UUID inactiveCustomerId;
    private UUID revenueAId;   // active INCOME
    private UUID revenueBId;   // active INCOME
    private UUID expenseId;    // active EXPENSE (wrong type)
    private UUID inactiveRevenueId; // inactive INCOME

    // The tenant seed must be committed, not held in the rolled-back test transaction:
    // EntryNumberGenerator.next() runs in REQUIRES_NEW and only sees committed rows.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Invoice Org");
            Customer customer = persistCustomer(org, "CUST-1", true);
            Customer inactive = persistCustomer(org, "CUST-2", false);
            Account revenueA = persistAccount(org, "4000", "Revenue A", AccountType.INCOME, true);
            Account revenueB = persistAccount(org, "4001", "Revenue B", AccountType.INCOME, true);
            Account expense = persistAccount(org, "5000", "Expense", AccountType.EXPENSE, true);
            Account inactiveRevenue = persistAccount(org, "4009", "Closed Revenue", AccountType.INCOME, false);

            entityManager.flush();
            orgId = org.getId();
            customerId = customer.getId();
            inactiveCustomerId = inactive.getId();
            revenueAId = revenueA.getId();
            revenueBId = revenueB.getId();
            expenseId = expense.getId();
            inactiveRevenueId = inactiveRevenue.getId();
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
            exec("DELETE FROM invoice_lines WHERE invoice_id IN "
                    + "(SELECT id FROM invoices WHERE organization_id = :org)");
            exec("DELETE FROM invoices WHERE organization_id = :org");
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM customers WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    // --- Totals -----------------------------------------------------------

    @Test
    void createDraft_computesPerLineTaxAndRolledUpTotals() {
        InvoiceDto dto = service.createDraft(new CreateInvoiceRequest(
                customerId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "First bill",
                List.of(
                        // 2 * 100 = 200 subtotal, 18% tax = 36
                        new CreateInvoiceRequest.LineRequest("Cement", bd("2"), bd("100"), bd("18"), revenueAId),
                        // 1 * 50 = 50 subtotal, 18% tax = 9
                        new CreateInvoiceRequest.LineRequest("Steel", bd("1"), bd("50"), bd("18"), revenueBId)
                )));

        assertThat(dto.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(dto.invoiceNumber()).startsWith("INV");
        assertThat(dto.lines()).hasSize(2);
        assertThat(dto.subtotal()).isEqualByComparingTo("250");
        assertThat(dto.taxTotal()).isEqualByComparingTo("45");
        assertThat(dto.total()).isEqualByComparingTo("295");
        assertThat(dto.amountPaid()).isEqualByComparingTo("0");
        assertThat(dto.balanceDue()).isEqualByComparingTo("295");
    }

    @Test
    void createDraft_roundsPerLineTaxAtStoreScale() {
        // 33.33 * 18% = 5.9994 (exact at 4dp); total = 33.33 + 5.9994 = 39.3294
        InvoiceDto dto = service.createDraft(new CreateInvoiceRequest(
                customerId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null,
                List.of(new CreateInvoiceRequest.LineRequest("Odd", bd("1"), bd("33.33"), bd("18"), revenueAId))));

        assertThat(dto.subtotal()).isEqualByComparingTo("33.33");
        assertThat(dto.taxTotal()).isEqualByComparingTo("5.9994");
        assertThat(dto.total()).isEqualByComparingTo("39.3294");
    }

    // --- Guards -----------------------------------------------------------

    @Test
    void createDraft_dueDateBeforeInvoiceDate_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.createDraft(new CreateInvoiceRequest(
                        customerId, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1), null,
                        List.of(new CreateInvoiceRequest.LineRequest("X", bd("1"), bd("10"), bd("0"), revenueAId)))));
    }

    @Test
    void createDraft_inactiveCustomer_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.createDraft(oneLine(inactiveCustomerId, revenueAId)));
    }

    @Test
    void createDraft_unknownCustomer_isRejected() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() ->
                service.createDraft(oneLine(UUID.randomUUID(), revenueAId)));
    }

    @Test
    void createDraft_nonIncomeRevenueAccount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.createDraft(oneLine(customerId, expenseId)));
    }

    @Test
    void createDraft_inactiveRevenueAccount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.createDraft(oneLine(customerId, inactiveRevenueId)));
    }

    @Test
    void createDraft_unknownRevenueAccount_isRejected() {
        assertThatExceptionOfType(AccountNotFoundException.class).isThrownBy(() ->
                service.createDraft(oneLine(customerId, UUID.randomUUID())));
    }

    // --- Helpers ----------------------------------------------------------

    private CreateInvoiceRequest oneLine(UUID customer, UUID revenueAccount) {
        return new CreateInvoiceRequest(customer,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null,
                List.of(new CreateInvoiceRequest.LineRequest("Line", bd("1"), bd("100"), bd("18"), revenueAccount)));
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

    private Customer persistCustomer(Organization org, String code, boolean active) {
        Customer customer = new Customer();
        customer.setCode(code);
        customer.setName("Customer " + code);
        customer.setActive(active);
        customer.setOrganization(org);
        entityManager.persist(customer);
        return customer;
    }

    private Account persistAccount(Organization org, String code, String name, AccountType type, boolean active) {
        Account account = new Account();
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        account.setActive(active);
        account.setOrganization(org);
        entityManager.persist(account);
        return account;
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
