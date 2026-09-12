package org.tornotron.echno_modulefixtures.violating.modules.beta;

import org.tornotron.echno_modulefixtures.violating.modules.alpha.internal.AlphaInternal;

/** Beta naming alpha's internals; beta also declares no manifest at all. */
public class BetaReachesAlphaInternals {
    public String describe() {
        return new AlphaInternal().describe();
    }
}
