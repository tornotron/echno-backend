package org.tornotron.echno_backend.modules.bim;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.NavDescriptor;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;

/**
 * The manifest of the BIM module: IFC models per project, their versions, the element table
 * that keeps an IFC GlobalId stable across re-imports, and the import jobs a worker consumes.
 *
 * <p>Paywalled on {@value #FEATURE_KEY}, granted on every seeded plan so no tenant goes dark
 * while the gate runs in advisory mode. Depends on the inspections module because elements
 * exist to be inspected: the QA/QC hierarchy they attach to is core, but the associations
 * that hang off a construction element (inspections, defects, observations) are that
 * module's. The kill switch is {@code echno.modules.bim.enabled}, read by the registry for
 * the request surface and by {@link BimModuleEnabled} for the import poller.
 *
 * <p>Design: {@code echno-roadmap/bim/bim-ingestion-viewer-element-identity.md}.
 */
@Component
public class BimModule implements EchnoModule {

    public static final String ID = "bim";
    public static final String FEATURE_KEY = "MODULE_BIM";

    public static final String PERMISSION_VIEW = "bim.view";
    public static final String PERMISSION_MANAGE = "bim.manage";

    static final List<String> PERMISSIONS = List.of(PERMISSION_VIEW, PERMISSION_MANAGE);

    static final List<NavDescriptor> NAV = List.of(
            new NavDescriptor("BIM", "projects",
                    "/users/dashboard/projects/all-projects/{projectId}/bim", "box", List.of()));

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "BIM Models",
            "1.0.0",
            FEATURE_KEY,
            List.of(InspectionsModule.ID),
            PERMISSIONS,
            NAV,
            false);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
