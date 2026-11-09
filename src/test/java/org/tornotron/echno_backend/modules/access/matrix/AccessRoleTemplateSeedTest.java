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
    private static final Set<String> NAMED_READS = Set.of("read-financials");

    private static final Path MAPPING = Path.of("docs/specs/2026-10-30-access-permission-mapping-endpoints.csv");
    private static final String BASELINE = "employee";

    /** Every CSV a changeset loads into access_role_template_grant, in changeset order. */
    private static final List<String> GRANT_FILES = List.of(
            "db/data/access/role-template-grants.csv",
            "db/data/access/role-template-grants-purchasing.csv",
            "db/data/access/role-template-grants-payments.csv",
            "db/data/access/role-template-grants-finance-areas.csv",
            "db/data/access/role-template-grants-vendor-financials.csv");

    /**
     * Template grants a later changeset deleted, by template slug and key: access-011 takes the
     * baseline's finance:read, which it held only for expenses and receipts, now an area of their own.
     */
    private static final Map<String, Set<String>> DELETED = Map.of(
            BASELINE, Set.of("finance:read"));

    private static final Map<String, String> SLUG_BY_ID = new HashMap<>();
    private static final Map<String, String> LEGACY_BY_SLUG = new HashMap<>();
    private static final Set<String> SYSTEM = new HashSet<>();
    private static final Set<String> BASELINES = new HashSet<>();
    private static final Set<String> CARRIED_OVER = new HashSet<>();
    private static final Map<String, Set<String>> KEYS_BY_SLUG = new HashMap<>();

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
            }
        }
        DELETED.forEach((slug, keys) -> KEYS_BY_SLUG.get(slug).removeAll(keys));
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
