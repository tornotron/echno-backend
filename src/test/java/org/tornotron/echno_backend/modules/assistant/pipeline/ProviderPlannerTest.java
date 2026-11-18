package org.tornotron.echno_backend.modules.assistant.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.provider.ProviderRegistry;

/**
 * The planner picks from what providers say about themselves. The providers here are made up on
 * the spot with invented ids and subjects, which is the point: a provider the planner has never
 * heard of is picked up with no change to it.
 */
class ProviderPlannerTest {

    @Test
    void picksTheProvidersWhoseSubjectsOverlapTheQuestions() {
        ProviderPlanner planner = planner(
                provider("attendance", Set.of(Subject.ATTENDANCE), true),
                provider("task", Set.of(Subject.TASK), false),
                provider("material", Set.of(Subject.MATERIAL), false));

        var plan = planner.plan(Set.of(Subject.TASK, Subject.ATTENDANCE));

        assertThat(plan.selected()).extracting(p -> p.describe().id()).containsExactly("attendance", "task");
        assertThat(plan.uncovered()).isEmpty();
    }

    @Test
    void aProviderAddedLaterIsPickedUpWithNoChangeToThePlanner() {
        ProviderPlanner planner = planner(provider("weather-station", Set.of(Subject.PROJECT), false));

        assertThat(planner.plan(Set.of(Subject.PROJECT)).selected())
                .extracting(p -> p.describe().id()).containsExactly("weather-station");
    }

    @Test
    void aProviderCoveringSeveralSubjectsIsSelectedOnce() {
        ProviderPlanner planner = planner(provider("everything", Set.of(Subject.TASK, Subject.PROJECT), false));

        assertThat(planner.plan(Set.of(Subject.TASK, Subject.PROJECT)).selected()).hasSize(1);
    }

    @Test
    void aSubjectNoProviderCoversIsReportedNotDropped() {
        ProviderPlanner planner = planner(provider("task", Set.of(Subject.TASK), false));

        var plan = planner.plan(Set.of(Subject.TASK, Subject.MATERIAL));

        assertThat(plan.selected()).hasSize(1);
        assertThat(plan.uncovered()).containsExactly(Subject.MATERIAL);
    }

    @Test
    void nothingCoveringTheQuestionSelectsNothing() {
        ProviderPlanner planner = planner(provider("task", Set.of(Subject.TASK), false));

        var plan = planner.plan(Set.of(Subject.DOCUMENT));

        assertThat(plan.selected()).isEmpty();
        assertThat(plan.uncovered()).containsExactly(Subject.DOCUMENT);
    }

    @Test
    void thePlanSaysWhetherAnySelectedProviderNeedsAPeriod() {
        ProviderPlanner planner = planner(
                provider("attendance", Set.of(Subject.ATTENDANCE), true),
                provider("task", Set.of(Subject.TASK), false));

        assertThat(planner.plan(Set.of(Subject.ATTENDANCE)).needsPeriod()).isTrue();
        assertThat(planner.plan(Set.of(Subject.TASK)).needsPeriod()).isFalse();
        assertThat(planner.plan(Set.of(Subject.ATTENDANCE, Subject.TASK)).needsPeriod()).isTrue();
    }

    private static ProviderPlanner planner(AssistantProvider... providers) {
        return new ProviderPlanner(new ProviderRegistry(List.of(providers)));
    }

    private static AssistantProvider provider(String id, Set<Subject> subjects, boolean needsPeriod) {
        ProviderDescriptor descriptor = new ProviderDescriptor(id, "answers things",
                List.of(new FieldSpec("value", "units", "a value")), subjects, needsPeriod, false, new CostProfile(1, 0));
        return new AssistantProvider() {
            @Override
            public ProviderDescriptor describe() {
                return descriptor;
            }

            @Override
            public ProviderResult retrieve(Question question, Scope scope) {
                return new ProviderResult.Empty("nothing");
            }
        };
    }
}
