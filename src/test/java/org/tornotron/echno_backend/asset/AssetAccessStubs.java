package org.tornotron.echno_backend.asset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * An {@link AssetAccess} for tests about something other than access: the caller may do anything to
 * every asset of the organization in force, which is what every starting role's read gives today.
 */
public final class AssetAccessStubs {

    private AssetAccessStubs() {
    }

    public static AssetAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        AssetAccess access = mock(AssetAccess.class, withSettings().strictness(Strictness.LENIENT));
        when(access.filter(any())).thenAnswer(call -> AccessFilter.all(TenantContext.getCurrentOrgId()));
        when(access.allows(any(), any())).thenReturn(true);
        return access;
    }
}
