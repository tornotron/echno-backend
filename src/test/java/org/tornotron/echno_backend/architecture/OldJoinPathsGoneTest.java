package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person joins an organization only by an invitation link. The invite codes and both
 * {@code joinOrganization} routes are gone, and this keeps them gone.
 */
class OldJoinPathsGoneTest {

    @Test
    void noRouteJoinsAnOrganizationByCodeOrByUserId() throws Exception {
        assertThat(routes())
                .isNotEmpty()
                .noneMatch(path -> path.contains("joinOrganization"))
                .noneMatch(path -> path.startsWith("/api/v1/invitation/"))
                .anyMatch(path -> path.startsWith("/api/v1/invitations/web"));
    }

    private static List<String> routes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> paths = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.tornotron.echno_backend")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> prefixes = root == null || root.path().length == 0 ? List.of("") : Arrays.asList(root.path());
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> suffixes = mapping.path().length == 0 ? List.of("") : Arrays.asList(mapping.path());
                prefixes.forEach(prefix -> suffixes.forEach(suffix -> paths.add(prefix + suffix)));
            }
        }
        return paths;
    }
}
