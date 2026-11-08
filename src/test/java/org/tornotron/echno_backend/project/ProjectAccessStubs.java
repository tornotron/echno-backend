package org.tornotron.echno_backend.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * A {@link ProjectAccess} for tests about something other than access: the caller may do anything to
 * every project and category of the organization in force, which is what every starting role's read
 * gives today. The checks that refuse ({@code require}, {@code requireOn}) do nothing.
 */
public final class ProjectAccessStubs {

    /** Stands in for the organization when a test sets none; an unrestricted filter never reads it. */
    private static final Long NO_ORGANIZATION = -1L;

    private ProjectAccessStubs() {
    }

    public static ProjectAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        return allowEverything(mock(ProjectAccess.class, withSettings().strictness(Strictness.LENIENT)));
    }

    /** Makes a mock the test declared, for one built with {@code @InjectMocks}, allow everything. */
    public static ProjectAccess allowEverything(ProjectAccess access) {
        lenient().when(access.filter(any())).thenAnswer(call -> {
            Long organizationId = TenantContext.getCurrentOrgId();
            return AccessFilter.all(organizationId == null ? NO_ORGANIZATION : organizationId);
        });
        lenient().when(access.allows(any(), any())).thenReturn(true);
        return access;
    }

    /** For a Spring slice that builds a service needing the bean. */
    @TestConfiguration
    public static class AllowingEverything {

        @Bean
        ProjectAccess projectAccess() {
            return allowingEverything();
        }
    }
}
