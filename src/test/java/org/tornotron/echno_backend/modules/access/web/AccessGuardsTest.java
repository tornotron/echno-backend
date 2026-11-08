package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every access handler asks the access matrix, never a role name, and each kind of request needs
 * the action matching its method: reads need {@code access:read}, and creates, changes and deletes
 * need {@code access:edit}, the two actions the module's manifest declares for them. Only
 * {@code /current-user-access} needs bare membership, since it is how a member learns what they
 * hold. Every controller in this package is checked, so a handler added later is too.
 */
class AccessGuardsTest {

    private static final String READ_GUARD = "@access.can('access', 'read')";
    private static final String MEMBER_GUARD = "@access.isMember()";

    private static final Map<Class<? extends Annotation>, String> GUARD_BY_MAPPING = Map.of(
            GetMapping.class, READ_GUARD,
            PostMapping.class, AccessAdminWriteControllerWeb.EDIT_GUARD,
            PutMapping.class, AccessAdminWriteControllerWeb.EDIT_GUARD,
            DeleteMapping.class, AccessAdminWriteControllerWeb.EDIT_GUARD);

    @Test
    void everyHandlerNeedsTheActionMatchingItsMethod() {
        List<Method> handlers = handlers();

        assertThat(handlers).isNotEmpty().allSatisfy(handler -> {
            PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
            assertThat(guard).as("%s has a guard", handler.getName()).isNotNull();
            if (paths(handler).stream().anyMatch(path -> path.endsWith("/current-user-access"))) {
                assertThat(guard.value()).as("%s", handler.getName()).isEqualTo(MEMBER_GUARD);
                return;
            }
            String expected = GUARD_BY_MAPPING.entrySet().stream()
                    .filter(entry -> handler.isAnnotationPresent(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElseThrow();
            assertThat(guard.value()).as("%s", handler.getName()).isEqualTo(expected);
        });
    }

    @Test
    void theWriteHandlersAreAmongThoseChecked() {
        assertThat(handlers()).extracting(Method::getName)
                .contains("createAccessRole", "replaceAccessRoleGrants", "deleteAccessRole", "replaceAccessUserRoles");
    }

    private static List<Method> handlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(AccessGuardsTest.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> ClassUtils.resolveClassName(name, AccessGuardsTest.class.getClassLoader()))
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }

    private static List<String> paths(Method handler) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
        return mapping == null ? List.of() : Arrays.asList(mapping.path());
    }
}
