package org.tornotron.echno_modulefixtures.violating.core;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

import java.util.List;

/** A manifest bean outside any module package. */
@Component
public class StrayModule implements EchnoModule {
    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest("stray", "Stray", "1.0.0", null,
                List.of(), List.of(), List.of(), true);
    }
}
