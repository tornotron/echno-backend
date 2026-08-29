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
import org.springframework.data.domain.Page;
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
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
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
 * Integration tests for {@link InvoiceService} against a real CockroachDB.
 *
 * <p>{@link InvoiceService#createDraft} pins the money the customer is billed: per-line tax
 * derived from the rate, the invoice subtotal/tax/total rolled up from the lines, and the guards
 * that keep a draft well-formed (date order, active customer, postable income revenue accounts).
 *
 * <p>{@link InvoiceService#findAll} pins the listing, which needs a real database for the two
 * things that are not visible from a mock: that the Hibernate {@code orgFilter} scopes both the
 * rows and the count, and that the fixed sort order actually pages.
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

    // A second tenant, so the listing can be asked to leak and shown not to.
    private Long otherOrgId;
    private UUID otherCustomerId;

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

            Organization otherOrg = persistOrganization("Rival Org");
            Customer otherCustomer = persistCustomer(otherOrg, "CUST-9", true);

            entityManager.flush();
            orgId = org.getId();
            customerId = customer.getId();
            inactiveCustomerId = inactive.getId();
            revenueAId = revenueA.getId();
            revenueBId = revenueB.getId();
            expenseId = expense.getId();
            inactiveRevenueId = inactiveRevenue.getId();
            otherOrgId = otherOrg.getId();
            otherCustomerId = otherCustomer.getId();
        });
        TenantContext.setCurrentOrgId(orgId);
    }

    @AfterEach
    void cleanup() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
        inCommittedTx(() -> {
            purgeOrganization(orgId);
            purgeOrganization(otherOrgId);
        });
    }

    private void purgeOrganization(Long org) {
        if (org == null) {
            return;
        }
        exec(org, "DELETE FROM invoice_lines WHERE invoice_id IN "
                + "(SELECT id FROM invoices WHERE organization_id = :org)");
        exec(org, "DELETE FROM invoices WHERE organization_id = :org");
        exec(org, "DELETE FROM document_sequence WHERE organization_id = :org");
        exec(org, "DELETE FROM accounts WHERE organization_id = :org");
        exec(org, "DELETE FROM customers WHERE organization_id = :org");
        exec(org, "DELETE FROM organization WHERE id = :org");
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

    // --- Listing ----------------------------------------------------------

    /**
     * The listing is the surface tenant isolation matters most on: by id a caller has to already
     * know the id, whereas a list hands over every row the query returns. This seeds two invoices
     * in each of two organizations and asks from inside each in turn, so a query that ignored the
     * scope would be caught whichever tenant it favoured, and asserts on the ids rather than the
     * count alone so swapping one row for another cannot pass.
     *
     * <p>The count is checked too. {@code getTotalElements} comes from a separate count query, and
     * a total that includes the other tenant's rows leaks how much business a rival is doing even
     * when no row of theirs is returned.
     */
    @Test
    void findAll_returnsOnlyTheInvoicesOfTheTenantAsking() {
        UUID mine = persistInvoice(orgId, customerId, "INV-A-1",
                LocalDate.of(2026, 8, 1), InvoiceStatus.ISSUED, "100", "0");
        UUID alsoMine = persistInvoice(orgId, customerId, "INV-A-2",
                LocalDate.of(2026, 8, 2), InvoiceStatus.DRAFT, "200", "0");
        UUID theirs = persistInvoice(otherOrgId, otherCustomerId, "INV-B-1",
                LocalDate.of(2026, 8, 3), InvoiceStatus.ISSUED, "300", "0");
        UUID alsoTheirs = persistInvoice(otherOrgId, otherCustomerId, "INV-B-2",
                LocalDate.of(2026, 8, 4), InvoiceStatus.DRAFT, "400", "0");

        enableOrgFilter(orgId);
        Page<InvoiceDto> asMine = service.findAll(null, null, false, 0, 10);
        assertThat(asMine.getContent()).extracting(InvoiceDto::id)
                .containsExactlyInAnyOrder(mine, alsoMine)
                .doesNotContain(theirs, alsoTheirs);
        assertThat(asMine.getTotalElements()).isEqualTo(2);
        disableOrgFilter();

        enableOrgFilter(otherOrgId);
        Page<InvoiceDto> asTheirs = service.findAll(null, null, false, 0, 10);
        assertThat(asTheirs.getContent()).extracting(InvoiceDto::id)
                .containsExactlyInAnyOrder(theirs, alsoTheirs)
                .doesNotContain(mine, alsoMine);
        assertThat(asTheirs.getTotalElements()).isEqualTo(2);
        disableOrgFilter();
    }

    /**
     * A filter narrows within the tenant and never widens past it: asking for the other tenant's
     * customer by id returns nothing rather than that customer's invoices. Without this a caller
     * who learned a customer id would have a way through the listing that the unfiltered case
     * does not show.
     */
    @Test
    void findAll_filtersByCustomerAndCannotReachAnotherTenantsCustomer() {
        UUID mine = persistInvoice(orgId, customerId, "INV-A-1",
                LocalDate.of(2026, 8, 1), InvoiceStatus.ISSUED, "100", "0");
        persistInvoice(orgId, inactiveCustomerId, "INV-A-2",
                LocalDate.of(2026, 8, 2), InvoiceStatus.ISSUED, "200", "0");
        persistInvoice(otherOrgId, otherCustomerId, "INV-B-1",
                LocalDate.of(2026, 8, 3), InvoiceStatus.ISSUED, "300", "0");

        enableOrgFilter(orgId);

        assertThat(service.findAll(customerId, null, false, 0, 10).getContent())
                .extracting(InvoiceDto::id)
                .containsExactly(mine);
        assertThat(service.findAll(otherCustomerId, null, false, 0, 10).getContent())
                .isEmpty();

        disableOrgFilter();
    }

    @Test
    void findAll_filtersByStatus() {
        UUID draft = persistInvoice(orgId, customerId, "INV-A-1",
                LocalDate.of(2026, 8, 1), InvoiceStatus.DRAFT, "100", "0");
        persistInvoice(orgId, customerId, "INV-A-2",
                LocalDate.of(2026, 8, 2), InvoiceStatus.ISSUED, "200", "0");
        persistInvoice(orgId, customerId, "INV-A-3",
                LocalDate.of(2026, 8, 3), InvoiceStatus.CANCELLED, "300", "0");

        enableOrgFilter(orgId);

        assertThat(service.findAll(null, InvoiceStatus.DRAFT, false, 0, 10).getContent())
                .extracting(InvoiceDto::id)
                .containsExactly(draft);

        disableOrgFilter();
    }

    /**
     * {@code openOnly} is the one filter {@code status} cannot express, because what is still owed
     * spans two statuses. A caller reduced to one status per request would have to make two and
     * page each separately to build a receivables view.
     */
    @Test
    void findAll_openOnlyReturnsWhatIsStillOwed() {
        persistInvoice(orgId, customerId, "INV-A-1",
                LocalDate.of(2026, 8, 1), InvoiceStatus.DRAFT, "100", "0");
        UUID issued = persistInvoice(orgId, customerId, "INV-A-2",
                LocalDate.of(2026, 8, 2), InvoiceStatus.ISSUED, "200", "0");
        UUID partlyPaid = persistInvoice(orgId, customerId, "INV-A-3",
                LocalDate.of(2026, 8, 3), InvoiceStatus.PARTIALLY_PAID, "300", "50");
        persistInvoice(orgId, customerId, "INV-A-4",
                LocalDate.of(2026, 8, 4), InvoiceStatus.PAID, "400", "400");
        persistInvoice(orgId, customerId, "INV-A-5",
                LocalDate.of(2026, 8, 5), InvoiceStatus.CANCELLED, "500", "0");

        enableOrgFilter(orgId);

        assertThat(service.findAll(null, null, true, 0, 10).getContent())
                .extracting(InvoiceDto::id)
                .containsExactlyInAnyOrder(issued, partlyPaid);
        // The two narrowing filters simply AND, so a contradictory pair matches nothing.
        assertThat(service.findAll(null, InvoiceStatus.PAID, true, 0, 10).getContent())
                .isEmpty();

        disableOrgFilter();
    }

    /**
     * Paging is only paging if the order is fixed. Without a deterministic sort the engine is free
     * to return the rows in a different order for each page, which lets one row appear on two
     * pages while another appears on none, and no assertion on a single page would notice.
     */
    @Test
    void findAll_pagesInAFixedNewestFirstOrder() {
        UUID first = persistInvoice(orgId, customerId, "INV-A-1",
                LocalDate.of(2026, 8, 1), InvoiceStatus.ISSUED, "100", "0");
        UUID second = persistInvoice(orgId, customerId, "INV-A-2",
                LocalDate.of(2026, 8, 2), InvoiceStatus.ISSUED, "200", "0");
        UUID third = persistInvoice(orgId, customerId, "INV-A-3",
                LocalDate.of(2026, 8, 3), InvoiceStatus.ISSUED, "300", "0");

        enableOrgFilter(orgId);

        Page<InvoiceDto> pageOne = service.findAll(null, null, false, 0, 2);
        assertThat(pageOne.getContent()).extracting(InvoiceDto::id).containsExactly(third, second);
        assertThat(pageOne.getTotalElements()).isEqualTo(3);
        assertThat(service.findAll(null, null, false, 1, 2).getContent())
                .extracting(InvoiceDto::id).containsExactly(first);

        disableOrgFilter();
    }

    // --- Helpers ----------------------------------------------------------

    private void enableOrgFilter(Long org) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", org);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    /**
     * Writes an invoice row directly, bypassing {@link InvoiceService#createDraft}. The listing
     * tests need invoices in a tenant the service cannot be asked to write into, and statuses the
     * lifecycle would take several steps to reach, neither of which the creation path is for.
     */
    private UUID persistInvoice(Long org, UUID customer, String number, LocalDate invoiceDate,
                                InvoiceStatus status, String total, String amountPaid) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setCustomer(entityManager.getReference(Customer.class, customer));
        invoice.setOrganization(entityManager.getReference(Organization.class, org));
        invoice.setInvoiceDate(invoiceDate);
        invoice.setDueDate(invoiceDate.plusDays(30));
        invoice.setStatus(status);
        invoice.setSubtotal(bd(total));
        invoice.setTaxTotal(BigDecimal.ZERO);
        invoice.setTotal(bd(total));
        invoice.setAmountPaid(bd(amountPaid));
        entityManager.persist(invoice);
        entityManager.flush();
        return invoice.getId();
    }

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

    private void exec(Long org, String sql) {
        entityManager.createNativeQuery(sql).setParameter("org", org).executeUpdate();
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
