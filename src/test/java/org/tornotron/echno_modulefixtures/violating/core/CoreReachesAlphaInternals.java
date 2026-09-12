package org.tornotron.echno_modulefixtures.violating.core;

import org.tornotron.echno_modulefixtures.violating.modules.alpha.internal.AlphaInternal;

/** Core naming a module's internals. */
public class CoreReachesAlphaInternals {
    public String describe() {
        return new AlphaInternal().describe();
    }
}
