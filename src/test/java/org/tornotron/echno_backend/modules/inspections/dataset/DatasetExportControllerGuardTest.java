package org.tornotron.echno_backend.modules.inspections.dataset;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dataset.web.DatasetExportControllerWeb;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export endpoints are for the organization's system-admin only, behind the inspections
 * feature (#791). Read off the annotations, the way the module's other routing tests do,
 * rather than through a web slice that would cost another cached context.
 */
class DatasetExportControllerGuardTest {

    private static final String SYSTEM_ADMIN_ONLY = "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')";

    @Test
    void everyHandlerIsGuardedOnTheSystemAdminRoleOfTheCurrentTenant() {
        List<Method> handlers = Arrays.stream(DatasetExportControllerWeb.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .toList();
        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrder("run", "list", "get");
        for (Method handler : handlers) {
            PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
            assertThat(guard).as("guard on %s", handler.getName()).isNotNull();
            assertThat(guard.value()).as("guard on %s", handler.getName()).isEqualTo(SYSTEM_ADMIN_ONLY);
        }
    }

    @Test
    void theControllerIsBehindTheInspectionsFeatureAndUnderTheModulesWebPrefix() {
        RequireSubscription gate = DatasetExportControllerWeb.class.getAnnotation(RequireSubscription.class);
        assertThat(gate).isNotNull();
        assertThat(gate.feature()).isEqualTo(InspectionsModule.FEATURE_KEY);
        assertThat(DatasetExportControllerWeb.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/inspections/web/dataset-export/runs");
    }
}
