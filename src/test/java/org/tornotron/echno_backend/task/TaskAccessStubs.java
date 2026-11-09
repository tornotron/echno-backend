package org.tornotron.echno_backend.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * A {@link TaskAccess} for tests about something other than access: the caller may do anything to
 * every task of the organization in force.
 */
public final class TaskAccessStubs {

    private TaskAccessStubs() {
    }

    public static TaskAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        return allowEverything(mock(TaskAccess.class, withSettings().strictness(Strictness.LENIENT)));
    }

    /** Makes a mock a test already holds, such as one {@code @InjectMocks} passed on, allow everything. */
    public static TaskAccess allowEverything(TaskAccess access) {
        // A test that sets no organization still gets an answer: every task, of no organization in particular.
        lenient().when(access.filter(any())).thenAnswer(call -> AccessFilter.all(
                TenantContext.getCurrentOrgId() == null ? 0L : TenantContext.getCurrentOrgId()));
        lenient().when(access.allows(any(), any())).thenReturn(true);
        return access;
    }
}
