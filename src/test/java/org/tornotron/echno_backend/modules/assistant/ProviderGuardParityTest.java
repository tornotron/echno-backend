package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.tornotron.echno_backend.attendance.AttendanceController;
import org.tornotron.echno_backend.attendance.AttendanceControllerWeb;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.provider.AttendanceProvider;

/**
 * A provider's {@code retrieve} carries the same guard as the controller endpoint it mirrors.
 *
 * <p>{@code @PreAuthorize} sits on controllers in this codebase, not on services, and a provider is
 * a bean called from the pipeline, so nothing else guards it. If a guard were tightened on a
 * controller and left loose on its provider, the assistant would hand a caller evidence the
 * endpoint refuses them. This reads both annotations and fails when they differ.
 *
 * <p>It also fails for a provider with no entry here, so a new one cannot skip the check: adding a
 * provider means naming the endpoint it mirrors.
 */
class ProviderGuardParityTest {

    /** The controller handler a provider's retrieve mirrors, on every controller twin that has it. */
    private record Mirror(List<Class<?>> controllers, String handler, String route) {
    }

    private static final Map<Class<? extends AssistantProvider>, Mirror> MIRRORS = Map.of(
            AttendanceProvider.class,
            new Mirror(List.of(AttendanceController.class, AttendanceControllerWeb.class),
                    "getByProject", "/project/{projectId}"));

    @Test
    void everyProviderCarriesTheGuardOfTheEndpointItMirrors() throws Exception {
        for (Map.Entry<Class<? extends AssistantProvider>, Mirror> entry : MIRRORS.entrySet()) {
            String providerGuard = guardOf(entry.getKey().getMethod("retrieve",
                    org.tornotron.echno_backend.modules.assistant.api.Question.class,
                    org.tornotron.echno_backend.modules.assistant.api.Scope.class));

            for (Class<?> controller : entry.getValue().controllers()) {
                Method handler = handler(controller, entry.getValue());
                assertThat(providerGuard)
                        .as("%s.retrieve against %s.%s", entry.getKey().getSimpleName(),
                                controller.getSimpleName(), handler.getName())
                        .isEqualTo(guardOf(handler));
            }
        }
    }

    @Test
    void theMirroredHandlersAreTheReadsTheyAreMeantToBe() throws Exception {
        // A rename that left the name pointing at some other method would make the test above
        // compare against the wrong guard and pass.
        for (Mirror mirror : MIRRORS.values()) {
            for (Class<?> controller : mirror.controllers()) {
                Method handler = handler(controller, mirror);
                assertThat(handler.getAnnotation(GetMapping.class).value())
                        .as("%s.%s is a read of %s", controller.getSimpleName(), handler.getName(), mirror.route())
                        .contains(mirror.route());
            }
        }
    }

    @Test
    void everyProviderInThePackageHasAMirrorSoANewOneCannotSkipTheCheck() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantProvider.class));
        Set<String> found = scanner.findCandidateComponents("org.tornotron.echno_backend.modules.assistant")
                .stream().map(BeanDefinition::getBeanClassName).collect(Collectors.toCollection(TreeSet::new));

        Set<String> mirrored = MIRRORS.keySet().stream().map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(found).as("providers found by scanning versus providers with a guard mirror")
                .isEqualTo(mirrored);
    }

    private static Method handler(Class<?> controller, Mirror mirror) {
        List<Method> named = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(mirror.handler())).toList();
        assertThat(named).as("%s declares exactly one %s", controller.getSimpleName(), mirror.handler())
                .hasSize(1);
        return named.get(0);
    }

    private static String guardOf(Method method) {
        PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
        assertThat(guard).as("%s.%s carries @PreAuthorize", method.getDeclaringClass().getSimpleName(),
                method.getName()).isNotNull();
        assertThat(guard.value()).isNotBlank().doesNotContain("permitAll");
        return guard.value();
    }
}
