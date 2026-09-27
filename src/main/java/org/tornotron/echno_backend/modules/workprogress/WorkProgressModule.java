package org.tornotron.echno_backend.modules.workprogress;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the Work Progress module: progress inspections of schedule activities now, and
 * the sub-contract claim, measurement and certification chain in later steps
 * ({@code docs/specs/2026-09-28-work-progress-inspection.md}).
 *
 * <p>Paywalled on {@value #FEATURE_KEY}, which the module's feature seed grants on every plan, so
 * no tenant goes dark on release. The schedule it inspects is the core WBS; this module holds only
 * what is recorded against it. It publishes no nav entry: its screens sit on the project page's
 * WBS tab.
 */
@Component
public class WorkProgressModule implements EchnoModule {

    public static final String ID = "work-progress";
    public static final String FEATURE_KEY = "MODULE_WORK_PROGRESS";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_RECORD = ID + ":record";

    // Any member of the tenant reads the record. The project team records it: the project
    // manager, the site engineer and the system admin, as the product owner asked that the project
    // team maintain the actuals. One string each, so the twin controllers cannot drift.
    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String RECORD_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager','site-engineer')";

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_RECORD);

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Work Progress",
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
