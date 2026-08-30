package org.tornotron.echno_backend.finance.ledger.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.AccountNotFoundException;
import org.tornotron.echno_backend.common.exception.InvalidJournalException;
import org.tornotron.echno_backend.common.exception.UnbalancedEntryException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.ledger.JournalLimits;
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
import java.util.stream.Collectors;

/**
 * Double-entry posting and reversal core for the general ledger.
 *
 * <p>Every entry that reaches the ledger goes through here. Before an entry is saved it must
 * satisfy the accounting invariants: at least two lines, total debits equal to total credits,
 * a non-zero total, no future entry date, and each line carrying either a debit or a credit but
 * not both. Lines may only target active leaf accounts; posting to a header (non-leaf) account is
 * rejected because that would double-count against its children in roll-up reports.
 *
 * <p>Posted entries are immutable. A correction is made by posting a reversal, which mirrors the
 * original lines with debit and credit swapped and links the two entries together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JournalPostingService {

    private final JournalEntryRepository journalRepo;
    private final AccountRepository accountRepo;
    private final EntryNumberGenerator numberGen;
    private final JournalEntryMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Posts a manually entered journal entry.
     *
     * @param req The entry header and its debit/credit lines.
     * @return The posted entry as a DTO.
     * @throws org.tornotron.echno_backend.common.exception.InvalidJournalException if the entry breaks a posting invariant.
     * @throws UnbalancedEntryException if total debits do not equal total credits.
     * @throws AccountNotFoundException if a line references an unknown account.
     */
    @Transactional
    public JournalEntryDto post(PostJournalRequest req) {
        return mapper.toDto(postInternal(req, "MANUAL", null));
    }

    /**
     * Posts a system-generated entry, tagging it with the source document that produced it
     * (for example an invoice or a payment) so the ledger row can be traced back to its origin.
     *
     * @param req The entry header and its debit/credit lines.
     * @param sourceType The kind of source document (INVOICE, PAYMENT, REVERSAL, ...).
     * @param sourceId The id of the source document, may be null.
     * @return The posted entry as a DTO.
     * @throws org.tornotron.echno_backend.common.exception.InvalidJournalException if the entry breaks a posting invariant.
     * @throws UnbalancedEntryException if total debits do not equal total credits.
     * @throws AccountNotFoundException if a line references an unknown account.
     */
    @Transactional
    public JournalEntryDto postSystem(PostJournalRequest req, String sourceType, UUID sourceId) {
        return mapper.toDto(postInternal(req, sourceType, sourceId));
    }

    /**
     * Validates, builds, and persists a journal entry, returning the managed entity.
     *
     * <p>Callers that need the entity (to read its generated id, or to link it from a source
     * document) use this directly; the invoice and payment services post their ledger entries
     * this way. Accounts are pre-fetched in one query to avoid an N+1, then each line is checked
     * against the active and leaf-account rules before the entry is saved with status POSTED.
     *
     * @param req The entry header and its debit/credit lines.
     * @param sourceType The kind of source document (MANUAL, INVOICE, PAYMENT, REVERSAL, ...).
     * @param sourceId The id of the source document, may be null.
     * @return The persisted journal entry.
     * @throws org.tornotron.echno_backend.common.exception.InvalidJournalException if the entry breaks a posting invariant, or a target account is inactive or a header account.
     * @throws UnbalancedEntryException if total debits do not equal total credits.
     * @throws AccountNotFoundException if a line references an unknown account.
     */
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
        entry.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

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
                        "Account '" + account.getCode() + "' is inactive and cannot be posted to");
            }
            if (headerIds.contains(id)) {
                throw new InvalidJournalException(
                        "Account '" + account.getCode() + "' (" + account.getName()
                        + ") is a header account and cannot be posted to directly; post to one of its leaf accounts instead");
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

    /**
     * Reverses a posted entry by posting a mirror entry with each line's debit and credit swapped.
     *
     * <p>The original is left untouched except that its status becomes REVERSED and it records the
     * id of the reversal; the reversal records the id of the entry it reverses. Only a POSTED entry
     * that has not already been reversed can be reversed.
     *
     * <p>The reason is bounded here and not only at whatever endpoint supplied it. It is the
     * ledger that turns a reason into a description, by putting a prefix in front of it, so the
     * ledger is the only place that knows how much room is actually left; a caller that constructs
     * the request itself, as the two invoice cancel paths do, runs no bean validation at all, and
     * one that does bind it still cannot see the prefix. Refusing here means an over-long reason
     * is an argument the ledger rejects before it writes anything, rather than a column overflow
     * discovered on flush and surfaced as a server error.
     *
     * @param entryId The id of the entry to reverse.
     * @param req Carries the reason recorded on the reversal's description.
     * @return The newly posted reversal entry as a DTO.
     * @throws org.tornotron.echno_backend.common.exception.InvalidJournalException if the entry does not exist, is not POSTED, was already reversed, or the reason is blank or longer than {@link JournalLimits#REVERSAL_REASON_MAX_LENGTH}.
     */
    @Transactional
    public JournalEntryDto reverse(UUID entryId, ReverseJournalRequest req) {
        String reason = requireStorableReason(req);

        JournalEntry original = journalRepo.findByIdWithLines(entryId)
                .orElseThrow(() -> new InvalidJournalException("Journal entry with ID " + entryId + " was not found"));

        if (original.getStatus() != JournalStatus.POSTED) {
            throw new InvalidJournalException(
                    "Journal entry " + original.getEntryNumber() + " cannot be reversed because its status is "
                    + original.getStatus() + "; only POSTED entries can be reversed");
        }
        if (original.getReversedByEntryId() != null) {
            throw new InvalidJournalException(
                    "Journal entry " + original.getEntryNumber() + " has already been reversed");
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
                JournalLimits.reversalDescription(original.getEntryNumber(), reason),
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

    /**
     * Checks that a reversal reason will fit the description the ledger builds around it.
     *
     * @param req The reversal request, which a service caller may have constructed by hand.
     * @return The reason, trimmed of surrounding whitespace.
     * @throws InvalidJournalException if the request or its reason is missing, blank, or longer
     *         than {@link JournalLimits#REVERSAL_REASON_MAX_LENGTH}.
     */
    private String requireStorableReason(ReverseJournalRequest req) {
        String reason = req == null || req.reason() == null ? null : req.reason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new InvalidJournalException(
                    "A reversal reason is required; it is recorded on the reversing entry's description");
        }
        if (reason.length() > JournalLimits.REVERSAL_REASON_MAX_LENGTH) {
            throw new InvalidJournalException(
                    "The reversal reason is " + reason.length() + " characters; at most "
                    + JournalLimits.REVERSAL_REASON_MAX_LENGTH + " fit alongside the "
                    + "\"" + JournalLimits.REVERSAL_DESCRIPTION_PREFIX + "<entry number>"
                    + JournalLimits.REVERSAL_DESCRIPTION_SEPARATOR + "\" prefix in the "
                    + JournalLimits.DESCRIPTION_MAX_LENGTH + "-character description");
        }
        return reason;
    }

    /**
     * Retrieves a single journal entry with its lines.
     *
     * @param id The id of the entry.
     * @return The entry as a DTO.
     * @throws org.tornotron.echno_backend.common.exception.InvalidJournalException if no entry with the given id exists.
     */
    @Transactional(readOnly = true)
    public JournalEntryDto findById(UUID id) {
        JournalEntry entry = journalRepo.findByIdWithLines(id)
                .orElseThrow(() -> new InvalidJournalException("Journal entry with ID " + id + " was not found"));
        return mapper.toDto(entry);
    }

    /**
     * Lists journal entries one page at a time, ordered by entry date then creation time.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of entries per page.
     * @return A page of entry DTOs.
     */
    @Transactional(readOnly = true)
    public Page<JournalEntryDto> findAll(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize,
                Sort.by(Sort.Direction.ASC, "entryDate")
                        .and(Sort.by(Sort.Direction.ASC, "createdAt")));
        return journalRepo.findAll(pageable)
                .map(mapper::toDto);
    }

    // ============================================================
    // Validation — the invariants that must hold
    // ============================================================

    private void validateRequest(PostJournalRequest req) {
        if (req.lines() == null || req.lines().size() < 2) {
            throw new InvalidJournalException("Journal entry must contain at least 2 lines");
        }
        if (req.entryDate().isAfter(LocalDate.now())) {
            throw new InvalidJournalException(
                    "Entry date " + req.entryDate() + " cannot be in the future");
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
                        "Line " + (i + 1) + ": debit and credit amounts must be non-negative");
            }
            if (hasDebit && hasCredit) {
                throw new InvalidJournalException(
                        "Line " + (i + 1) + ": cannot have both a debit and a credit amount");
            }
            if (!hasDebit && !hasCredit) {
                throw new InvalidJournalException(
                        "Line " + (i + 1) + ": must have either a debit or a credit amount");
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
            throw new InvalidJournalException("Journal entry total amount cannot be zero");
        }
    }
}