package org.tornotron.echno_backend.modules.assistant.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.pipeline.EvidenceAssembler.NoteKind;
import org.tornotron.echno_backend.modules.assistant.pipeline.ParallelRetriever.ProviderOutcome;

/** What survives assembly, and that the providers that gave nothing are remembered, with why. */
class EvidenceAssemblerTest {

    private final AssistantProperties props = new AssistantProperties();
    private final EvidenceAssembler assembler = new EvidenceAssembler(props);

    @Test
    void aUnitReturnedTwiceIsKeptOnceAtItsBestRelevance() {
        var result = assembler.assemble(List.of(outcome("task",
                unit("task:1", "task", 0.3), unit("task:1", "task", 0.9))));

        assertThat(result.units()).hasSize(1);
        assertThat(result.units().get(0).relevance()).isEqualTo(0.9);
    }

    @Test
    void theSameIdFromTwoProvidersIsStillOneUnit() {
        var result = assembler.assemble(List.of(
                outcome("a", unit("shared:1", "a", 0.5)),
                outcome("b", unit("shared:1", "b", 0.7))));

        assertThat(result.units()).hasSize(1);
    }

    @Test
    void eachProviderIsCappedAtItsShareKeepingItsMostRelevant() {
        props.getRetrieval().setUnitsPerProvider(3);
        List<EvidenceUnit> units = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            units.add(unit("task:" + i, "task", i / 10.0));
        }

        var result = assembler.assemble(List.of(outcome("task", units.toArray(EvidenceUnit[]::new))));

        assertThat(result.units()).extracting(EvidenceUnit::id).containsExactly("task:9", "task:8", "task:7");
    }

    @Test
    void oneNoisyProviderDoesNotCrowdOutAQuietOne() {
        props.getRetrieval().setUnitsPerProvider(2);
        var result = assembler.assemble(List.of(
                outcome("noisy", unit("n:1", "noisy", 0.9), unit("n:2", "noisy", 0.8), unit("n:3", "noisy", 0.7)),
                outcome("quiet", unit("q:1", "quiet", 0.1))));

        assertThat(result.units()).extracting(EvidenceUnit::id).containsExactly("n:1", "n:2", "q:1");
    }

    @Test
    void theTokenBudgetCutsTheTailAndKeepsAtLeastOneUnit() {
        props.getRetrieval().setEvidenceTokenBudget(1); // four characters
        var result = assembler.assemble(List.of(outcome("task",
                unit("task:1", "task", 0.9), unit("task:2", "task", 0.8))));

        assertThat(result.units()).extracting(EvidenceUnit::id).containsExactly("task:1");
    }

    @Test
    void unitsComeOutMostRelevantFirstAndTiesAreOrderedByIdSoTheOutputIsStable() {
        var result = assembler.assemble(List.of(outcome("task",
                unit("task:b", "task", 0.5), unit("task:a", "task", 0.5), unit("task:c", "task", 0.9))));

        assertThat(result.units()).extracting(EvidenceUnit::id).containsExactly("task:c", "task:a", "task:b");
    }

    @Test
    void anEmptyProviderAndAnUnavailableOneAreRememberedAsDifferentThings() {
        var result = assembler.assemble(List.of(
                new ProviderOutcome("attendance", new ProviderResult.Empty("attendance records for project 42 from A to B")),
                new ProviderOutcome("task", new ProviderResult.Unavailable("The task source could not be read."))));

        assertThat(result.hasEvidence()).isFalse();
        assertThat(result.notes()).extracting(EvidenceAssembler.ProviderNote::kind)
                .containsExactly(NoteKind.EMPTY, NoteKind.UNAVAILABLE);
        assertThat(result.unavailable()).extracting(EvidenceAssembler.ProviderNote::providerId).containsExactly("task");
        assertThat(result.notes().get(0).text()).contains("project 42");
    }

    @Test
    void aProviderThatReturnedEvidenceLeavesNoNote() {
        var result = assembler.assemble(List.of(outcome("task", unit("task:1", "task", 0.5))));

        assertThat(result.notes()).isEmpty();
        assertThat(result.hasEvidence()).isTrue();
    }

    private static ProviderOutcome outcome(String provider, EvidenceUnit... units) {
        return new ProviderOutcome(provider, new ProviderResult.Evidence(List.of(units)));
    }

    private static EvidenceUnit unit(String id, String provider, double relevance) {
        return new EvidenceUnit(id, provider, "kind", Map.of("value", 1), SourceRef.entity("test", id), relevance);
    }
}
