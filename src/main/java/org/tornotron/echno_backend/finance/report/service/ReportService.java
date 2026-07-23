package org.tornotron.echno_backend.finance.report.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.report.dtos.BalanceSheetReport;
import org.tornotron.echno_backend.finance.report.dtos.ProfitAndLossReport;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceRow;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final EntityManager em;


    @Transactional(readOnly = true)
    public TrialBalanceReport trailBalanceReport(LocalDate asOfDate) {
        // Native SQL bypasses the Hibernate orgFilter, so scope by organization explicitly.
        Long orgId = TenantContext.getCurrentOrgId();
        String sql = """
                SELECT a.code, a.name, a.type,
                                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                                   COALESCE(SUM(l.credit), 0) AS total_credit
                            FROM accounts a
                            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                                  AND je.status = 'POSTED'
                                  AND je.entry_date <= :asOf
                """
                + jeOrgJoin(orgId)
                + acctOrgWhere(orgId)
                + """
                            GROUP BY a.code, a.name, a.type
                            HAVING COALESCE(SUM(l.debit), 0) <> 0
                                OR COALESCE(SUM(l.credit), 0) <> 0
                            ORDER BY a.code
                """;

        var query = em.createNativeQuery(sql).setParameter("asOf", asOfDate);
        if (orgId != null) query.setParameter("orgId", orgId);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = query.getResultList();

        List<TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            BigDecimal net = td.subtract(tc);
            BigDecimal dr = BigDecimal.ZERO;
            BigDecimal cr = BigDecimal.ZERO;
            if(type.isDebitNormal()) {
                if(net.signum() >= 0) dr = net; else cr = net.negate();
            } else {
                if(net.signum() <= 0) cr = net.negate(); else dr = net;
            }

            totalDebit = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);

            rows.add(new TrialBalanceRow(code, name, type,
                    MoneyUtils.forDisplay(td), MoneyUtils.forDisplay(tc),
                    MoneyUtils.forDisplay(dr), MoneyUtils.forDisplay(cr)));
        }

        totalDebit = MoneyUtils.forDisplay(totalDebit);
        totalCredit = MoneyUtils.forDisplay(totalCredit);
        boolean balanced = totalDebit.compareTo(totalCredit)==0;

        if(!balanced) {
            log.error("TRAIL BALANCE DOES NOT BALANCE as of {}: dr={} cr={}",
                    asOfDate, totalDebit, totalCredit);
        }

        return new TrialBalanceReport(asOfDate, rows, totalDebit, totalCredit, balanced);
    }

    @Transactional(readOnly = true)
    public ProfitAndLossReport profitAndLoss(LocalDate fromDate, LocalDate toDate) {
        if(toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date (" + toDate + ") cannot be before the start date (" + fromDate + ")");
        }

        // Native SQL bypasses the Hibernate orgFilter, so scope by organization explicitly.
        Long orgId = TenantContext.getCurrentOrgId();
        String sql = """
                SELECT a.code, a.name, a.type,
                                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                                   COALESCE(SUM(l.credit), 0) AS total_credit
                            FROM accounts a
                            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                                  AND je.status = 'POSTED'
                                  AND je.entry_date BETWEEN :from AND :to
                """
                + jeOrgJoin(orgId)
                + """
                            WHERE a.type IN ('INCOME', 'EXPENSE')
                """
                + acctOrgAnd(orgId)
                + """
                            GROUP BY a.code, a.name, a.type
                            ORDER BY a.code
              """;

        var query = em.createNativeQuery(sql)
                .setParameter("from", fromDate)
                .setParameter("to", toDate);
        if (orgId != null) query.setParameter("orgId", orgId);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = query.getResultList();

        List<ProfitAndLossReport.AccountLine> income = new ArrayList<>();
        List<ProfitAndLossReport.AccountLine> expense = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for(Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            if(type == AccountType.INCOME) {
                BigDecimal balance = tc.subtract(td);
                if(balance.signum() != 0) {
                    income.add(new ProfitAndLossReport.AccountLine(
                            code, name, MoneyUtils.forDisplay(balance)));
                    totalIncome = totalIncome.add(balance);
                }
            } else  {
                BigDecimal balance = td.subtract(tc);
                if(balance.signum() != 0) {
                    expense.add(new ProfitAndLossReport.AccountLine(
                            code, name, MoneyUtils.forDisplay(balance)));
                    totalExpense = totalExpense.add(balance);
                }
            }
        }

        BigDecimal netProfit = MoneyUtils.forDisplay(totalIncome.subtract(totalExpense));
        return new ProfitAndLossReport(
                fromDate,
                toDate,
                income,
                MoneyUtils.forDisplay(totalIncome),
                expense,
                MoneyUtils.forDisplay(totalExpense),
                netProfit
        );

    }

    @Transactional(readOnly = true)
    public BalanceSheetReport balanceSheet(LocalDate asOfDate) {
        // Native SQL bypasses the Hibernate orgFilter, so scope by organization explicitly.
        Long orgId = TenantContext.getCurrentOrgId();
        String sql = """
            SELECT a.code, a.name, a.type,
                   COALESCE(SUM(l.debit), 0)  AS total_debit,
                   COALESCE(SUM(l.credit), 0) AS total_credit
            FROM accounts a
            LEFT JOIN journal_entry_lines l ON l.account_id = a.id
            LEFT JOIN journal_entries je    ON je.id = l.journal_entry_id
                  AND je.status = 'POSTED'
                  AND je.entry_date <= :asOf
            """
                + jeOrgJoin(orgId)
                + """
            WHERE a.type IN ('ASSET', 'LIABILITY', 'EQUITY')
            """
                + acctOrgAnd(orgId)
                + """
            GROUP BY a.code, a.name, a.type
            ORDER BY a.code
            """;

        var query = em.createNativeQuery(sql).setParameter("asOf", asOfDate);
        if (orgId != null) query.setParameter("orgId", orgId);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = query.getResultList();

        List<BalanceSheetReport.AccountLine> assets = new ArrayList<>();
        List<BalanceSheetReport.AccountLine> liabilities = new ArrayList<>();
        List<BalanceSheetReport.AccountLine> equity = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (Object[] r : raw) {
            String code = (String) r[0];
            String name = (String) r[1];
            AccountType type = AccountType.valueOf((String) r[2]);
            BigDecimal td = toBig(r[3]);
            BigDecimal tc = toBig(r[4]);

            switch (type) {
                case ASSET -> {
                    BigDecimal balance = td.subtract(tc);
                    if (balance.signum() != 0) {
                        assets.add(new BalanceSheetReport.AccountLine(
                                code, name, MoneyUtils.forDisplay(balance)));
                        totalAssets = totalAssets.add(balance);
                    }
                }
                case LIABILITY -> {
                    BigDecimal balance = tc.subtract(td);
                    if (balance.signum() != 0) {
                        liabilities.add(new BalanceSheetReport.AccountLine(
                                code, name, MoneyUtils.forDisplay(balance)));
                        totalLiabilities = totalLiabilities.add(balance);
                    }
                }
                case EQUITY -> {
                    BigDecimal balance = tc.subtract(td);
                    if (balance.signum() != 0) {
                        equity.add(new BalanceSheetReport.AccountLine(
                                code, name, MoneyUtils.forDisplay(balance)));
                        totalEquity = totalEquity.add(balance);
                    }
                }
                default -> { /* ignore */ }
            }
        }

        // Net profit/loss for the period flows into retained earnings
        // For MVP we treat "the period" as everything up to asOfDate within the current FY
        LocalDate fyStart = currentFyStart(asOfDate);
        ProfitAndLossReport pnl = profitAndLoss(fyStart, asOfDate);
        BigDecimal retained = pnl.netProfit();
        totalEquity = totalEquity.add(retained);
        if (retained.signum() != 0) {
            equity.add(new BalanceSheetReport.AccountLine(
                    "RE-CURR", "Retained Earnings (Current Period)", retained));
        }

        BigDecimal totalAssetsDisplay = MoneyUtils.forDisplay(totalAssets);
        BigDecimal totalLiabDisplay   = MoneyUtils.forDisplay(totalLiabilities);
        BigDecimal totalEquityDisplay = MoneyUtils.forDisplay(totalEquity);
        BigDecimal totalLE = totalLiabDisplay.add(totalEquityDisplay);
        boolean balanced = totalAssetsDisplay.compareTo(totalLE) == 0;

        if (!balanced) {
            log.error("BALANCE SHEET DOES NOT BALANCE as of {}: assets={} liab+equity={}",
                    asOfDate, totalAssetsDisplay, totalLE);
        }

        return new BalanceSheetReport(
                asOfDate,
                assets,      totalAssetsDisplay,
                liabilities, totalLiabDisplay,
                equity,      totalEquityDisplay,
                retained,
                totalLE,
                balanced
        );
    }









    /**
     * Org predicate for the {@code journal_entries} table, emitted inside the LEFT JOIN ON
     * clause so outer-join semantics are preserved (accounts with no activity still appear).
     * Empty when there is no tenant context (e.g. a global admin viewing all organizations).
     */
    private static String jeOrgJoin(Long orgId) {
        return orgId != null ? "      AND je.organization_id = :orgId\n" : "";
    }

    /** Org predicate for the {@code accounts} table as a fresh WHERE (query has none of its own). */
    private static String acctOrgWhere(Long orgId) {
        return orgId != null ? "            WHERE a.organization_id = :orgId\n" : "";
    }

    /** Org predicate for the {@code accounts} table appended to an existing WHERE clause. */
    private static String acctOrgAnd(Long orgId) {
        return orgId != null ? "            AND a.organization_id = :orgId\n" : "";
    }

    private static BigDecimal toBig(Object o) {
        return switch (o) {
            case null -> BigDecimal.ZERO;
            case BigDecimal b -> b;
            case Number n -> BigDecimal.valueOf(n.doubleValue());
            default -> new BigDecimal(o.toString());
        };
    }

    private static LocalDate currentFyStart(LocalDate d) {
        if (d.getMonthValue() >= 4) {
            return LocalDate.of(d.getYear(),4 , 1);
        }
        return LocalDate.of(d.getYear() -1, 4, 1);
    }
}
