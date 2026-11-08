package org.tornotron.echno_backend.indent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * An {@link IndentAccess} for tests about something other than access: the caller may do anything to
 * every indent of the organization in force, which is what the system admin's starting role gives.
 */
public final class IndentAccessStubs {

    private IndentAccessStubs() {
    }

    public static IndentAccess allowingEverything() {
        // Lenient, since a test exercises only some of these. check and require are left as the mock's
        // no-ops, so they refuse nothing.
        IndentAccess access = mock(IndentAccess.class, withSettings().strictness(Strictness.LENIENT));
        when(access.filter(any())).thenAnswer(call -> AccessFilter.all(TenantContext.getCurrentOrgId()));
        when(access.allows(any(), any())).thenReturn(true);
        return access;
    }
}
