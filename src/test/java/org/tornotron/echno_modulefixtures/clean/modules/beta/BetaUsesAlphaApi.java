package org.tornotron.echno_modulefixtures.clean.modules.beta;

import org.tornotron.echno_modulefixtures.clean.modules.alpha.api.AlphaApi;

/** One module reaching another through its api subpackage, which is allowed. */
public class BetaUsesAlphaApi {
    private final AlphaApi alpha;

    public BetaUsesAlphaApi(AlphaApi alpha) {
        this.alpha = alpha;
    }

    public String describe() {
        return "beta over " + alpha.describe();
    }
}
