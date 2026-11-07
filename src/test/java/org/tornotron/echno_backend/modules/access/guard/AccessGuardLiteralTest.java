package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;

/**
 * Every {@code @access.can} in the application parses and names a permission the catalog or a
 * module manifest declares, so a typo fails the build instead of quietly refusing everyone.
 *
 * <p>No guard uses {@code @access} yet, which is why {@link #aMalformedLiteralIsWhatTheCheckCatches}
 * proves the check bites on a planted guard.
 */
class AccessGuardLiteralTest {

    private static final String APPLICATION_ROOT = "org.tornotron.echno_backend";

    private static final class PlantedGuards {

        @PreAuthorize("@access.can('finance', 'read')")
        public void valid() {
        }

        @PreAuthorize("@access.can('Finance', 'read')")
        public void malformed() {
        }

        @PreAuthorize("@access.can('harbour', 'read')")
        public void undeclared() {
        }
    }

    @Test
    void everyAccessGuardInTheApplicationIsWellFormedAndDeclared() {
        assertThat(problems(applicationClasses())).isEmpty();
    }

    @Test
    void aMalformedLiteralIsWhatTheCheckCatches() {
        assertThat(problems(List.of(PlantedGuards.class))).containsExactlyInAnyOrder(
                PlantedGuards.class.getName() + ": @access.can('Finance', 'read')",
                PlantedGuards.class.getName() + ": harbour:read is not declared");
    }

    private static List<String> problems(List<Class<?>> classes) {
        AccessCatalogReader catalog = new AccessCatalogReader(realModules(), new EnforcedPermissions(null));
        List<String> problems = new ArrayList<>();
        for (Class<?> type : classes) {
            for (String expression : EnforcedPermissions.expressionsOn(type)) {
                AccessGuardExpressions.Parsed parsed = AccessGuardExpressions.parse(expression);
                parsed.malformed().forEach(call -> problems.add(type.getName() + ": " + call));
                for (AccessPermission permission : parsed.permissions()) {
                    if (!catalog.declares(permission)) {
                        problems.add(type.getName() + ": " + permission + " is not declared");
                    }
                }
            }
        }
        return problems;
    }

    private static ModuleRegistry realModules() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(EchnoModule.class));
        List<EchnoModule> modules = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(APPLICATION_ROOT + ".modules")) {
            modules.add((EchnoModule) BeanUtils.instantiateClass(load(definition)));
        }
        return new ModuleRegistry(modules, (org, key) -> true, new MockEnvironment());
    }

    private static List<Class<?>> applicationClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(APPLICATION_ROOT)) {
            classes.add(load(definition));
        }
        assertThat(classes).as("the scan found the application's controllers").hasSizeGreaterThan(50);
        return classes;
    }

    private static Class<?> load(BeanDefinition definition) {
        try {
            return Class.forName(definition.getBeanClassName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
