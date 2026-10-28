package org.tornotron.echno_backend.modules.access;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the access control module: who may do what, on which records.
 *
 * <p>Core, so it is on for every organization, never paywalled, and has no kill switch. Nothing
 * outside the module reaches past {@code modules.access.api}, which speaks in decisions
 * ({@code check}, {@code filter}, {@code grantsFor}) and never in roles or grants. That keeps the
 * engine behind it replaceable: the roles and permissions matrix is the first one.
 *
 * <p>No navigation of its own: the Roles &amp; Permissions screen is a tab on the organization
 * page, which the web app gates on {@value #PERMISSION_VIEW}.
 */
@Component
public class AccessControlModule implements EchnoModule {

    public static final String ID = "access";

    public static final String PERMISSION_VIEW = ID + ":view";
    public static final String PERMISSION_EDIT = ID + ":edit";

    static final List<String> PERMISSIONS = List.of(PERMISSION_VIEW, PERMISSION_EDIT);

    private static final ModuleManifest MANIFEST = ModuleManifest.core(
            ID,
            "Access Control",
            "0.1.0",
            List.of(),
            PERMISSIONS,
            List.of());

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
