package org.tornotron.echno_backend.modules.access.matrix.catalog;

import java.util.List;
import java.util.stream.Stream;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;

/**
 * Every area the matrix covers, the section the Roles &amp; Permissions screen lists it under, and
 * the actions it offers.
 *
 * <p>The one list the screen, the starting roles and the checks agree on. Sections follow the
 * roles guide's twelve areas. Each area offers the five common actions; an area may add named
 * ones, such as the steps of a contractor bill.
 */
public final class AccessCatalog {

    /** The five actions every area offers, in the order the screen shows them. */
    public static final List<String> COMMON_ACTIONS = List.of(
            AccessPermission.VIEW, AccessPermission.CREATE, AccessPermission.EDIT,
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
            new Area("progress-bills", "Contractor Bills", "Bills", List.of("prepare", "verify", "certify")),
            area("inspections", "Inspections & Defects", "Inspect"),
            area("toolbox-talks", "Toolbox Talks", "Talks"),
            area("inventory", "Inventory", "Stores"),
            area("material-consumption", "Material Consumption", "Stores"),
            area("storage-locations", "Storage Locations", "Stores"),
            area("goods-receipts", "Goods Receipts", "Stores"),
            area("indents", "Material Requests", "Buying"),
            area("purchase-orders", "Purchase Orders", "Buying"),
            area("vendors", "Vendors", "Buying"),
            area("finance", "Finance", "Money"),
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

    /** Whether the permission names an area here and an action that area offers. */
    public static boolean offers(AccessPermission permission) {
        return AREAS.stream().anyMatch(area -> area.id().equals(permission.area())
                && area.actions().contains(permission.action()));
    }

    private static Area area(String id, String label, String section) {
        return new Area(id, label, section, List.of());
    }
}
