package org.tornotron.echno_backend.finance.ledger.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.UnbalancedEntryException;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntryLine;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryDto;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalPostingService {

    private final JournalEntryRepository journalRepo;
    private final AccountRepository accountRepo;
    private final EntryNumberGenerator numberGen;
    private final JournalEntryMapper mapper;

    // ============================================================
    // Public API
    // ============================================================

    @Transactional
    public JournalEntryDto post(PostJournalRequest req) {
        return mapper.toDto(postInternal(req, "MANUAL", null));
    }

    @Transactional
    public JournalEntryDto postSystem(PostJournalRequest req, String sourceType, UUID sourceId) {
        return mapper.toDto(postInternal(req, sourceType, sourceId));
    }

    @Transactional
    public JournalEntry postInternal(PostJournalRequest req, String sourceType, UUID sourceId) {
        validateRequest(req);

        JournalEntry entry = new JournalEntry();
        entry.setEntryNumber(numberGen.next("JE"));
        entry.setEntryDate(req.entryDate());
        entry.setDescription(req.description());
        entry.setReference(req.reference());
        entry.setStatus(JournalStatus.POSTED);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);

        // Pre-fetch all accounts in one query to avoid N+1
        List<UUID> accountIds = req.lines().stream()
                .map(PostJournalRequest.LineRequest::accountId).distinct().toList();
        Map<UUID, Account> accountMap = new HashMap<>();
        for (Account a : accountRepo.findAllById(accountIds)) {
            accountMap.put(a.getId(), a);
        }
        // Header (non-leaf) accounts hold only derived totals; posting to them
        // would double-count against their children in roll-up reports.
        Set<UUID> headerIds = new HashSet<>(accountRepo.findHeaderIdsAmong(accountIds));
        for (UUID id : accountIds) {
            if (!accountMap.containsKey(id)) {
                throw new AccountNotFoundException(id);
            }
            Account account = accountMap.get(id);
            if (!account.isActive()) {
                throw new InvalidJournalException(
                        "Account is inactive: " + account.getCode());
            }
            if (headerIds.contains(id)) {
                throw new InvalidJournalException(
                        "Cannot post to header account " + account.getCode()
                        + " (" + account.getName() + "); post to a leaf account instead");
            }
        }

        int order = 0;
        for (var lineReq : req.lines()) {
            JournalEntryLine line = new JournalEntryLine();
            line.setAccount(accountMap.get(lineReq.accountId()));
            line.setDebit(MoneyUtils.normalize(lineReq.debit()));
            line.setCredit(MoneyUtils.normalize(lineReq.credit()));
            line.setNarration(lineReq.narration());
            line.setLineOrder(order++);
            entry.addLine(line);
        }

        JournalEntry saved = journalRepo.save(entry);
        log.info("Posted journal entry {} on {} (source={}/{})",
                saved.getEntryNumber(), saved.getEntryDate(), sourceType, sourceId);
        return saved;
    }

    @Transactional
    public JournalEntryDto reverse(UUID entryId, ReverseJournalRequest req) {
        JournalEntry original = journalRepo.findByIdWithLines(entryId)
                .orElseThrow(() -> new InvalidJournalException("Journal entry not found: " + entryId));

        if (original.getStatus() != JournalStatus.POSTED) {
            throw new InvalidJournalException(
                    "Only POSTED entries can be reversed; current status: " + original.getStatus());
        }
        if (original.getReversedByEntryId() != null) {
            throw new InvalidJournalException("Entry already reversed");
        }

        // Build reversal lines: swap debit and credit on each line
        List<PostJournalRequest.LineRequest> reversedLines = original.getLines().stream()
                .map(l -> new PostJournalRequest.LineRequest(
                        l.getAccount().getId(),
                        l.getCredit(),   // swap
                        l.getDebit(),    // swap
                        "Reversal: " + (l.getNarration() == null ? "" : l.getNarration())))
                .toList();

        PostJournalRequest reversalReq = new PostJournalRequest(
                LocalDate.now(),
                "Reversal of " + original.getEntryNumber() + " - " + req.reason(),
                original.getEntryNumber(),
                reversedLines
        );

        JournalEntry reversal = postInternal(reversalReq, "REVERSAL", original.getId());
        reversal.setReversesEntryId(original.getId());

        original.setStatus(JournalStatus.REVERSED);
        original.setReversedByEntryId(reversal.getId());

        log.info("Reversed entry {} via {}", original.getEntryNumber(), reversal.getEntryNumber());
        return mapper.toDto(reversal);
    }

    @Transactional(readOnly = true)
    public JournalEntryDto findById(UUID id) {
        JournalEntry entry = journalRepo.findByIdWithLines(id)
                .orElseThrow(() -> new InvalidJournalException("Journal entry not found: " + id));
        return mapper.toDto(entry);
    }

    // ============================================================
    // Validation — the invariants that must hold
    // ============================================================

    private void validateRequest(PostJournalRequest req) {
        if (req.lines() == null || req.lines().size() < 2) {
            throw new InvalidJournalException("At least 2 lines required");
        }
        if (req.entryDate().isAfter(LocalDate.now())) {
            throw new InvalidJournalException("Entry date cannot be in the future");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (int i = 0; i < req.lines().size(); i++) {
            var line = req.lines().get(i);
            BigDecimal dr = MoneyUtils.normalize(line.debit());
            BigDecimal cr = MoneyUtils.normalize(line.credit());

            boolean hasDebit = MoneyUtils.isPositive(dr);
            boolean hasCredit = MoneyUtils.isPositive(cr);

            if (MoneyUtils.isNegative(dr) || MoneyUtils.isNegative(cr)) {
                throw new InvalidJournalException(
                        "Line " + (i + 1) + ": amounts must be non-negative");
            }
            if (hasDebit && hasCredit) {
                throw new InvalidJournalException(
                        "Line " + (i + 1) + ": cannot have both debit and credit");
            }
            if (!hasDebit && !hasCredit) {
                throw new InvalidJournalException(
                        "Line " + (i + 1) + ": must have either debit or credit");
            }

            totalDebit  = totalDebit.add(dr);
            totalCredit = totalCredit.add(cr);
        }

        totalDebit  = MoneyUtils.normalize(totalDebit);
        totalCredit = MoneyUtils.normalize(totalCredit);

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new UnbalancedEntryException(totalDebit, totalCredit);
        }
        if (MoneyUtils.isZero(totalDebit)) {
            throw new InvalidJournalException("Journal entry total cannot be zero");
        }
    }
}