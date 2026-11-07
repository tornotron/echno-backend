package org.tornotron.echno_backend.modules.documentingestion;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.NavDescriptor;

/**
 * The manifest of the document ingestion module: tender documents read into candidate values that
 * a person accepts, with a link from each accepted value to its source.
 *
 * <p>Dark until an operator opens it, for every organization and in every entitlement mode. A
 * feature key alone would not do that, because the default {@code advisory} mode logs a refused
 * feature and lets the call through. So the switch reads as off when unset, the allow-list starts
 * empty, and {@code MODULE_DOCUMENT_INGESTION} is seeded on no plan. Design:
 * {@code docs/specs/2026-11-08-document-ingestion-design.md}, section 6.7.
 *
 * <p>{@code dependsOn} stays empty. Project, WBS and risk are core, and an applier that needs an
 * optional module asks the registry and refuses with a clear message when it is off.
 */
@Component
public class DocumentIngestionModule implements EchnoModule {

    public static final String ID = "document-ingestion";
    public static final String VERSION = "0.1.0";
    public static final String FEATURE_KEY = "MODULE_DOCUMENT_INGESTION";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_UPLOAD = ID + ":upload";
    public static final String PERMISSION_REVIEW = ID + ":review";
    public static final String PERMISSION_APPLY = ID + ":apply";
    public static final String PERMISSION_ADMIN = ID + ":admin";

    // One guard per permission, section 6.6. They move to @access with the rest of the codebase
    // when the access matrix reaches development.
    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String UPLOAD_GUARD = "@orgSecurity.hasAnyOrgRoleForCurrentTenant("
            + "'system-admin','project-manager','qa-engineer','site-engineer')";
    public static final String REVIEW_GUARD = UPLOAD_GUARD;
    public static final String APPLY_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";
    public static final String ADMIN_GUARD = "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')";

    static final String ROUTE_ROOT = "/users/dashboard/" + ID;
    static final String NAV_SECTION = "projects";

    static final List<String> PERMISSIONS =
            List.of(PERMISSION_READ, PERMISSION_UPLOAD, PERMISSION_REVIEW, PERMISSION_APPLY, PERMISSION_ADMIN);

    static final List<NavDescriptor> NAV = List.of(
            new NavDescriptor("Import from documents", NAV_SECTION, ROUTE_ROOT, "file-input",
                    List.of(PERMISSION_READ)));

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Document Ingestion",
            VERSION,
            FEATURE_KEY,
            List.of(),
            PERMISSIONS,
            NAV,
            false);

    private final DocumentIngestionSwitch operatorSwitch;

    public DocumentIngestionModule(Environment environment) {
        this.operatorSwitch = new DocumentIngestionSwitch(environment);
    }

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }

    @Override
    public boolean switchedOnWhenUnset() {
        return false;
    }

    @Override
    public boolean isOfferedTo(Long organizationId) {
        return operatorSwitch.allows(organizationId);
    }
}
