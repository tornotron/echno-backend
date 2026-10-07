package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** The registry finds providers by id, in id order, and refuses two that claim the same one. */
class ProviderRegistryTest {

    @Test
    void findsEveryProviderByItsId() {
        AssistantProvider attendance = provider("attendance");
        AssistantProvider task = provider("task");

        ProviderRegistry registry = new ProviderRegistry(List.of(attendance, task));

        assertThat(registry.byId("attendance")).containsSame(attendance);
        assertThat(registry.byId("task")).containsSame(task);
        assertThat(registry.byId("material")).isEmpty();
    }

    @Test
    void listsProvidersInIdOrderNotRegistrationOrder() {
        AssistantProvider task = provider("task");
        AssistantProvider attendance = provider("attendance");
        AssistantProvider material = provider("material");

        ProviderRegistry registry = new ProviderRegistry(List.of(task, attendance, material));

        assertThat(registry.all()).extracting(p -> p.describe().id())
                .containsExactly("attendance", "material", "task");
    }

    @Test
    void twoProvidersClaimingOneIdStopTheApplicationRatherThanOneShadowingTheOther() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(provider("attendance"), provider("attendance"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attendance");
    }

    @Test
    void worksWithNoProvidersAtAll() {
        ProviderRegistry registry = new ProviderRegistry(List.<AssistantProvider>of());

        assertThat(registry.all()).isEmpty();
        assertThat(registry.byId("attendance")).isEmpty();
    }

    @Test
    void theListItReturnsCannotBeUsedToChangeTheRegistry() {
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("attendance")));

        assertThatThrownBy(() -> registry.all().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    static AssistantProvider provider(String id) {
        ProviderDescriptor descriptor = new ProviderDescriptor(id, "answers things", List.of(
                new FieldSpec("value", "units", "a value")),
                Set.of(Subject.ATTENDANCE), false, false, new CostProfile(1, 0));
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
