package org.tornotron.echno_modulefixtures.clean.core;

import org.tornotron.echno_modulefixtures.clean.modules.alpha.api.AlphaApi;

/** Core reaching a module through its api subpackage, which is allowed. */
public class CoreUsesAlphaApi {
    private final AlphaApi alpha;

    public CoreUsesAlphaApi(AlphaApi alpha) {
        this.alpha = alpha;
    }

    public String describe() {
        return alpha.describe();
    }
}
