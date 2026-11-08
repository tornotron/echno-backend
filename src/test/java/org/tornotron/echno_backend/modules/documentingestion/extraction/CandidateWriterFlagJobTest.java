package org.tornotron.echno_backend.modules.documentingestion.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.extraction.milestone.MilestoneTables;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;

class CandidateWriterFlagJobTest {

    final CandidateRepository candidates = mock(CandidateRepository.class);
    final CandidateWriter writer = new CandidateWriter(candidates, mock(SourceRefRepository.class),
            mock(TenantEntityHelper.class));
    final UUID job = UUID.randomUUID();

    static Candidate row(String percent, CandidateStatus status, CandidateFlag... flags) {
        Candidate c = new Candidate();
        c.setPayload(Map.of(MilestoneTables.PERCENT, percent));
        c.setStatus(status);
        c.setFlags(new ArrayList<>(List.of(flags)));
        return c;
    }

    void check() {
        writer.flagJob(job, CandidateKind.MILESTONE, MilestoneTables.PERCENT_TOTAL, payloads -> MilestoneTables
                .percentTotal(payloads.stream().map(p -> p.get(MilestoneTables.PERCENT)).toList()).orElse(null));
    }

    @Test
    void anAcceptedRowCountsInTheTotalAndKeepsItsFlags() {
        Candidate accepted = row("40%", CandidateStatus.ACCEPTED);
        Candidate proposed = row("60%", CandidateStatus.PROPOSED);
        when(candidates.findOfJob(job, CandidateKind.MILESTONE)).thenReturn(List.of(accepted, proposed));

        check();

        assertThat(proposed.getFlags()).isEmpty();
        assertThat(accepted.getFlags()).isEmpty();
    }

    @Test
    void aRetryReplacesAStaleTotalAndRemovesItOnceTheTotalIsRight() {
        CandidateFlag stale = new CandidateFlag(MilestoneTables.PERCENT_TOTAL, "add up to 60, not 100.");
        CandidateFlag other = new CandidateFlag("RELATIVE_TIME", "counted from the start");
        Candidate a = row("60%", CandidateStatus.PROPOSED, stale, other);
        Candidate b = row("30%", CandidateStatus.PROPOSED, stale);
        when(candidates.findOfJob(job, CandidateKind.MILESTONE)).thenReturn(List.of(a, b));

        check();
        assertThat(a.getFlags()).containsExactly(other, new CandidateFlag(MilestoneTables.PERCENT_TOTAL,
                "The payment percentages of this schedule add up to 90, not 100."));

        Candidate rest = row("10%", CandidateStatus.PROPOSED);
        Candidate rejected = row("25%", CandidateStatus.REJECTED);
        when(candidates.findOfJob(job, CandidateKind.MILESTONE)).thenReturn(List.of(a, b, rest, rejected));
        check();
        assertThat(a.getFlags()).containsExactly(other);
        assertThat(b.getFlags()).isEmpty();
        assertThat(rejected.getFlags()).isEmpty();
    }
}
