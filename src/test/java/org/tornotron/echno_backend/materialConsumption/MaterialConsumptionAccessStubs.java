package org.tornotron.echno_backend.materialConsumption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * A {@link MaterialConsumptionAccess} for tests about something other than access: the caller may do
 * anything to every consumption of the organization in force.
 */
public final class MaterialConsumptionAccessStubs {

    private MaterialConsumptionAccessStubs() {
    }

    public static MaterialConsumptionAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        MaterialConsumptionAccess access = mock(MaterialConsumptionAccess.class, withSettings().strictness(Strictness.LENIENT));
        when(access.filter(any())).thenAnswer(call -> AccessFilter.all(TenantContext.getCurrentOrgId()));
        when(access.allows(any(), any())).thenReturn(true);
        return access;
    }
}
