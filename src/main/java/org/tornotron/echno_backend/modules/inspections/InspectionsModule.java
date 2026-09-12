package org.tornotron.echno_backend.modules.inspections;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.NavDescriptor;

/**
 * The manifest of the site inspections module: inspections, checklist templates, NCRs and the
 * AI compliance assessments that hang off them.
 *
 * <p>This is the first premium module, and the reference for the ones that follow. It is
 * paywalled on {@value #FEATURE_KEY}, which every seeded plan grants, so nothing changes for a
 * tenant on cutover; the gate runs in advisory mode until the operator flips it. The kill switch
 * is {@code echno.modules.inspections.enabled}, read by the registry for the web surface and by
 * {@link InspectionsModuleEnabled} for the scheduled jobs.
 *
 * <p>Nav paths are the web app's routes under {@code /users/dashboard/inspections}, plus the
 * per-project compliance screen, whose path carries a {@code {projectId}} placeholder since it
 * is opened from a project rather than from the sidebar. Every entry is visible to any member;
 * the web app decides per role what to show, as it did before the module existed.
 */
@Component
public class InspectionsModule implements EchnoModule {

    public static final String ID = "inspections";
    public static final String FEATURE_KEY = "MODULE_INSPECTIONS";

    static final String NAV_SECTION = "inspections";
    static final String ROUTE_ROOT = "/users/dashboard/inspections";

    static final List<String> PERMISSIONS = List.of(
            "inspections.read",
            "inspections.manage",
            "inspections.checklists.define",
            "inspections.ncr.raise",
            "inspections.ncr.corrective-action",
            "inspections.ncr.sign-off",
            "inspections.compliance.generate");

    static final List<NavDescriptor> NAV = List.of(
            new NavDescriptor("Inspections", NAV_SECTION, ROUTE_ROOT, "clipboard-check", List.of()),
            new NavDescriptor("QA/QC", NAV_SECTION, ROUTE_ROOT + "/qa-qc", "clipboard-check", List.of()),
            new NavDescriptor("Safety", NAV_SECTION, ROUTE_ROOT + "/safety", "hard-hat", List.of()),
            new NavDescriptor("NCR / Defects", NAV_SECTION, ROUTE_ROOT + "/ncr", "shield-alert", List.of()),
            new NavDescriptor("Checklist Builder", NAV_SECTION, ROUTE_ROOT + "/checklists", "clipboard-list", List.of()),
            new NavDescriptor("Reports", NAV_SECTION, ROUTE_ROOT + "/reports", "bar-chart-3", List.of()),
            new NavDescriptor("Compliance", "projects",
                    "/users/dashboard/projects/all-projects/{projectId}/compliance", "shield-check", List.of()));

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Site Inspections",
            "1.0.0",
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
