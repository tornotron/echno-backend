package org.tornotron.echno_backend.finance.report.service;

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
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.service.JournalPostingService;
import org.tornotron.echno_backend.finance.report.dtos.BalanceSheetReport;
import org.tornotron.echno_backend.finance.report.dtos.ProfitAndLossReport;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceReport;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceRow;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for the financial-report aggregation against a real CockroachDB.
 * The reports read POSTED journal activity with native SQL, so they only exercise
 * meaningfully over real rows. A two-entry fixture (a sale funded to cash, then an
 * expense paid from cash) drives all three reports so the normal-balance arithmetic,
 * the retained-earnings roll-up, and the balance checks are pinned end to end.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReportService.class, JournalPostingService.class, JournalEntryMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class})
class ReportServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private JournalPostingService journalPostingService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private UUID cashId;      // ASSET  (debit-normal)
    private UUID revenueId;   // INCOME (credit-normal)
    private UUID expenseId;   // EXPENSE (debit-normal)

    private final LocalDate today = LocalDate.now();

    // The tenant seed (org + accounts) is committed, not held in the test's rolled-back
    // transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW and only sees committed
    // rows. The journal entries themselves are posted inside the test transaction, which the
    // report queries read in the same transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Report Org");
            Account cash = persistAccount(org, "1000", "Cash", AccountType.ASSET);
            Account revenue = persistAccount(org, "4000", "Revenue", AccountType.INCOME);
            Account expense = persistAccount(org, "5000", "Office Rent", AccountType.EXPENSE);
            entityManager.flush();
            orgId = org.getId();
            cashId = cash.getId();
            revenueId = revenue.getId();
            expenseId = expense.getId();
        });
        TenantContext.setCurrentOrgId(orgId);

        // Sale: cash +1000 against revenue. Expense: office rent 300 paid from cash.
        post(today, line(cashId, "1000", "0"), line(revenueId, "0", "1000"));
        post(today, line(expenseId, "300", "0"), line(cashId, "0", "300"));
        entityManager.flush();
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
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    // --- Trial balance ----------------------------------------------------

    @Test
    void trialBalance_placesEachAccountOnItsNormalSideAndBalances() {
        TrialBalanceReport report = reportService.trailBalanceReport(today);

        assertThat(report.balanced()).isTrue();
        assertThat(report.totalDebit()).isEqualByComparingTo("1000");
        assertThat(report.totalCredit()).isEqualByComparingTo("1000");

        // Cash is debit-normal and net debit 700 (1000 in, 300 out) → debit column.
        TrialBalanceRow cash = row(report, "1000");
        assertThat(cash.debitBalance()).isEqualByComparingTo("700");
        assertThat(cash.creditBalance()).isEqualByComparingTo("0");

        // Revenue is credit-normal → credit column carries 1000.
        TrialBalanceRow revenue = row(report, "4000");
        assertThat(revenue.creditBalance()).isEqualByComparingTo("1000");
        assertThat(revenue.debitBalance()).isEqualByComparingTo("0");

        // Expense is debit-normal → debit column carries 300.
        TrialBalanceRow expense = row(report, "5000");
        assertThat(expense.debitBalance()).isEqualByComparingTo("300");
    }

    // --- Profit and loss --------------------------------------------------

    @Test
    void profitAndLoss_nettsIncomeAgainstExpenseForThePeriod() {
        ProfitAndLossReport report = reportService.profitAndLoss(today.minusDays(1), today);

        assertThat(report.totalIncome()).isEqualByComparingTo("1000");
        assertThat(report.totalExpense()).isEqualByComparingTo("300");
        assertThat(report.netProfit()).isEqualByComparingTo("700");
        assertThat(report.income()).hasSize(1);
        assertThat(report.expense()).hasSize(1);
    }

    @Test
    void profitAndLoss_endDateBeforeStartDate_isRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> reportService.profitAndLoss(today, today.minusDays(5)));
    }

    // --- Balance sheet ----------------------------------------------------

    @Test
    void balanceSheet_flowsPeriodProfitIntoRetainedEarningsAndBalances() {
        BalanceSheetReport report = reportService.balanceSheet(today);

        // Only asset is cash at 700; the period profit of 700 becomes retained earnings,
        // so assets equal liabilities plus equity.
        assertThat(report.totalAssets()).isEqualByComparingTo("700");
        assertThat(report.retainedEarningsForPeriod()).isEqualByComparingTo("700");
        assertThat(report.totalEquity()).isEqualByComparingTo("700");
        assertThat(report.totalLiabilitiesAndEquity()).isEqualByComparingTo("700");
        assertThat(report.balanced()).isTrue();
    }

    // --- Helpers ----------------------------------------------------------

    private TrialBalanceRow row(TrialBalanceReport report, String code) {
        return report.rows().stream()
                .filter(r -> r.accountCode().equals(code))
                .findFirst().orElseThrow(() -> new AssertionError("no trial-balance row for account " + code));
    }

    private void post(LocalDate date, PostJournalRequest.LineRequest... lines) {
        journalPostingService.postInternal(
                new PostJournalRequest(date, "Report fixture", null, List.of(lines)), "MANUAL", null);
    }

    private PostJournalRequest.LineRequest line(UUID accountId, String debit, String credit) {
        return new PostJournalRequest.LineRequest(accountId, new BigDecimal(debit), new BigDecimal(credit), null);
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

    private Account persistAccount(Organization org, String code, String name, AccountType type) {
        Account account = new Account();
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        account.setParent(null);
        account.setActive(true);
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
}
