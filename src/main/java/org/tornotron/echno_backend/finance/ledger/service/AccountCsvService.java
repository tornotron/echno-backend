package org.tornotron.echno_backend.finance.ledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.common.csv.CsvUtils;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.dtos.CoaImportSummary;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports the tenant's chart of accounts to CSV and imports one back, for interchange with external
 * bookkeeping systems.
 *
 * <p>The interchange columns are {@code code,name,type,parentCode,active}. Import upserts by code:
 * an account already present is updated, a missing one is created, and an account absent from the
 * file is left untouched (import never deletes). Rows are applied parent-before-child regardless of
 * their order in the file, a child's type must match its parent's, and any row that cannot be
 * applied is reported in the summary rather than aborting the whole import.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCsvService {

    private static final List<String> HEADER = List.of("code", "name", "type", "parentCode", "active");

    private final AccountRepository repo;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Exports the whole chart of accounts (active and inactive) as CSV, ordered by code.
     */
    @Transactional(readOnly = true)
    public String exportCsv() {
        List<Account> all = repo.findAll(Sort.by("code"));
        StringBuilder sb = new StringBuilder();
        sb.append(CsvUtils.toLine(HEADER)).append('\n');
        for (Account a : all) {
            String parentCode = a.getParent() == null ? "" : a.getParent().getCode();
            sb.append(CsvUtils.toLine(List.of(
                    nz(a.getCode()),
                    nz(a.getName()),
                    a.getType() == null ? "" : a.getType().name(),
                    parentCode,
                    Boolean.toString(a.isActive())))).append('\n');
        }
        return sb.toString();
    }

    /**
     * Imports a chart-of-accounts CSV, upserting accounts by code within the current tenant.
     *
     * @param content The CSV text, with the header row {@code code,name,type,parentCode,active}.
     * @return A summary of how many accounts were created and updated, and any per-row errors.
     * @throws InvalidRequestException if the file is empty or its header does not match.
     */
    @Transactional
    public CoaImportSummary importCsv(String content) {
        List<List<String>> rows = CsvUtils.parse(content);
        if (rows.isEmpty()) {
            throw new InvalidRequestException("The CSV file is empty");
        }

        List<String> header = normalize(rows.get(0));
        if (!header.equals(HEADER)) {
            throw new InvalidRequestException(
                    "Unexpected CSV header; expected " + HEADER + " but found " + header);
        }

        // Parse the data rows into records keyed by code, preserving file order.
        Map<String, Record> desired = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int lineNo = r + 1;
            if (row.size() < HEADER.size()) {
                errors.add("Line " + lineNo + ": expected " + HEADER.size() + " columns but found " + row.size());
                continue;
            }
            String code = trim(row.get(0));
            if (code.isEmpty()) {
                errors.add("Line " + lineNo + ": code is required");
                continue;
            }
            if (desired.containsKey(code)) {
                errors.add("Line " + lineNo + ": duplicate code '" + code + "' in the file");
                continue;
            }
            desired.put(code, new Record(code, trim(row.get(1)), trim(row.get(2)),
                    trim(row.get(3)), trim(row.get(4)), lineNo));
        }

        // Existing accounts by code, so we can update in place and resolve parent references.
        Map<String, Account> byCode = new HashMap<>();
        for (Account a : repo.findAll()) {
            byCode.put(a.getCode(), a);
        }

        Counters counters = new Counters();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Map<String, String> failed = new HashMap<>();
        for (String code : desired.keySet()) {
            apply(code, desired, byCode, done, visiting, failed, errors, counters);
        }

        log.info("Imported chart of accounts: {} created, {} updated, {} errors",
                counters.created, counters.updated, errors.size());
        return new CoaImportSummary(counters.created, counters.updated, errors);
    }

    /**
     * Resolves one code, first ensuring its parent (if any) is applied. Returns the resolved
     * account, or null when the row could not be applied (an error has been recorded).
     */
    private Account apply(String code, Map<String, Record> desired, Map<String, Account> byCode,
                          Set<String> done, Set<String> visiting, Map<String, String> failed,
                          List<String> errors, Counters counters) {
        if (done.contains(code)) {
            return byCode.get(code);
        }
        if (failed.containsKey(code)) {
            return null;
        }
        Record rec = desired.get(code);
        if (rec == null) {
            // Not in the file: only valid as a reference to an already-existing account.
            return byCode.get(code);
        }
        if (visiting.contains(code)) {
            fail(code, "Line " + rec.lineNo + ": parent cycle involving code '" + code + "'", failed, errors);
            return null;
        }
        visiting.add(code);

        Account parent = null;
        if (!rec.parentCode.isEmpty()) {
            if (rec.parentCode.equals(code)) {
                visiting.remove(code);
                fail(code, "Line " + rec.lineNo + ": account '" + code + "' cannot be its own parent", failed, errors);
                return null;
            }
            parent = apply(rec.parentCode, desired, byCode, done, visiting, failed, errors, counters);
            if (parent == null && !byCode.containsKey(rec.parentCode)) {
                visiting.remove(code);
                fail(code, "Line " + rec.lineNo + ": parent code '" + rec.parentCode
                        + "' was not found in the file or the existing chart", failed, errors);
                return null;
            }
            if (parent == null) {
                parent = byCode.get(rec.parentCode);
            }
        }
        visiting.remove(code);

        // Resolve the type: explicit where given, otherwise inherited from the parent.
        AccountType type;
        if (!rec.type.isEmpty()) {
            try {
                type = AccountType.valueOf(rec.type);
            } catch (IllegalArgumentException ex) {
                fail(code, "Line " + rec.lineNo + ": unknown account type '" + rec.type + "'", failed, errors);
                return null;
            }
            if (parent != null && parent.getType() != type) {
                fail(code, "Line " + rec.lineNo + ": type '" + type + "' does not match parent '"
                        + parent.getCode() + "' type '" + parent.getType() + "'", failed, errors);
                return null;
            }
        } else if (parent != null) {
            type = parent.getType();
        } else {
            fail(code, "Line " + rec.lineNo + ": type is required for a root account", failed, errors);
            return null;
        }

        if (rec.name.isEmpty()) {
            fail(code, "Line " + rec.lineNo + ": name is required", failed, errors);
            return null;
        }
        boolean active = rec.active.isEmpty() || Boolean.parseBoolean(rec.active);

        Account account = byCode.get(code);
        boolean isNew = account == null;
        if (isNew) {
            account = new Account();
            account.setCode(code);
            account.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        }
        account.setName(rec.name);
        account.setType(type);
        account.setActive(active);
        account.setParent(parent);
        account = repo.save(account);

        byCode.put(code, account);
        done.add(code);
        if (isNew) {
            counters.created++;
        } else {
            counters.updated++;
        }
        return account;
    }

    private void fail(String code, String message, Map<String, String> failed, List<String> errors) {
        failed.put(code, message);
        errors.add(message);
    }

    private List<String> normalize(List<String> header) {
        List<String> out = new ArrayList<>(header.size());
        for (String h : header) {
            out.add(h == null ? "" : h.trim());
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A parsed CSV data row. */
    private record Record(String code, String name, String type, String parentCode,
                          String active, int lineNo) {}

    /** Mutable created/updated tallies threaded through the recursion. */
    private static final class Counters {
        int created;
        int updated;
    }
}
