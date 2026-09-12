package org.tornotron.echno_modulefixtures.clean.modules.alpha.internal;

import org.tornotron.echno_modulefixtures.clean.modules.alpha.api.AlphaApi;

/** Alpha's own business; nothing outside alpha may name it. */
public class AlphaInternal implements AlphaApi {
    @Override
    public String describe() {
        return "alpha";
    }
}
