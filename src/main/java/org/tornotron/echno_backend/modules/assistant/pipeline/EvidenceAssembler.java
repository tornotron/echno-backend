package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.pipeline.ParallelRetriever.ProviderOutcome;

/**
 * Step four: tidies what the providers returned into what the model will be shown. Duplicates
 * by evidence id are dropped, each provider is capped, everything is cut to a token budget, and
 * the most relevant units come first.
 *
 * <p>It also keeps a note of every provider that returned nothing, and why: empty, unavailable
 * (failed or timed out), or denied (the caller lacks access). Denied providers are never counted as
 * unavailable so permissions do not masquerade as source failures.
 */
@Component
@AssistantModuleEnabled
public class EvidenceAssembler {

    /** Roughly how many characters make a token, for sizing evidence against the budget. */
    private static final int CHARS_PER_TOKEN = 4;

    public enum NoteKind {
        /** The provider looked and found nothing. */
        EMPTY,
        /** The provider could not look (error, timeout, or queue rejection). */
        UNAVAILABLE,
        /** The caller does not have permission to read this provider; left out quietly. */
        DENIED
    }

    public record ProviderNote(String providerId, NoteKind kind, String text) {
    }

    /** The evidence the model will see, and the providers that gave none. */
    public record Assembled(List<EvidenceUnit> units, List<ProviderNote> notes) {

        public Assembled {
            units = List.copyOf(units);
            notes = List.copyOf(notes);
        }

        public boolean hasEvidence() {
            return !units.isEmpty();
        }

        public List<ProviderNote> unavailable() {
            return notes.stream().filter(n -> n.kind() == NoteKind.UNAVAILABLE).toList();
        }

        public List<ProviderNote> denied() {
            return notes.stream().filter(n -> n.kind() == NoteKind.DENIED).toList();
        }
    }

    private final AssistantProperties props;

    public EvidenceAssembler(AssistantProperties props) {
        this.props = props;
    }

    public Assembled assemble(List<ProviderOutcome> outcomes) {
        int perProvider = Math.max(1, props.getRetrieval().getUnitsPerProvider());
        List<ProviderNote> notes = new ArrayList<>();
        Map<String, EvidenceUnit> byId = new LinkedHashMap<>();

        for (ProviderOutcome outcome : outcomes) {
            if (outcome.accessDenied()) {
                notes.add(new ProviderNote(outcome.providerId(), NoteKind.DENIED, "not accessible to caller"));
                continue;
            }
            switch (outcome.result()) {
                case ProviderResult.Evidence evidence -> {
                    Map<String, EvidenceUnit> own = new LinkedHashMap<>();
                    evidence.units().forEach(u -> own.merge(u.id(), u, EvidenceAssembler::moreRelevant));
                    own.values().stream().sorted(RELEVANT_FIRST).limit(perProvider)
                            .forEach(u -> byId.merge(u.id(), u, EvidenceAssembler::moreRelevant));
                }
                case ProviderResult.Empty empty ->
                        notes.add(new ProviderNote(outcome.providerId(), NoteKind.EMPTY, empty.lookedFor()));
                case ProviderResult.Unavailable unavailable ->
                        notes.add(new ProviderNote(outcome.providerId(), NoteKind.UNAVAILABLE, unavailable.reason()));
            }
        }

        long budget = (long) Math.max(1, props.getRetrieval().getEvidenceTokenBudget()) * CHARS_PER_TOKEN;
        List<EvidenceUnit> kept = new ArrayList<>();
        long used = 0;
        for (EvidenceUnit unit : byId.values().stream().sorted(RELEVANT_FIRST).toList()) {
            long size = unit.id().length() + unit.values().toString().length();
            if (!kept.isEmpty() && used + size > budget) {
                break;
            }
            kept.add(unit);
            used += size;
        }
        return new Assembled(kept, notes);
    }

    private static final Comparator<EvidenceUnit> RELEVANT_FIRST =
            Comparator.comparingDouble(EvidenceUnit::relevance).reversed().thenComparing(EvidenceUnit::id);

    private static EvidenceUnit moreRelevant(EvidenceUnit a, EvidenceUnit b) {
        return b.relevance() > a.relevance() ? b : a;
    }
}
