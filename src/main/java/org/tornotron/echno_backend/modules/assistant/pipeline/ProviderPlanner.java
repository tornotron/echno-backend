package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.provider.ProviderRegistry;

/**
 * Step two: which providers can help. It reads what each provider says about itself in
 * {@link AssistantProvider#describe()} and picks those whose subjects overlap what the question
 * is about. It holds no list of "this kind of question goes to that provider", so a provider
 * added later is picked up by describing itself and nothing here changes.
 *
 * <p>A provider that needs a period is still selected when the question names none: the default
 * period is filled in afterward and stated in the answer. {@link #needsPeriod} tells the caller
 * whether it has to.
 */
@Component
@AssistantModuleEnabled
public class ProviderPlanner {

    private final ProviderRegistry registry;

    public ProviderPlanner(ProviderRegistry registry) {
        this.registry = registry;
    }

    /** The providers to call, in id order, and the subjects no provider covers. */
    public record Plan(List<AssistantProvider> selected, Set<Subject> uncovered) {

        public Plan {
            selected = List.copyOf(selected);
            uncovered = Collections.unmodifiableSet(uncovered);
        }

        /** True when any selected provider answers only for a period. */
        public boolean needsPeriod() {
            return selected.stream().anyMatch(p -> p.describe().needsPeriod());
        }
    }

    public Plan plan(Set<Subject> subjects) {
        List<AssistantProvider> selected = registry.all().stream()
                .filter(p -> overlaps(p.describe(), subjects))
                .toList();
        Set<Subject> uncovered = new java.util.TreeSet<>(subjects);
        selected.forEach(p -> uncovered.removeAll(p.describe().subjects()));
        return new Plan(selected, uncovered);
    }

    private static boolean overlaps(ProviderDescriptor descriptor, Set<Subject> subjects) {
        return descriptor.subjects().stream().anyMatch(subjects::contains);
    }
}
