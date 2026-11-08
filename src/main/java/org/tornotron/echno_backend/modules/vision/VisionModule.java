package org.tornotron.echno_backend.modules.vision;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the computer vision module: the model registry, the class taxonomy and, later,
 * the inference jobs and an organization's settings. Regions live in {@code inspections}, since a person can draw one without any
 * model; this module depends on {@code inspections} and never the other way round.
 *
 * <p>The module is dark until an operator turns it on. A feature key alone would not do that: in
 * the default {@code advisory} entitlement mode a refused feature is logged and let through. So
 * unlike the other modules the kill switch {@code echno.modules.vision.enabled} defaults to off,
 * in {@code application.yml} for the registry and in {@link VisionModuleEnabled} for every other
 * bean of the module, which is how the endpoints go away. {@code MODULE_VISION} is granted on no
 * seeded plan; pricing it is a billing data change.
 *
 * <p>Design: {@code docs/specs/2026-10-26-cv-phase4-design.md}, section 4.2.
 */
@Component
public class VisionModule implements EchnoModule {

    public static final String ID = "vision";
    public static final String FEATURE_KEY = "MODULE_VISION";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_RUN = ID + ":run";
    public static final String PERMISSION_MODELS_MANAGE = ID + ":models-manage";

    // Reading the registry is for the jobs that review a model's findings, and for the role a
    // reviewer outside the client's staff holds. One string, so the two handlers cannot drift.
    public static final String READ_GUARD = "@orgSecurity.hasAnyOrgRoleForCurrentTenant("
            + "'system-admin','project-manager','qa-engineer','safety-officer','detection-reviewer')";
    // Asking for a run reveals nothing by itself: the findings it produces are read under
    // READ_GUARD and the detection visibility rule. The same roles, so whoever reviews can re-run.
    public static final String RUN_GUARD = READ_GUARD;
    // An organization's own settings: its admin decides whether every new photo is run.
    public static final String SETTINGS_MANAGE_GUARD = "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')";
    // The registry is global, so writing it is a platform job. An organization's system-admin is
    // whoever created that organization, and must not choose what every organization runs.
    public static final String MODELS_MANAGE_GUARD = "hasRole('platform-admin')";

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_RUN, PERMISSION_MODELS_MANAGE);

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Computer Vision",
            "0.1.0",
            FEATURE_KEY,
            List.of("inspections"),
            PERMISSIONS,
            List.of(),
            false);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
