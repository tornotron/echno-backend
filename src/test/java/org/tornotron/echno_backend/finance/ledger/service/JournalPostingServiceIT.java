package org.tornotron.echno_backend.finance.ledger.service;

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
import org.tornotron.echno_backend.common.exception.UnbalancedEntryException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.ledger.AccountType;
import org.tornotron.echno_backend.finance.ledger.JournalLimits;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.PostJournalRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;
import org.tornotron.echno_backend.finance.ledger.mapper.JournalEntryMapperImpl;
import org.tornotron.echno_backend.finance.ledger.repositories.JournalEntryRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for the double-entry ledger posting against a real CockroachDB.
 * These pin the invariants that keep the books correct: an entry only posts when its
 * debits equal its credits, every line is a debit XOR a credit, header accounts are
 * off-limits, and a reversal exactly mirrors the original.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JournalPostingService.class, JournalEntryMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class})
class JournalPostingServiceIT extends AbstractIntegrationTest {

    @Autowired
    private JournalPostingService service;

    @Autowired
    private JournalEntryRepository journalRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgId;
    private UUID cashId;      // leaf ASSET
    private UUID revenueId;   // leaf INCOME
    private UUID headerId;    // parent of another account → not postable
    private UUID inactiveId;  // active = false

    // The tenant seed (org + accounts) must be committed, not held in the test's
    // rolled-back transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW and
    // only sees committed rows (document_sequence has an FK to organization).
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization org = persistOrganization("Ledger Org");
            Account cash = persistAccount(org, "1000", "Cash", AccountType.ASSET, null, true);
            Account revenue = persistAccount(org, "4000", "Revenue", AccountType.INCOME, null, true);
            Account header = persistAccount(org, "1", "Assets", AccountType.ASSET, null, true);
            persistAccount(org, "1500", "Sub-asset", AccountType.ASSET, header, true); // makes header a parent
            Account inactive = persistAccount(org, "9999", "Closed", AccountType.EXPENSE, null, false);

            entityManager.flush();
            orgId = org.getId();
            cashId = cash.getId();
            revenueId = revenue.getId();
            headerId = header.getId();
            inactiveId = inactive.getId();
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
            exec("DELETE FROM journal_entry_lines WHERE journal_entry_id IN "
                    + "(SELECT id FROM journal_entries WHERE organization_id = :org)");
            exec("DELETE FROM journal_entries WHERE organization_id = :org");
            exec("DELETE FROM document_sequence WHERE organization_id = :org");
            exec("DELETE FROM accounts WHERE organization_id = :org");
            exec("DELETE FROM organization WHERE id = :org");
        });
    }

    // --- Happy path -------------------------------------------------------

    @Test
    void post_balancedEntry_persistsPostedEntryWithLines() {
        JournalEntry saved = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")),
                "MANUAL", null);

        assertThat(saved.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(saved.getEntryNumber()).startsWith("JE");
        assertThat(saved.getOrganization().getId()).isEqualTo(orgId);
        assertThat(saved.getLines()).hasSize(2);

        JournalEntry reloaded = journalRepo.findByIdWithLines(saved.getId()).orElseThrow();
        BigDecimal debits = reloaded.getLines().stream().map(l -> l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = reloaded.getLines().stream().map(l -> l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo("100");
        assertThat(credits).isEqualByComparingTo("100");
    }

    // --- The balance invariant -------------------------------------------

    @Test
    void post_unbalancedEntry_isRejected() {
        assertThatExceptionOfType(UnbalancedEntryException.class).isThrownBy(() ->
                service.postInternal(request(line(cashId, "100", "0"), line(revenueId, "0", "90")),
                        "MANUAL", null));
    }

    // --- Per-line and shape validation -----------------------------------

    @Test
    void post_singleLine_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(request(line(cashId, "100", "0")), "MANUAL", null));
    }

    @Test
    void post_futureDate_isRejected() {
        PostJournalRequest req = new PostJournalRequest(LocalDate.now().plusDays(1),
                "Future", null, List.of(line(cashId, "100", "0"), line(revenueId, "0", "100")));
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(req, "MANUAL", null));
    }

    @Test
    void post_lineWithBothDebitAndCredit_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(request(line(cashId, "50", "50"), line(revenueId, "0", "100")),
                        "MANUAL", null));
    }

    @Test
    void post_lineWithNeitherDebitNorCredit_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(request(line(cashId, "0", "0"), line(revenueId, "0", "100")),
                        "MANUAL", null));
    }

    // --- Account rules ----------------------------------------------------

    @Test
    void post_toHeaderAccount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(request(line(headerId, "100", "0"), line(revenueId, "0", "100")),
                        "MANUAL", null));
    }

    @Test
    void post_toInactiveAccount_isRejected() {
        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.postInternal(request(line(inactiveId, "100", "0"), line(revenueId, "0", "100")),
                        "MANUAL", null));
    }

    @Test
    void post_toUnknownAccount_isRejected() {
        assertThatExceptionOfType(AccountNotFoundException.class).isThrownBy(() ->
                service.postInternal(request(line(UUID.randomUUID(), "100", "0"), line(revenueId, "0", "100")),
                        "MANUAL", null));
    }

    // --- Reversal ---------------------------------------------------------

    @Test
    void reverse_postedEntry_mirrorsLinesAndMarksOriginalReversed() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);

        service.reverse(original.getId(), new ReverseJournalRequest("Booked in error"));

        JournalEntry reloaded = journalRepo.findByIdWithLines(original.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(reloaded.getReversedByEntryId()).isNotNull();

        JournalEntry reversal = journalRepo.findByIdWithLines(reloaded.getReversedByEntryId()).orElseThrow();
        // The cash line was a debit on the original; on the reversal it is a credit.
        BigDecimal cashCreditOnReversal = reversal.getLines().stream()
                .filter(l -> l.getAccount().getId().equals(cashId))
                .map(l -> l.getCredit()).findFirst().orElseThrow();
        assertThat(cashCreditOnReversal).isEqualByComparingTo("100");
    }

    @Test
    void reverse_reasonTooLongForTheDescriptionColumn_isRejectedBeforeAnythingIsWritten() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);
        String tooLong = "x".repeat(JournalLimits.REVERSAL_REASON_MAX_LENGTH + 1);

        // Without the bound this is a column overflow on flush, so it arrives as a database error
        // rather than as a refusal naming the field, and the caller sees a 500.
        assertThatExceptionOfType(InvalidJournalException.class)
                .isThrownBy(() -> service.reverse(original.getId(), new ReverseJournalRequest(tooLong)))
                .withMessageContaining("reversal reason");

        JournalEntry reloaded = journalRepo.findByIdWithLines(original.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(reloaded.getReversedByEntryId()).isNull();
    }

    @Test
    void reverse_reasonOfExactlyTheMaximumLength_isAccepted() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);
        String atTheLimit = "x".repeat(JournalLimits.REVERSAL_REASON_MAX_LENGTH);

        service.reverse(original.getId(), new ReverseJournalRequest(atTheLimit));

        JournalEntry reversal = journalRepo
                .findByIdWithLines(journalRepo.findByIdWithLines(original.getId()).orElseThrow()
                        .getReversedByEntryId())
                .orElseThrow();
        assertThat(reversal.getDescription())
                .endsWith(atTheLimit)
                .hasSizeLessThanOrEqualTo(JournalLimits.DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void reverse_blankReason_isRejectedRatherThanLeavingADanglingSeparator() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);

        assertThatExceptionOfType(InvalidJournalException.class)
                .isThrownBy(() -> service.reverse(original.getId(), new ReverseJournalRequest("   ")));
    }

    @Test
    void reverse_reasonOfOnlyUnicodeWhitespace_isRejected() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);

        // An em space survives trim(), which stops at U+0020, so a reason made only of one would
        // have posted a reversal whose description ends in a dangling separator.
        assertThatExceptionOfType(InvalidJournalException.class)
                .isThrownBy(() -> service.reverse(original.getId(), new ReverseJournalRequest("\u2003")));
    }

    @Test
    void reverse_alreadyReversedEntry_isRejected() {
        JournalEntry original = service.postInternal(
                request(line(cashId, "100", "0"), line(revenueId, "0", "100")), "MANUAL", null);
        service.reverse(original.getId(), new ReverseJournalRequest("first"));

        assertThatExceptionOfType(InvalidJournalException.class).isThrownBy(() ->
                service.reverse(original.getId(), new ReverseJournalRequest("second")));
    }

    // --- Helpers ----------------------------------------------------------

    private PostJournalRequest request(PostJournalRequest.LineRequest... lines) {
        return new PostJournalRequest(LocalDate.now(), "Test entry", null, List.of(lines));
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

    private Account persistAccount(Organization org, String code, String name, AccountType type,
                                   Account parent, boolean active) {
        Account account = new Account();
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        account.setParent(parent);
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
}
