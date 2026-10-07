package org.tornotron.echno_backend.modules.assistant;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the Assistant module: natural-language questions over attendance, tasks,
 * materials and uploaded documents, answered from evidence fetched under the caller's own scope.
 * The design is in {@code docs/specs/2026-09-20-assistant-module.md}.
 *
 * <p>Paywalled on {@value #FEATURE_KEY}. Unlike the modules that ship on every plan, the feature
 * is granted on no plan by default: the module is premium and stays dark until billing adds it.
 * The kill switch is {@code echno.modules.assistant.enabled}, read by the registry for the
 * request surface and by {@link AssistantModuleEnabled} for anything scheduled.
 *
 * <p>Permission keys use the {@code <module>:<action>} vocabulary, the same form as the
 * {@code @PreAuthorize} authorities elsewhere in the API. They name the surface and add nothing
 * above the existing model: what a member can see through {@code ask} is still whatever the
 * guard on each provider allows.
 */
@Component
public class AssistantModule implements EchnoModule {

    public static final String ID = "assistant";
    public static final String FEATURE_KEY = "MODULE_ASSISTANT";

    public static final String PERMISSION_ASK = ID + ":ask";
    public static final String PERMISSION_INGEST = ID + ":ingest";
    public static final String PERMISSION_ADMIN = ID + ":admin";

    static final List<String> PERMISSIONS = List.of(PERMISSION_ASK, PERMISSION_INGEST, PERMISSION_ADMIN);

    // No nav entry: the web panel is a later package, and a menu item for a route that does not
    // exist would be worse than none.
    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Assistant",
            "0.1.0",
            FEATURE_KEY,
            List.of(),
            PERMISSIONS,
            List.of(),
            false);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
