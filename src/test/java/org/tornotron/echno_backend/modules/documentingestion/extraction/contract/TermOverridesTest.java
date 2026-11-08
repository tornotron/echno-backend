package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;

class TermOverridesTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 9, 10, 0);

    static TermOverrides.Row row(String term, DocClass docClass, CandidateStatus status, String clause, int minute) {
        return new TermOverrides.Row(UUID.randomUUID(), term, docClass, status, clause, List.of(), null,
                T0.plusMinutes(minute));
    }

    @Test
    void theGeneralTermIsFlaggedAndTheSpecialOneSupersedesIt() {
        TermOverrides.Row gcc = row("ld_rate", DocClass.GCC, CandidateStatus.PROPOSED, "47.1", 0);
        TermOverrides.Row scc = row("ld_rate", DocClass.SCC, CandidateStatus.PROPOSED, "12", 1);
        TermOverrides.Row onlyGcc = row("dlp", DocClass.GCC, CandidateStatus.PROPOSED, "35", 0);

        Map<UUID, TermOverrides.Change> changes = TermOverrides.decide(List.of(gcc, scc, onlyGcc));

        assertThat(changes).containsOnlyKeys(gcc.id(), scc.id());
        CandidateFlag flag = changes.get(gcc.id()).addFlag();
        assertThat(flag.code()).isEqualTo(TermOverrides.OVERRIDDEN_BY_SCC);
        assertThat(flag.message()).contains("special conditions", "clause 12");
        assertThat(changes.get(scc.id()).supersedes()).isEqualTo(gcc.id());
    }

    @Test
    void contractDataCountsAsSpecialAndAnAppliedGeneralTermIsTheOneSuperseded() {
        TermOverrides.Row applied = row("retention", DocClass.GCC, CandidateStatus.APPLIED, "48", 0);
        TermOverrides.Row later = row("retention", DocClass.GCC, CandidateStatus.PROPOSED, "48", 5);
        TermOverrides.Row data = row("retention", DocClass.CONTRACT_DATA, CandidateStatus.PROPOSED, null, 9);

        Map<UUID, TermOverrides.Change> changes = TermOverrides.decide(List.of(applied, later, data));

        assertThat(changes.get(data.id()).supersedes()).isEqualTo(applied.id());
        assertThat(changes).as("a decided row keeps its flags").doesNotContainKey(applied.id());
        assertThat(changes.get(later.id()).addFlag().message()).contains("contract data").doesNotContain("clause");
    }

    @Test
    void runningTwiceChangesNothingMoreAndRejectedRowsAreIgnored() {
        UUID gccId = UUID.randomUUID();
        TermOverrides.Row gcc = new TermOverrides.Row(gccId, "ld_cap", DocClass.GCC, CandidateStatus.PROPOSED, "47.2",
                List.of(new CandidateFlag(TermOverrides.OVERRIDDEN_BY_SCC, "x")), null, T0);
        TermOverrides.Row scc = new TermOverrides.Row(UUID.randomUUID(), "ld_cap", DocClass.SCC, CandidateStatus.PROPOSED,
                "13", List.of(), gccId, T0);
        assertThat(TermOverrides.decide(List.of(gcc, scc))).isEmpty();

        TermOverrides.Row rejected = row("escalation", DocClass.SCC, CandidateStatus.REJECTED, "14", 0);
        TermOverrides.Row general = row("escalation", DocClass.GCC, CandidateStatus.PROPOSED, "50", 0);
        assertThat(TermOverrides.decide(List.of(rejected, general))).isEmpty();
    }
}
