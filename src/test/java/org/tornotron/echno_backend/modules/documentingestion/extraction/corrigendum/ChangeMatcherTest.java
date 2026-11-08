package org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

    @Test
    void aMeasureIsReadWholeOrNotAtAll() {
        assertThat(ChangeMatcher.measure("920 cum")).isEqualByComparingTo("920");
        assertThat(ChangeMatcher.measure("1 200 cum")).isEqualByComparingTo("1200");
        assertThat(ChangeMatcher.measure("Rs. 640 per sqm")).isEqualByComparingTo("640");
        assertThat(ChangeMatcher.measure("9,800/cum")).isEqualByComparingTo("9800");
        assertThat(ChangeMatcher.measure(new BigDecimal("12.5"))).isEqualByComparingTo("12.5");
        assertThat(ChangeMatcher.measure("1/2")).isNull();
        assertThat(ChangeMatcher.measure("2 coats of paint")).isNull();
        assertThat(ChangeMatcher.measure("about 900")).isNull();
    }

    @Test
    void aValueWithItsUnitIsComparedAndPricedAsTheNumber() {
        Candidate rcc = boq("1.03", "850", "9800", CandidateStatus.APPLIED, UUID.randomUUID());
        ChangeMatcher.Match m = ChangeMatcher.match(change("1.03", "quantity", "850 cum", "920 cum"), null, List.of(rcc),
                false);

        assertThat(m.flags()).isEmpty();
        assertThat(m.additions()).containsEntry(ChangeMatcher.QUANTITY_VARIANCE, "70")
                .containsEntry(ChangeMatcher.MONEY_IMPACT, "686000");
    }

    @Test
    void aProjectFieldChangeNeedsNoFieldToFindTheEarlierValue() {
        Candidate due = new Candidate();
        due.setId(UUID.randomUUID());
        due.setKind(CandidateKind.PROJECT_FIELD);
        due.setNaturalKey("bid_due_date");
        due.setPayload(new LinkedHashMap<>(Map.of("value", "2026-12-01")));
        due.setStatus(CandidateStatus.PROPOSED);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put(ChangeMatcher.TARGET_KIND, "PROJECT_FIELD");
        change.put(ChangeMatcher.TARGET, "bid_due_date");
        change.put(ChangeMatcher.OLD_VALUE, "2026-11-20");
        change.put(ChangeMatcher.NEW_VALUE, "2026-12-15");

        ChangeMatcher.Match m = ChangeMatcher.match(change, null, List.of(due), false);

        assertThat(m.priorId()).isEqualTo(due.getId());
        assertThat(m.additions()).containsEntry(ChangeMatcher.PRIOR_VALUE, "2026-12-01");
        assertThat(codes(m)).containsExactly(ChangeMatcher.OLD_VALUE_DIFFERS);
    }

    @Test
    void aLiveRowAChangeWasAppliedToStillMatchesWithTheValueOnTheRecord() {
        Candidate live = boq("1.03", "850", "9800", CandidateStatus.SUPERSEDED, UUID.randomUUID());
        live.setAppliedTargetId(UUID.randomUUID().toString());
        Candidate replacedDraft = boq("1.03", "700", "9800", CandidateStatus.SUPERSEDED, UUID.randomUUID());

        ChangeMatcher.Match m = ChangeMatcher.match(change("1.03", "quantity", "920", "950"), null,
                List.of(replacedDraft, live), false, c -> {
                    Map<String, Object> values = new LinkedHashMap<>(ChangeMatcher.values(c));
                    values.put("quantity", "920");
                    return values;
                });

        assertThat(m.priorId()).isEqualTo(live.getId());
        assertThat(m.additions()).containsEntry(ChangeMatcher.PRIOR_VALUE, "920")
                .containsEntry(ChangeMatcher.QUANTITY_VARIANCE, "30");
        assertThat(m.flags()).isEmpty();
    }
}
