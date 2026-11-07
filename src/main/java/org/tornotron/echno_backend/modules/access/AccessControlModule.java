package org.tornotron.echno_backend.modules.access;

import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;

/**
 * The manifest of the access control module: who may do what, on which records.
 *
 * <p>Free and on for every organization, with no billing feature behind it. Nothing outside the
 * module reaches past {@code modules.access.api}, which speaks in decisions ({@code check},
 * {@code filter}, {@code grantsFor}) and never in roles or grants. That keeps the engine behind it
 * replaceable: the roles and permissions matrix is the first one.
 *
 * <p>The registry's kill switch reports this module as off when it is set, but the guard and the
 * engine do not read the registry, so access decisions do not depend on it.
 *
 * <p>No navigation of its own: the Roles &amp; Permissions screen is a tab on the organization
 * page, which the web app gates on {@value #PERMISSION_READ}.
 */
@Component
public class AccessControlModule implements EchnoModule {

    public static final String ID = "access";

    public static final String PERMISSION_READ = ID + ":read";
    public static final String PERMISSION_EDIT = ID + ":edit";

    /** Changing the baseline role every employee holds, which is a separate decision. */
    public static final String PERMISSION_EDIT_EMPLOYEE_ROLE = ID + ":edit-employee-role";

    static final List<String> PERMISSIONS = List.of(PERMISSION_READ, PERMISSION_EDIT, PERMISSION_EDIT_EMPLOYEE_ROLE);

    private static final ModuleManifest MANIFEST = new ModuleManifest(
            ID,
            "Access Control",
            "0.1.0",
            null,
            List.of(),
            PERMISSIONS,
            List.of(),
            true);

    @Override
    public ModuleManifest manifest() {
        return MANIFEST;
    }
}
