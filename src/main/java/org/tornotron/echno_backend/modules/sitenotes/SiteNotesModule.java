package org.tornotron.echno_backend.modules.sitenotes;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.NavDescriptor;

/**
 * The manifest of the Site Notes module.
 *
 * <p>Paywalled on {@value #FEATURE_KEY}, which the module's feature seed grants on every plan,
 * so no tenant goes dark on cutover. The kill switch is {@code echno.modules.site-notes.enabled},
 * read by the registry for the request surface and by {@link SiteNotesModuleEnabled} for
 * anything scheduled.
 *
 * <p>Permission keys use the {@code <module>:<action>} vocabulary, the same form as the
 * {@code @PreAuthorize} authorities elsewhere in the API and the web nav gate.
 */
@Component
public class SiteNotesModule implements EchnoModule {

    public static final String ID = "site-notes";
    public static final String FEATURE_KEY = "MODULE_SITE_NOTES";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_MANAGE = ID + ":manage";

    // The two guards every handler in the module carries. Reading a note is for any member of
    // the tenant; writing one is for the roles that run a site: the project manager, the site
    // engineer and the system admin. One string each, so the twins cannot drift.
    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String MANAGE_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager','site-engineer')";

    static final String ROUTE_ROOT = "/users/dashboard/" + ID;
    // A note belongs to a project, and that is where the site engineer or project manager who
    // writes one is already looking, so the entry sits with the rest of the project's tools
    // rather than in a section of its own.
    static final String NAV_SECTION = "projects";

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_MANAGE);

    static final List<NavDescriptor> NAV = List.of(
            new NavDescriptor("Site Notes", NAV_SECTION, ROUTE_ROOT, "notebook-pen", List.of(PERMISSION_READ)));

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Site Notes",
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
