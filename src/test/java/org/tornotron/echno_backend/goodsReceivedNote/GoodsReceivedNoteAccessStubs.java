package org.tornotron.echno_backend.goodsReceivedNote;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;

/**
 * A {@link GoodsReceivedNoteAccess} for tests about something other than access: the caller may do
 * anything to every receipt of the organization in force.
 */
public final class GoodsReceivedNoteAccessStubs {

    private GoodsReceivedNoteAccessStubs() {
    }

    public static GoodsReceivedNoteAccess allowingEverything() {
        // Lenient, since a test exercises only some of these.
        GoodsReceivedNoteAccess access = mock(GoodsReceivedNoteAccess.class, withSettings().strictness(Strictness.LENIENT));
        when(access.filter(any())).thenAnswer(call -> AccessFilter.all(TenantContext.getCurrentOrgId()));
        when(access.allows(any(), any())).thenReturn(true);
        return access;
    }
}
