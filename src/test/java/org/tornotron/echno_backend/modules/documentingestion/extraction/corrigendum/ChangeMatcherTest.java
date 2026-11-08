package org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;

class ChangeMatcherTest {

    static Candidate boq(String code, String quantity, String rate, CandidateStatus status, UUID document) {
        Candidate c = new Candidate();
        c.setId(UUID.randomUUID());
        c.setKind(CandidateKind.BOQ_ITEM);
        c.setDocumentId(document);
        c.setNaturalKey("BOQ:" + code);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("itemCode", code);
        p.put("quantity", quantity);
        p.put("rate", rate);
        c.setPayload(p);
        c.setStatus(status);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    static Map<String, Object> change(String target, String field, String oldValue, String newValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(ChangeMatcher.TARGET_KIND, "BOQ_ITEM");
        m.put(ChangeMatcher.TARGET, target);
        m.put(ChangeMatcher.FIELD, field);
        if (oldValue != null) {
            m.put(ChangeMatcher.OLD_VALUE, oldValue);
        }
        m.put(ChangeMatcher.NEW_VALUE, newValue);
        return m;
    }

    static List<String> codes(ChangeMatcher.Match m) {
        return m.flags().stream().map(CandidateFlag::code).toList();
    }

    @Test
    void theStubsExampleGivesPlus70And686000() {
        Candidate rcc = boq("1.03", "850", "9800", CandidateStatus.APPLIED, UUID.randomUUID());
        ChangeMatcher.Match m = ChangeMatcher.match(change("1.03", "quantity", "850", "920"), UUID.randomUUID(),
                List.of(boq("1.02", "10", "5", CandidateStatus.APPLIED, null), rcc), false);

        assertThat(m.priorId()).isEqualTo(rcc.getId());
        assertThat(m.additions()).containsEntry(ChangeMatcher.PRIOR_VALUE, "850")
                .containsEntry(ChangeMatcher.QUANTITY_VARIANCE, "70").containsEntry(ChangeMatcher.MONEY_IMPACT, "686000");
        assertThat(m.flags()).isEmpty();
    }

    @Test
    void aRateChangeIsPricedOnTheQuantityAndAWrongOldValueIsFlagged() {
        Candidate rcc = boq("1.03", "850", "9800", CandidateStatus.ACCEPTED, UUID.randomUUID());
        ChangeMatcher.Match m = ChangeMatcher.match(change("1.03", "rate", "9,500", "10,000"), null, List.of(rcc), true);

        assertThat(m.additions()).containsEntry(ChangeMatcher.MONEY_IMPACT, "170000");
        assertThat(codes(m)).containsExactlyInAnyOrder(ChangeMatcher.OLD_VALUE_DIFFERS, ChangeMatcher.TENDER_IMPACT);
    }

    @Test
    void aStatementMatchingNoItemIsFlaggedAndKept() {
        ChangeMatcher.Match m = ChangeMatcher.match(change("7.77", "quantity", null, "5"), null,
                List.of(boq("1.03", "850", "9800", CandidateStatus.APPLIED, null)), false);

        assertThat(m.priorId()).isNull();
        assertThat(codes(m)).containsExactly(ChangeMatcher.UNMATCHED);
    }

    @Test
    void anAppliedRowWinsOverAProposedOneAndTheChangesOwnFileIsNeverItsPrior() {
        UUID revisedFile = UUID.randomUUID();
        Candidate applied = boq("1.03", "850", "9800", CandidateStatus.APPLIED, UUID.randomUUID());
        Candidate proposedLater = boq("1.03", "860", "9800", CandidateStatus.PROPOSED, UUID.randomUUID());
        Candidate ownRow = boq("1.03", "920", "9800", CandidateStatus.APPLIED, revisedFile);
        Candidate rejected = boq("1.03", "1", "1", CandidateStatus.REJECTED, UUID.randomUUID());

        ChangeMatcher.Match m = ChangeMatcher.match(change("1.03", "quantity", null, "920"), revisedFile,
                List.of(rejected, ownRow, proposedLater, applied), false);

        assertThat(m.priorId()).isEqualTo(applied.getId());
    }
}
