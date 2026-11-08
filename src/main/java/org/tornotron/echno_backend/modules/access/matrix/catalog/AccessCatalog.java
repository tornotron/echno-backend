package org.tornotron.echno_backend.modules.access.matrix.catalog;

import java.util.List;
import java.util.stream.Stream;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;

/**
 * Every area the matrix covers that is not declared by a module manifest, the section the Roles
 * &amp; Permissions screen lists it under, and the actions it offers.
 *
 * <p>Sections follow the roles guide's twelve areas. Each area offers the five common actions; an
 * area may add named ones, such as the steps of a contractor bill. A module's own permissions come
 * from its manifest and are added by {@link AccessCatalogReader}, which also says which of them
 * are enforced.
 */
public final class AccessCatalog {

    /** The five actions every area offers, in the order the screen shows them. */
    public static final List<String> COMMON_ACTIONS = List.of(
            AccessPermission.READ, AccessPermission.CREATE, AccessPermission.EDIT,
            AccessPermission.APPROVE, AccessPermission.DELETE);

    /** One area: its id, its label, its section, and the actions beyond the common five. */
    public record Area(String id, String label, String section, List<String> namedActions) {

        public Area {
            namedActions = List.copyOf(namedActions);
        }

        /** The common actions followed by the area's named ones. */
        public List<String> actions() {
            return Stream.concat(COMMON_ACTIONS.stream(), namedActions.stream()).toList();
        }
    }

    /** The sections, in the order the screen shows them. */
    public static final List<String> SECTIONS = List.of(
            "Projects", "Progress", "Bills", "Inspect", "Talks", "Stores", "Buying", "Money",
            "People", "Docs", "Chat", "Setup");

    private static final List<Area> AREAS = List.of(
            area("projects", "Projects & Settings", "Projects"),
            area("wbs", "WBS & Schedule", "Projects"),
            area("tasks", "Tasks", "Projects"),
            area("issues", "Issues & Comments", "Projects"),
            area("bim", "3D Models & Site Layout", "Projects"),
            area("construction", "Subcontracts & Labour", "Projects"),
            area("assets", "Assets & Equipment", "Projects"),
            area("compliance", "Risk & Compliance", "Projects"),
            area("progress", "Progress Tracking", "Progress"),
            // The last step of a bill's chain, finance paying it: the second signature on a payment.
            new Area("progress-bills", "Contractor Bills", "Bills",
                    List.of("prepare", "verify", "certify", "authorize-payment")),
            area("inspections", "Inspections & Defects", "Inspect"),
            area("toolbox-talks", "Toolbox Talks", "Talks"),
            area("inventory", "Inventory", "Stores"),
            area("material-consumption", "Material Consumption", "Stores"),
            area("storage-locations", "Storage Locations", "Stores"),
            area("goods-receipts", "Goods Receipts", "Stores"),
            area("indents", "Material Requests", "Buying"),
            area("purchase-orders", "Purchase Orders", "Buying"),
            area("vendors", "Vendors", "Buying"),
            // Expenses and receipts are read by every member, the rest of finance by the pair, so they
            // are an area of their own; payables are the system admin's alone. Configure is the
            // system admin's part of finance: the chart's import and defaults, the posting mappings
            // and the finance settings.
            area("expenses", "Expenses & Receipts", "Money"),
            new Area("finance", "Finance", "Money", List.of("configure")),
            area("payables", "Payables", "Money"),
            area("team", "Team & Attendance", "People"),
            area("leave", "Leave", "People"),
            area("leave-regularisation", "Attendance Regularisation", "People"),
            area("documents", "Documents", "Docs"),
            area("chat", "Chat", "Chat"),
            area("org-admin", "Organisation Settings", "Setup"),
            area("access", "Roles & Permissions", "Setup"));

    private AccessCatalog() {
    }

    public static List<Area> areas() {
        return AREAS;
    }

    private static Area area(String id, String label, String section) {
        return new Area(id, label, section, List.of());
    }
}
