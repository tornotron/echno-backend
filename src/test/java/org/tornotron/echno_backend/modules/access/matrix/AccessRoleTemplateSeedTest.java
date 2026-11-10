package org.tornotron.echno_backend.modules.access.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * Fails the build on a seed that breaks a rule about the starting roles. The cells are data, so this
 * holds whatever they change to.
 */
class AccessRoleTemplateSeedTest {

    /** A mapping row's read that needs more than the area's read: its named action is checked as is. */
    private static final Set<String> NAMED_READS = Set.of("read-financials", "read-ledger", "read-reports",
            "generate-compliance", "export-datasets", "read-reporting-lines");

    private static final Path MAPPING = Path.of("docs/specs/2026-10-30-access-permission-mapping-endpoints.csv");
    private static final String BASELINE = "employee";

    /** Every CSV a changeset loads into access_role_template_grant, in changeset order. */
    private static final List<String> GRANT_FILES = List.of(
            "db/data/access/role-template-grants.csv",
            "db/data/access/role-template-grants-purchasing.csv",
            "db/data/access/role-template-grants-payments.csv",
            "db/data/access/role-template-grants-finance-areas.csv",
            "db/data/access/role-template-grants-vendor-financials.csv",
            "db/data/access/role-template-grants-labour.csv",
            "db/data/access/role-template-grants-issue-comments.csv",
            "db/data/access/role-template-grants-stores.csv",
            "db/data/access/role-template-grants-document-reversals.csv",
            "db/data/access/role-template-grants-documents-reports.csv",
            "db/data/access/role-template-grants-org-profile.csv",
            "db/data/access/role-template-grants-inspections.csv",
            "db/data/access/role-template-grants-leave.csv",
            "db/data/access/role-template-grants-working-time.csv",
            "db/data/access/role-template-grants-team.csv",
            "db/data/access/role-template-grants-attendance.csv");

    /**
     * Template grants a later changeset deleted, by template slug and key: access-011 takes the
     * baseline's finance:read, which it held only for expenses and receipts, now an area of their own;
     * access-013 takes the HR admin's construction writes, which it held only for labour, now an area of
     * its own; access-014 takes the baseline's issues:create and issues:edit, which it held only for
     * comments, now an area of their own; access-016 takes the baseline's compliance:approve, which it
     * held only for document reversals, now an area of their own; access-018 takes the baseline's
     * org-admin:read and org-admin:create and the HR admin's org-admin:edit, which they held only for
     * the organization's profile, now an area of their own; access-019 takes the site engineer's and the
     * observation producer's inspections:create, which would have let them schedule and record an
     * inspection; access-022 takes the project manager's team:edit, which no employee handler asks for.
     */
    private static final Map<String, Set<String>> DELETED = Map.of(
            BASELINE, Set.of("finance:read", "issues:create", "issues:edit", "compliance:approve",
                    "org-admin:read", "org-admin:create"),
            "hr-admin", Set.of("construction:create", "construction:edit", "construction:delete", "org-admin:edit"),
            "site-engineer", Set.of("inspections:create"),
            "project-manager", Set.of("team:edit"),
            "observation-producer", Set.of("inspections:create"));

    private static final Map<String, String> SLUG_BY_ID = new HashMap<>();
    private static final Map<String, String> LEGACY_BY_SLUG = new HashMap<>();
    private static final Set<String> SYSTEM = new HashSet<>();
    private static final Set<String> BASELINES = new HashSet<>();
    private static final Set<String> CARRIED_OVER = new HashSet<>();
    private static final Map<String, Set<String>> KEYS_BY_SLUG = new HashMap<>();

    /**
     * Template grants a later changeset narrowed in place, by template slug, key and the scope it
     * left: access-022 narrows the baseline's team:read and team:create to the employee's own record, and
     * access-023 its team:approve.
     */
    private static final Map<String, Map<String, String>> RESCOPED = Map.of(BASELINE,
            Map.of("team:read", "OWN", "team:create", "OWN", "team:approve", "OWN"));

    /** The scope each template holds each key at, after {@link #RESCOPED}. */
    private static final Map<String, Map<String, String>> SCOPE_BY_SLUG = new HashMap<>();

    @BeforeAll
    static void readTheSeed() throws IOException {
        for (String[] row : rows("db/data/access/role-templates.csv")) {
            SLUG_BY_ID.put(row[0], row[1]);
            // Liquibase reads NULL in a CSV as a null column, where an empty field would be an empty string.
            LEGACY_BY_SLUG.put(row[1], row[6].equals("NULL") ? "" : row[6]);
            if (Boolean.parseBoolean(row[4])) {
                SYSTEM.add(row[1]);
            }
            if (Boolean.parseBoolean(row[5])) {
                BASELINES.add(row[1]);
            }
            if (Boolean.parseBoolean(row[7])) {
                CARRIED_OVER.add(row[1]);
            }
            KEYS_BY_SLUG.put(row[1], new HashSet<>());
        }
        for (String file : GRANT_FILES) {
            for (String[] row : rows(file)) {
                KEYS_BY_SLUG.get(SLUG_BY_ID.get(row[1])).add(row[2]);
                SCOPE_BY_SLUG.computeIfAbsent(SLUG_BY_ID.get(row[1]), slug -> new HashMap<>()).put(row[2], row[3]);
            }
        }
        DELETED.forEach((slug, keys) -> KEYS_BY_SLUG.get(slug).removeAll(keys));
        RESCOPED.forEach((slug, scopes) -> SCOPE_BY_SLUG.get(slug).putAll(scopes));
    }

    private static List<String[]> rows(String resource) throws IOException {
        List<String> lines = List.of(new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8).split("\n"));
        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            rows.add(line.split(",", -1));
        }
        return rows;
    }

    private static Set<String> held(String slug) {
        Set<String> keys = new HashSet<>(KEYS_BY_SLUG.get(BASELINE));
        keys.addAll(KEYS_BY_SLUG.get(slug));
        return keys;
    }

    @Test
    void theBaselineNeverHoldsAnAccessPermission() {
        assertThat(KEYS_BY_SLUG.get(BASELINE)).noneMatch(key -> key.startsWith("access:"));
    }

    @Test
    void onlySystemAdminStartsWithAccessPermissions() {
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin")) {
                assertThat(keys).as(slug).noneMatch(key -> key.startsWith("access:"));
            }
        });
        assertThat(KEYS_BY_SLUG.get("system-admin"))
                .contains("access:read", "access:edit", "access:edit-employee-role");
    }

    @Test
    void onlyTheAdministratorAndTheBaselineAreSystemRoles() {
        assertThat(SYSTEM).containsExactlyInAnyOrder("system-admin", BASELINE);
        assertThat(BASELINES).containsExactly(BASELINE);
    }

    @Test
    void theRolesWithNoCounterpartHoldOnlyTheBaseline() {
        for (String slug : List.of("management-director", "procurement-manager", "client-owner")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug).isEmpty();
            assertThat(LEGACY_BY_SLUG.get(slug)).as(slug).isEmpty();
        }
        assertThat(LEGACY_BY_SLUG.get("finance-manager")).isEmpty();
    }

    @Test
    void financeAuthorizesBillPaymentsAndTheSystemAdminDoesNot() {
        // The product owner's decision of 8 Nov: finance, or the person controlling the project,
        // authorizes a contractor bill payment. The second of those is a project's named authorizer,
        // which is a record, not a grant.
        assertThat(KEYS_BY_SLUG.get("finance-manager")).containsExactly("progress-bills:authorize-payment");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("finance-manager")) {
                assertThat(keys).as(slug).doesNotContain("progress-bills:authorize-payment");
            }
        });
    }

    @Test
    void theBaselineReadsExpensesAndReceiptsAndNoOtherFinance() {
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("expenses:read")
                .noneMatch(key -> key.startsWith("finance:") || key.startsWith("payables:"));
    }

    @Test
    void onlyTheSystemAdminConfiguresFinanceAndHoldsPayables() {
        // Finance's system-admin-only writes and the web payables were the system admin's alone before
        // the matrix, and stay so: the project manager keeps every other finance write.
        assertThat(KEYS_BY_SLUG.get("system-admin"))
                .contains("finance:configure", "payables:read", "payables:create", "payables:edit");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin")) {
                assertThat(keys).as(slug).doesNotContain("finance:configure")
                        .noneMatch(key -> key.startsWith("payables:"));
            }
        });
        assertThat(KEYS_BY_SLUG.get("project-manager"))
                .contains("finance:read", "finance:create", "finance:edit", "finance:delete", "finance:approve");
    }

    @Test
    void onlyTheSystemAdminReadsAVendorsFinancials() {
        // Bank accounts, tax identifiers, payment terms and the spend summary were the system admin's
        // alone before the matrix; the store keeper reads the rest of a vendor.
        assertThat(KEYS_BY_SLUG.get("system-admin")).contains("vendors:read-financials");
        assertThat(KEYS_BY_SLUG.get("store-keeper")).contains("vendors:read")
                .doesNotContain("vendors:read-financials");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin")) {
                assertThat(keys).as(slug).doesNotContain("vendors:read-financials");
            }
        });
    }

    @Test
    void onlyTheSystemAdminTheHrAdminAndTheProjectManagerReadTheReport() {
        // The organization's PDF report was those three roles' alone before the matrix, while every
        // member reads, files, renames and removes attachments.
        Set<String> readers = Set.of("system-admin", "hr-admin", "project-manager");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (readers.contains(slug)) {
                assertThat(keys).as(slug).contains("documents:read-reports");
            } else {
                assertThat(keys).as(slug).doesNotContain("documents:read-reports");
            }
        });
        assertThat(KEYS_BY_SLUG.get(BASELINE))
                .contains("documents:read", "documents:create", "documents:edit", "documents:delete");
    }

    @Test
    void onlyTheSystemAdminAndTheHrAdminHoldLabour() {
        // Labour was the system admin's and the HR admin's alone before the matrix, reads included,
        // while every member reads the subcontracts and the system admin and project manager change
        // them. The HR admin keeps no subcontract write.
        Set<String> labour = Set.of("labour:read", "labour:create", "labour:edit", "labour:delete");
        assertThat(KEYS_BY_SLUG.get("system-admin")).containsAll(labour);
        assertThat(KEYS_BY_SLUG.get("hr-admin")).containsAll(labour)
                .noneMatch(key -> key.startsWith("construction:"));
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin") && !slug.equals("hr-admin")) {
                assertThat(keys).as(slug).noneMatch(key -> key.startsWith("labour:"));
            }
        });
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("construction:read");
        assertThat(KEYS_BY_SLUG.get("project-manager"))
                .contains("construction:create", "construction:edit", "construction:delete");
    }

    @Test
    void theStoresAreasKeepWhatEachRoleHadBeforeTheMatrix() {
        // Before the matrix the system admin, project manager and store keeper read every stores
        // record. The pair changed the catalogue and only the system admin deleted from it, while all
        // three set reorder thresholds. Only the system admin and store keeper moved a site transfer
        // or listed the ledger over the whole organization.
        assertThat(KEYS_BY_SLUG.get("system-admin")).contains("inventory:read-ledger",
                "materials:read", "materials:create", "materials:edit", "materials:delete",
                "materials:set-thresholds", "site-transfers:read", "site-transfers:create",
                "site-transfers:edit");
        assertThat(KEYS_BY_SLUG.get("project-manager"))
                .contains("materials:read", "materials:create", "materials:edit", "materials:set-thresholds",
                        "site-transfers:read")
                .doesNotContain("inventory:read-ledger", "materials:delete", "site-transfers:create",
                        "site-transfers:edit");
        assertThat(KEYS_BY_SLUG.get("store-keeper"))
                .contains("inventory:read-ledger", "materials:read", "materials:set-thresholds",
                        "site-transfers:read", "site-transfers:create", "site-transfers:edit")
                .doesNotContain("inventory:approve", "inventory:delete",
                        "materials:create", "materials:edit", "materials:delete", "site-transfers:delete");
        Set<String> stores = Set.of("system-admin", "project-manager", "store-keeper");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!stores.contains(slug)) {
                assertThat(keys).as(slug).noneMatch(key -> key.startsWith("inventory:")
                        || key.startsWith("materials:") || key.startsWith("site-transfers:"));
            }
        });
    }

    @Test
    void everyMemberAsksForAReversalAndOnlyThePairDecidesOneOrKeepsTheRiskRegister() {
        // Before the matrix every member read the risk register and the reversals, asked for a
        // reversal and withdrew their own, while only the system admin and project manager recorded,
        // changed or removed a risk, or approved or rejected a reversal.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("compliance:read", "document-reversals:read",
                "document-reversals:create", "document-reversals:edit")
                .doesNotContain("compliance:create", "compliance:edit", "compliance:delete",
                        "compliance:approve", "document-reversals:approve");
        Set<String> pair = Set.of("system-admin", "project-manager");
        for (String slug : pair) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug).contains("compliance:create", "compliance:edit",
                    "compliance:delete", "document-reversals:approve");
        }
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!pair.contains(slug)) {
                assertThat(keys).as(slug).doesNotContain("compliance:create", "compliance:edit",
                        "compliance:delete", "compliance:approve", "document-reversals:approve");
            }
        });
    }

    @Test
    void everyMemberCommentsOnAnIssueAndOnlyThePairRaisesOne() {
        // Before the matrix every member read issues and read, left and edited comments, while only
        // the system admin and project manager raised, edited or deleted an issue, or deleted a comment.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("issues:read",
                "issue-comments:read", "issue-comments:create", "issue-comments:edit")
                .doesNotContain("issues:create", "issues:edit", "issues:delete", "issue-comments:delete");
        for (String slug : List.of("system-admin", "project-manager")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug)
                    .contains("issues:create", "issues:edit", "issues:delete", "issue-comments:delete");
        }
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin") && !slug.equals("project-manager")) {
                assertThat(keys).as(slug).doesNotContain(
                        "issues:create", "issues:edit", "issues:delete", "issue-comments:delete");
            }
        });
    }

    @Test
    void everyMemberReadsTheOrganizationAndOnlyTheSystemAdminAdministersIt() {
        // Before the matrix every member read the organization, its modules and its plan's features,
        // and recorded usage; the system admin and HR admin changed its details; everything else in
        // the organization's administration was the system admin's alone.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("org-profile:read", "org-profile:create")
                .noneMatch(key -> key.startsWith("org-admin:"));
        for (String slug : List.of("system-admin", "hr-admin")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug).contains("org-profile:edit");
        }
        assertThat(KEYS_BY_SLUG.get("system-admin")).contains("org-admin:read", "org-admin:create",
                "org-admin:edit", "org-admin:approve", "org-admin:delete");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin")) {
                assertThat(keys).as(slug).noneMatch(key -> key.startsWith("org-admin:"));
            }
            if (!slug.equals("system-admin") && !slug.equals("hr-admin")) {
                assertThat(keys).as(slug).doesNotContain("org-profile:edit", "org-profile:delete");
            }
        });
    }

    @Test
    void eachInspectionJobKeepsItsOwnRoles() {
        // Before the matrix each inspection job had its own role list, and the sign-offs went by the
        // report's discipline.
        Map<String, Set<String>> holders = Map.ofEntries(
                Map.entry("inspections:read", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer", "site-engineer")),
                Map.entry("inspections:create", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer")),
                Map.entry("inspections:edit", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer")),
                Map.entry("inspections:approve", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer")),
                Map.entry("inspections:generate-compliance", Set.of("system-admin", "project-manager")),
                Map.entry("inspections:export-datasets", Set.of("system-admin")),
                Map.entry("inspection-checklists:read", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer", "site-engineer")),
                Map.entry("inspection-checklists:create", Set.of("system-admin", "qa-qc-engineer")),
                Map.entry("inspection-checklists:edit", Set.of("system-admin", "qa-qc-engineer")),
                Map.entry("ncrs:read", Set.of("system-admin", "project-manager", "qa-qc-engineer",
                        "safety-officer", "site-engineer")),
                Map.entry("ncrs:create", Set.of("system-admin", "qa-qc-engineer", "safety-officer")),
                Map.entry("ncrs:edit", Set.of("system-admin", "qa-qc-engineer", "safety-officer")),
                Map.entry("ncrs:report-corrective-action", Set.of("system-admin", "site-engineer")),
                Map.entry("ncrs:sign-off-quality", Set.of("system-admin", "qa-qc-engineer")),
                Map.entry("ncrs:sign-off-safety", Set.of("system-admin", "safety-officer")));
        holders.forEach((key, slugs) -> KEYS_BY_SLUG.forEach((slug, keys) ->
                assertThat(keys.contains(key)).as(slug + " " + key).isEqualTo(slugs.contains(slug))));
        KEYS_BY_SLUG.forEach((slug, keys) -> assertThat(keys).as(slug)
                .allMatch(key -> !key.startsWith("inspections:") && !key.startsWith("inspection-checklists:")
                        && !key.startsWith("ncrs:") || holders.containsKey(key)));
    }

    @Test
    void onlyTheSystemAdminAndTheHrAdminKeepLeaveBalancesAndPolicies() {
        // Before the matrix the system admin and HR admin recalculated and adjusted balances, and read
        // and kept the policies, while only the system admin created one.
        for (String slug : List.of("system-admin", "hr-admin")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug).contains("leave:adjust-balances",
                    "leave-policies:read", "leave-policies:maintain");
        }
        assertThat(KEYS_BY_SLUG.get("system-admin")).contains("leave-policies:create");
        assertThat(KEYS_BY_SLUG.get("hr-admin")).doesNotContain("leave-policies:create");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin") && !slug.equals("hr-admin")) {
                assertThat(keys).as(slug).doesNotContain("leave:adjust-balances")
                        .noneMatch(key -> key.startsWith("leave-policies:"));
            }
        });
    }

    @Test
    void everyMemberReadsTheWorkingCalendarAndOnlyTheSystemAdminAndTheHrAdminChangeIt() {
        // Before the matrix every member read holidays, the working week, attendance settings and
        // shift timings, and only the system admin and HR admin changed them.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("working-time:read");
        for (String slug : List.of("system-admin", "hr-admin")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug)
                    .contains("working-time:create", "working-time:edit", "working-time:delete");
        }
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!slug.equals("system-admin") && !slug.equals("hr-admin")) {
                assertThat(keys).as(slug).doesNotContain("working-time:create", "working-time:edit",
                        "working-time:delete", "working-time:approve");
            }
        });
    }

    @Test
    void everyMemberReachesTheirOwnRecordAndOnlyThePersonnelRolesReachEveryones() {
        // Before the matrix every member read and edited their own employee record and saw who reports
        // to them; the system admin, HR admin and project manager read every record; and only the system
        // admin and HR admin changed or deleted one, or read anyone's reports.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("team:read", "team:edit", "team:read-reporting-lines")
                .doesNotContain("team:delete");
        for (String key : List.of("team:read", "team:create", "team:edit", "team:read-reporting-lines")) {
            assertThat(SCOPE_BY_SLUG.get(BASELINE).get(key)).as(key).isEqualTo("OWN");
        }
        for (String slug : List.of("system-admin", "hr-admin")) {
            assertThat(KEYS_BY_SLUG.get(slug)).as(slug)
                    .contains("team:read", "team:edit", "team:delete", "team:read-reporting-lines");
            for (String key : List.of("team:read", "team:create", "team:edit", "team:delete",
                    "team:read-reporting-lines")) {
                assertThat(SCOPE_BY_SLUG.get(slug).get(key)).as(slug + " " + key).isEqualTo("ALL");
            }
        }
        assertThat(KEYS_BY_SLUG.get("project-manager")).contains("team:read")
                .doesNotContain("team:edit", "team:delete", "team:read-reporting-lines");
        assertThat(SCOPE_BY_SLUG.get("project-manager").get("team:read")).isEqualTo("ALL");
        KEYS_BY_SLUG.forEach((slug, keys) -> {
            if (!List.of(BASELINE, "system-admin", "hr-admin", "project-manager").contains(slug)) {
                assertThat(keys).as(slug).noneMatch(key -> key.startsWith("team:"));
            }
        });
    }

    @Test
    void everyMemberKeepsTheirOwnAttendanceAndTheRecordManagersKeepEveryones() {
        // Before the matrix every member recorded and read their own attendance and asked to decide the
        // records naming them as approver, and the system admin, HR admin and project manager (the
        // record-management roles) marked absences, read a project's records, decided and verified
        // anyone's, and deleted a record. Deleting an employee stays the system admin's and HR admin's.
        assertThat(KEYS_BY_SLUG.get(BASELINE)).contains("team:create", "team:approve")
                .doesNotContain("team:delete-attendance");
        assertThat(SCOPE_BY_SLUG.get(BASELINE).get("team:approve")).isEqualTo("OWN");
        for (String slug : List.of("system-admin", "hr-admin", "project-manager")) {
            for (String key : List.of("team:read", "team:create", "team:approve", "team:delete-attendance")) {
                assertThat(KEYS_BY_SLUG.get(slug)).as(slug).contains(key);
                assertThat(SCOPE_BY_SLUG.get(slug).get(key)).as(slug + " " + key).isEqualTo("ALL");
            }
        }
        assertThat(KEYS_BY_SLUG.get("project-manager")).doesNotContain("team:delete");
    }

    @Test
    void everyGrantIdIsUniqueAcrossTheFiles() throws IOException {
        Set<String> ids = new HashSet<>();
        for (String file : GRANT_FILES) {
            for (String[] row : rows(file)) {
                assertThat(ids.add(row[0])).as(file + " " + row[0]).isTrue();
            }
        }
    }

    @Test
    void theFourOldRolesAreCarriedOver() {
        assertThat(CARRIED_OVER).containsExactlyInAnyOrder("hr-admin", "safety-officer", "org-manager", "observation-producer");
    }

    @Test
    void everyOldRoleHasExactlyOneTemplate() {
        for (OrgRole role : OrgRole.values()) {
            assertThat(LEGACY_BY_SLUG.values()).as(role.name()).containsOnlyOnce(role.getGroupName());
        }
    }

    @Test
    void editCoversCreate() {
        KEYS_BY_SLUG.forEach((slug, keys) -> keys.stream()
                // The access area offers no create of its own: its edit is the screen's.
                .filter(key -> key.endsWith(":edit") && !key.startsWith("access:"))
                .map(key -> key.replace(":edit", ":create"))
                .forEach(create -> assertThat(held(slug)).as(slug).contains(create)));
    }

    @Test
    void aRoleWithNoCounterpartSaysSoWithANull() throws IOException {
        for (String[] row : rows("db/data/access/role-templates.csv")) {
            assertThat(row[6]).as(row[1]).isNotEmpty();
        }
    }

    @Test
    void everyKeyIsAnAreaAndAnAction() {
        KEYS_BY_SLUG.forEach((slug, keys) -> assertThat(keys).as(slug).allMatch(
                key -> key.matches("[a-z-]+:[a-z-]+")));
    }

    @Test
    void everyRoleKeepsWhatTodaysChecksGiveIt() throws IOException {
        for (String[] row : mapping()) {
            if (!row[4].equals("role-names") || isPlatform(row)) {
                continue;
            }
            String action = row[1].equals("GET") && !NAMED_READS.contains(row[8]) ? "read" : row[8];
            for (String legacy : row[5].split(";")) {
                String slug = slugOf(legacy.trim());
                assertThat(held(slug)).as(slug + " " + row[1] + " " + row[2]).contains(row[7] + ":" + action);
            }
        }
    }

    @Test
    void theBaselineKeepsWhatAnyMemberReachesToday() throws IOException {
        for (String[] row : mapping()) {
            if (row[4].equals("any-member") && !isPlatform(row)) {
                String action = row[1].equals("GET") ? "read" : row[8];
                assertThat(KEYS_BY_SLUG.get(BASELINE)).as(row[1] + " " + row[2]).contains(row[7] + ":" + action);
            }
        }
    }

    /** User profile, search and the Keycloak admin endpoints are not a row of the matrix. */
    private static boolean isPlatform(String[] row) {
        return row[7].equals("platform");
    }

    private static String slugOf(String legacy) {
        return LEGACY_BY_SLUG.entrySet().stream()
                .filter(entry -> entry.getValue().equals(legacy))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    /** The mapping's rows. No field before {@code file} holds a comma or a quote. */
    private static List<String[]> mapping() throws IOException {
        List<String> lines = Files.readAllLines(MAPPING);
        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            rows.add(line.split(",", 10));
        }
        return rows;
    }
}
