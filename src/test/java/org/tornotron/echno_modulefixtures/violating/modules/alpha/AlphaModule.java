package org.tornotron.echno_modulefixtures.violating.modules.alpha;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

import java.util.List;

/** One of two manifest beans in alpha, which is one too many. */
@Component
public class AlphaModule implements EchnoModule {
    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest("alpha", "Alpha", "1.0.0", "MODULE_ALPHA",
                List.of(), List.of(), List.of(), false);
    }
}
