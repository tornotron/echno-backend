package org.tornotron.echno_backend.modules.toolboxtalks;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.NavDescriptor;

/**
 * The manifest of the Toolbox Talks module.
 *
 * <p>Paywalled on {@value #FEATURE_KEY}, which the module's feature seed grants on every plan,
 * so no tenant goes dark on cutover. The kill switch is {@code echno.modules.toolbox-talks.enabled},
 * read by the registry for the request surface and by {@link ToolboxTalksModuleEnabled} for
 * anything scheduled.
 *
 * <p>Permission keys use the {@code <module>:<action>} vocabulary, the same form as the
 * {@code @PreAuthorize} authorities elsewhere in the API and the web nav gate.
 */
@Component
public class ToolboxTalksModule implements EchnoModule {

    public static final String ID = "toolbox-talks";
    public static final String FEATURE_KEY = "MODULE_TOOLBOX_TALKS";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_MANAGE = ID + ":manage";

    static final String ROUTE_ROOT = "/users/dashboard/" + ID;

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_MANAGE);

    static final List<NavDescriptor> NAV = List.of(
            new NavDescriptor("Toolbox Talks", ID, ROUTE_ROOT, "package", List.of(PERMISSION_READ)));

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Toolbox Talks",
            "0.1.0",
            FEATURE_KEY,
            List.of(),
            PERMISSIONS,
            NAV,
            false);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
