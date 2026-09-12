package org.tornotron.echno_modulefixtures.clean.modules.beta;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

import java.util.List;

/** The single manifest bean of the beta module. */
@Component
public class BetaModule implements EchnoModule {
    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest("beta", "Beta", "1.0.0", "MODULE_BETA",
                List.of("alpha"), List.of(), List.of(), false);
    }
}
