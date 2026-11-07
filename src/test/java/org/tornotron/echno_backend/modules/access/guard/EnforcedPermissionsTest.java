package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;

/**
 * The boot-time scan: guards on a class and on its methods count, only {@code @access.can} counts,
 * and a malformed guard stops nothing.
 */
class EnforcedPermissionsTest {

    @PreAuthorize("@access.can('tasks', 'read')")
    static class GuardedClass {

        @PreAuthorize("@access.can('wbs', 'edit') or hasAuthority('finance:read')")
        public void edit() {
        }

        @PreAuthorize("@access.can('Bad Area', 'edit')")
        public void malformed() {
        }

        @PreAuthorize("isAuthenticated()")
        public void open() {
        }
    }

    @Test
    void readsTheGuardsOffTheBeansAtBoot() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(GuardedClass.class, EnforcedPermissions.class);
            context.refresh();

            EnforcedPermissions enforced = context.getBean(EnforcedPermissions.class);

            assertThat(enforced.all()).containsExactlyInAnyOrder(
                    AccessPermission.of("tasks:read"), AccessPermission.of("wbs:edit"));
            assertThat(enforced.isEnforced(AccessPermission.of("wbs:edit"))).isTrue();
            assertThat(enforced.isEnforced(AccessPermission.of("finance:read"))).isFalse();
        }
    }
}
