package org.tornotron.echno_backend.payable;

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
 * A {@link PayableAccess} for tests about something other than access: the caller may do anything to
 * every payable of the organization in force, which is what the system admin's grants give today. The
 * checks that refuse ({@code require}, {@code check}) do nothing.
 */
public final class PayableAccessStubs {

    /** Stands in for the organization when a test sets none; an unrestricted filter never reads it. */
    private static final Long NO_ORGANIZATION = -1L;

    private PayableAccessStubs() {
    }

    public static PayableAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        PayableAccess access = mock(PayableAccess.class, withSettings().strictness(Strictness.LENIENT));
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
        PayableAccess payableAccess() {
            return allowingEverything();
        }
    }
}
