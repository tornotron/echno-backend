package org.tornotron.echno_backend.finance.report.web;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.finance.report.dtos.BalanceSheetReport;
import org.tornotron.echno_backend.finance.report.dtos.ProfitAndLossReport;
import org.tornotron.echno_backend.finance.report.dtos.TrialBalanceReport;
import org.tornotron.echno_backend.finance.report.service.ReportService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/finance/reports/web")
@RequiredArgsConstructor
public class ReportControllerWeb {

    private final ReportService service;

    @GetMapping("/trial-balance")
    public TrialBalanceReport trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)LocalDate asOfDate
            ) {
        return service.trailBalanceReport(asOfDate);
    }

    @GetMapping("/profit-and-loss")
    public ProfitAndLossReport profitAndLoss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return service.profitAndLoss(fromDate, toDate);
    }

    @GetMapping("/balance-sheet")
    public BalanceSheetReport balanceSheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return service.balanceSheet(asOfDate);
    }
}
