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
            // Every member reads, leaves and edits a comment, while only the system admin and project
            // manager raise or change an issue, so comments are an area of their own.
            area("issues", "Issues", "Projects"),
            area("issue-comments", "Issue Comments", "Projects"),
            area("bim", "3D Models & Site Layout", "Projects"),
            // Labour is the workforce register, which HR and the system admin keep, while every member
            // reads the subcontracts, so the two are areas of their own.
            area("construction", "Subcontracts", "Projects"),
            area("labour", "Labour", "Projects"),
            area("assets", "Assets & Equipment", "Projects"),
            area("compliance", "Risk Register", "Projects"),
            area("progress", "Progress Tracking", "Progress"),
            // The last step of a bill's chain, finance paying it: the second signature on a payment.
            new Area("progress-bills", "Contractor Bills", "Bills",
                    List.of("prepare", "verify", "certify", "authorize-payment")),
            area("inspections", "Inspections & Defects", "Inspect"),
            area("toolbox-talks", "Toolbox Talks", "Talks"),
            // Stock adjustments and the stock ledger. The ledger listed over the whole organization is
            // the store's and the system admin's, while a project manager reads the movements behind a
            // material, location, project or task, so the whole ledger is a named read.
            new Area("inventory", "Stock Adjustments & Ledger", "Stores", List.of("read-ledger")),
            // The catalogue is changed by the system admin and project manager, while the store keeper
            // also tunes the reorder thresholds at a storage location, so setting one is a named action.
            new Area("materials", "Materials", "Stores", List.of("set-thresholds")),
            // Both ends of a transfer are worked by a store; the project manager reads them.
            area("site-transfers", "Site Transfers", "Stores"),
            area("material-consumption", "Material Consumption", "Stores"),
            area("storage-locations", "Storage Locations", "Stores"),
            area("goods-receipts", "Goods Receipts", "Stores"),
            // Whoever raised a site transfer, purchase order or goods receipt asks for its reversal and may
            // withdraw the request, while only the system admin and project manager decide it, so
            // reversals are an area of their own beside the risk register's compliance.
            area("document-reversals", "Document Reversals", "Stores"),
            area("indents", "Material Requests", "Buying"),
            area("purchase-orders", "Purchase Orders", "Buying"),
            // A vendor's bank accounts, tax identifiers, payment terms and spend summary are read by the
            // system admin alone, while the store keeper reads the rest of a vendor.
            new Area("vendors", "Vendors", "Buying", List.of("read-financials")),
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
            // Every member reads, files, renames and removes an attachment, while the organization's PDF
            // report is the system admin's, the HR admin's and the project manager's, so it is a named read.
            new Area("documents", "Documents", "Docs", List.of("read-reports")),
            area("chat", "Chat", "Chat"),
            // Every member reads the organization, its switched-on modules and its plan's features, and
            // records usage, while the system admin and HR admin change its details; the rest of the
            // organization's administration is the system admin's alone, so the member side is an area
            // of its own.
            area("org-profile", "Organisation Profile", "Setup"),
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
