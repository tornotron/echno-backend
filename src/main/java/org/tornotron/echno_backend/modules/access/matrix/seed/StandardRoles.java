package org.tornotron.echno_backend.modules.access.matrix.seed;

import static org.tornotron.echno_backend.modules.access.api.AccessPermission.APPROVE;
import static org.tornotron.echno_backend.modules.access.api.AccessPermission.CREATE;
import static org.tornotron.echno_backend.modules.access.api.AccessPermission.DELETE;
import static org.tornotron.echno_backend.modules.access.api.AccessPermission.EDIT;
import static org.tornotron.echno_backend.modules.access.api.AccessPermission.VIEW;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.ALL;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.OWN;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRole.Grant;

/**
 * The roles every organization starts with, and what each grants.
 *
 * <p>Taken from the permission mapping (echno-web {@code docs/specs/2026-10-28-permission-mapping.md},
 * "Starting permissions for the standard roles"), so a person holding a role can do on the
 * matrix what the role-name checks allow them today. Organizations then change them on the Roles
 * &amp; Permissions screen; nothing here is reapplied over their edits.
 *
 * <p>Any action implies view: granting {@code edit} on an area grants {@code view} on it with the
 * same scope.
 */
public final class StandardRoles {

    public static final String MEMBER = "member";
    public static final String SYSTEM_ADMIN = "system-admin";

    /** Every area the matrix covers, for the role that holds everything. */
    static final List<String> AREAS = AccessCatalog.areas().stream().map(AccessCatalog.Area::id).toList();

    /** The named actions on contractor bills, on top of the five common ones. */
    static final List<String> BILL_STEPS = AccessCatalog.areas().stream()
            .filter(area -> area.id().equals("progress-bills"))
            .findFirst().orElseThrow().namedActions();

    private StandardRoles() {
    }

    public static List<StandardRole> all() {
        return List.of(member(), systemAdmin(), orgManager(), hrAdmin(), projectManager(), qaEngineer(),
                safetyOfficer(), siteEngineer(), storeKeeper(), observationProducer());
    }

    static StandardRole member() {
        Grants g = new Grants()
                .add(ALL, VIEW, "projects", "wbs", "progress", "progress-bills", "tasks", "issues", "bim",
                        "construction", "toolbox-talks", "assets", "compliance", "documents", "team")
                .add(ALL, CREATE, "chat")
                .locked(OWN, CREATE, "leave", "leave-regularisation");
        return new StandardRole(MEMBER, "Member",
                "What everyone in the organization holds: company-wide reads, chat, and their own leave "
                        + "and attendance.",
                null, true, g.list(), List.of());
    }

    static StandardRole systemAdmin() {
        Grants g = new Grants();
        for (String area : AREAS) {
            g.add(ALL, CREATE, area).add(ALL, EDIT, area).add(ALL, APPROVE, area).add(ALL, DELETE, area);
        }
        BILL_STEPS.forEach(step -> g.add(ALL, step, "progress-bills"));
        return new StandardRole(SYSTEM_ADMIN, "Company Administrator",
                "Full control of the organization, including who may do what.",
                SYSTEM_ADMIN, false, g.list(),
                List.of(SYSTEM_ADMIN, "org-manager", "hr-admin", "project-manager", "qa-engineer",
                        "safety-officer", "site-engineer", "store-keeper", "observation-producer"));
    }

    static StandardRole orgManager() {
        return new StandardRole("org-manager", "Organisation Manager",
                "May be named as someone's manager. Holds nothing beyond what every member does.",
                "org-manager", false, List.of(), List.of());
    }

    static StandardRole hrAdmin() {
        Grants g = new Grants()
                .add(ALL, CREATE, "team", "leave", "construction")
                .add(ALL, EDIT, "team", "leave", "construction", "org-admin", "access")
                .add(ALL, DELETE, "team", "leave", "construction")
                .add(ALL, VIEW, "documents");
        return new StandardRole("hr-admin", "HR Administrator",
                "Staff records, attendance, leave and holidays, and giving out ordinary roles.",
                "hr-admin", false, g.list(),
                List.of("org-manager", "project-manager", "qa-engineer", "safety-officer", "site-engineer",
                        "store-keeper"));
    }

    static StandardRole projectManager() {
        Grants g = new Grants()
                .add(ALL, CREATE, "projects", "wbs", "tasks", "issues", "assets", "toolbox-talks", "construction",
                        "progress", "bim", "inspections")
                .add(ALL, EDIT, "projects", "wbs", "tasks", "issues", "assets", "toolbox-talks", "construction",
                        "progress", "progress-bills")
                .add(ALL, DELETE, "projects", "wbs", "tasks", "issues", "assets", "toolbox-talks", "construction")
                .add(ALL, APPROVE, "progress-bills", "bim", "compliance", "inventory")
                .add(ALL, CREATE, "inventory", "finance")
                .add(ALL, EDIT, "inventory", "finance")
                .add(ALL, APPROVE, "finance")
                .add(ALL, DELETE, "inventory", "finance")
                .add(ALL, VIEW, "material-consumption", "storage-locations", "indents", "purchase-orders",
                        "goods-receipts", "documents", "team");
        BILL_STEPS.forEach(step -> g.add(ALL, step, "progress-bills"));
        return new StandardRole("project-manager", "Project Manager",
                "Runs projects: plans, schedules, progress, contractor bills and project costs.",
                "project-manager", false, g.list(), List.of());
    }

    static StandardRole qaEngineer() {
        Grants g = new Grants().add(ALL, CREATE, "inspections").add(ALL, EDIT, "inspections")
                .add(ALL, APPROVE, "inspections");
        return new StandardRole("qa-engineer", "Quality Engineer",
                "Quality inspections and checklists, quality defects and their sign-off.",
                "qa-engineer", false, g.list(), List.of());
    }

    static StandardRole safetyOfficer() {
        Grants g = new Grants()
                .add(ALL, CREATE, "toolbox-talks", "inspections")
                .add(ALL, EDIT, "toolbox-talks")
                .add(ALL, DELETE, "toolbox-talks")
                .add(ALL, APPROVE, "inspections");
        return new StandardRole("safety-officer", "Safety Officer",
                "Safety inspections, safety defects and their sign-off, and toolbox talks.",
                "safety-officer", false, g.list(), List.of());
    }

    static StandardRole siteEngineer() {
        Grants g = new Grants()
                .add(ALL, CREATE, "progress", "inspections")
                .add(ALL, EDIT, "progress")
                .add(ALL, "prepare", "progress-bills")
                .add(ALL, "verify", "progress-bills");
        return new StandardRole("site-engineer", "Site Engineer",
                "Records work progress, prepares and verifies contractor bills, and reports defect fixes.",
                "site-engineer", false, g.list(), List.of());
    }

    static StandardRole storeKeeper() {
        Grants g = new Grants()
                .add(ALL, CREATE, "goods-receipts", "inventory", "material-consumption")
                .add(ALL, EDIT, "goods-receipts", "inventory")
                .add(ALL, VIEW, "indents", "purchase-orders", "storage-locations", "vendors");
        return new StandardRole("store-keeper", "Store Keeper",
                "Runs the store: receives goods, issues materials, moves and counts stock.",
                "store-keeper", false, g.list(), List.of());
    }

    static StandardRole observationProducer() {
        Grants g = new Grants().add(ALL, CREATE, "inspections");
        return new StandardRole("observation-producer", "Automated Inspection Device",
                "A machine login, such as a camera or sensor, that sends in observations for a person to review.",
                "observation-producer", false, g.list(), List.of());
    }

    /** Collects grants, adding view alongside every other action, without repeats. */
    private static final class Grants {

        private final Set<Grant> grants = new LinkedHashSet<>();

        Grants add(AccessScope scope, String action, String... areas) {
            return put(scope, action, false, areas);
        }

        Grants locked(AccessScope scope, String action, String... areas) {
            return put(scope, action, true, areas);
        }

        private Grants put(AccessScope scope, String action, boolean locked, String... areas) {
            for (String area : areas) {
                grants.add(new Grant(new AccessPermission(area, VIEW), scope, locked));
                if (!VIEW.equals(action)) {
                    grants.add(new Grant(new AccessPermission(area, action), scope, locked));
                }
            }
            return this;
        }

        List<Grant> list() {
            return List.copyOf(grants);
        }
    }
}
