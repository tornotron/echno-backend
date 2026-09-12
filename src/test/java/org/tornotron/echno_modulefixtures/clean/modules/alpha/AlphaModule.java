package org.tornotron.echno_modulefixtures.clean.modules.alpha;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_modulefixtures.clean.modules.alpha.internal.AlphaInternal;

import java.util.List;

/** The single manifest bean of the alpha module. */
@Component
public class AlphaModule implements EchnoModule {

    private final AlphaInternal internal = new AlphaInternal();

    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest("alpha", internal.describe(), "1.0.0", "MODULE_ALPHA",
                List.of(), List.of(), List.of(), false);
    }
}
