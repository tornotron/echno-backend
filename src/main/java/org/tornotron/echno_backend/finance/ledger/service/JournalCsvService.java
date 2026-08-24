package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.finance.common.csv.CsvUtils;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntryLine;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Exports the tenant's journal entries to CSV for external systems, one row per journal line.
 *
 * <p>The columns are {@code entryDate,entryNumber,accountCode,accountName,debit,credit,narration,
 * referenceType,referenceId}. The optional date bounds narrow the export by entry date; the rows
 * are tenant-scoped through the Hibernate organization filter.
 */
@Service
@RequiredArgsConstructor
public class JournalCsvService {

    private static final List<String> HEADER = List.of(
            "entryDate", "entryNumber", "accountCode", "accountName",
            "debit", "credit", "narration", "referenceType", "referenceId");

    private final JournalEntryRepository journalRepo;

    /**
     * Exports journal entries within the optional inclusive date range as CSV, one row per line.
     *
     * @param from Earliest entry date to include, or null for no lower bound.
     * @param to Latest entry date to include, or null for no upper bound.
     * @return The CSV text.
     */
    @Transactional(readOnly = true)
    public String exportCsv(LocalDate from, LocalDate to) {
        List<JournalEntry> entries = journalRepo.findForExport(from, to);
        StringBuilder sb = new StringBuilder();
        sb.append(CsvUtils.toLine(HEADER)).append('\n');
        for (JournalEntry je : entries) {
            for (JournalEntryLine line : je.getLines()) {
                sb.append(CsvUtils.toLine(List.of(
                        je.getEntryDate() == null ? "" : je.getEntryDate().toString(),
                        nz(je.getEntryNumber()),
                        line.getAccount() == null ? "" : nz(line.getAccount().getCode()),
                        line.getAccount() == null ? "" : nz(line.getAccount().getName()),
                        plain(line.getDebit()),
                        plain(line.getCredit()),
                        nz(line.getNarration()),
                        nz(je.getSourceType()),
                        je.getSourceId() == null ? "" : je.getSourceId().toString()))).append('\n');
            }
        }
        return sb.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
