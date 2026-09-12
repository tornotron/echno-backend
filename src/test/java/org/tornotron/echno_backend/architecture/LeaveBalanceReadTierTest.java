package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the guard on every leave-balance endpoint, on both twins, in one table.
 *
 * <p>The pair used to carry three tiers for reads of the same data, and the tier that mattered was
 * the one an approver could not reach: after #685 a line manager could approve a leave request
 * and not read the balance the days come out of. #745 settled the reads against the approve grant
 * through {@code @leaveSecurity.canViewEmployeeBalances}, and left the ledger and the two commands
 * where they were. This test is the record of that decision in both directions: a balance read
 * dropped back to self-or-role fails here, and so does a ledger read or a command quietly moved
 * onto the wider policy.
 *
 * <p>It reads the source, the way {@code StoreKeeperRoleScopeTest} does, because the thing being
 * pinned is the {@code @PreAuthorize} string above each mapping and nothing else.
 */
class LeaveBalanceReadTierTest {

    private static final Path SOURCE_ROOT = Path.of(
            System.getProperty("main.source.root", "src/main/java"));
    private static final Path LEAVE = SOURCE_ROOT.resolve("org/tornotron/echno_backend/leave");

    private static final String APPROVE_GRANT = "@leaveSecurity.canViewEmployeeBalances(#employeeId)";
    private static final String SELF_OR_ADMIN =
            "@orgSecurity.isSelfOrHasAnyOrgRole(#employeeId, 'system-admin', 'hr-admin')";
    private static final String ADMIN_ONLY = "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')";

    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            // The balance the days come out of: readable by whoever may approve the request.
            Map.entry("LeaveBalanceController.getEmployeeBalances GET /employee/{employeeId}", APPROVE_GRANT),
            Map.entry("LeaveBalanceController.getSpecificBalance GET /employee/{employeeId}/policy/{policyId}", APPROVE_GRANT),
            Map.entry("LeaveBalanceController.getBalanceSummary GET /employee/{employeeId}/summary", APPROVE_GRANT),
            Map.entry("LeaveBalanceControllerWeb.getEmployeeBalances GET", APPROVE_GRANT),
            Map.entry("LeaveBalanceControllerWeb.getSpecificBalance GET /specific", APPROVE_GRANT),
            Map.entry("LeaveBalanceControllerWeb.getBalanceSummary GET /summary", APPROVE_GRANT),

            // The ledger behind a balance: the employee and the administrators, as before.
            Map.entry("LeaveBalanceController.getTransactionHistory GET /employee/{employeeId}/transactions", SELF_OR_ADMIN),
            Map.entry("LeaveBalanceControllerWeb.getTransactionHistory GET /transactions", SELF_OR_ADMIN),

            // Commands, and the ledger keyed by a balance id that names no person.
            Map.entry("LeaveBalanceController.recalculateBalances POST /employee/{employeeId}/recalculate", ADMIN_ONLY),
            Map.entry("LeaveBalanceController.adjustBalance POST /adjust", ADMIN_ONLY),
            Map.entry("LeaveBalanceController.getTransactionsByBalance GET /{balanceId}/transactions", ADMIN_ONLY),
            Map.entry("LeaveBalanceControllerWeb.recalculateBalances POST /recalculate", ADMIN_ONLY),
            Map.entry("LeaveBalanceControllerWeb.adjustBalance POST /adjust", ADMIN_ONLY),
            Map.entry("LeaveBalanceControllerWeb.getTransactionsByBalance GET /transactions-by-balance", ADMIN_ONLY)
    ));

    private static final Pattern PRE_AUTHORIZE = Pattern.compile("^\\s*@PreAuthorize\\(\"(.*)\"\\)\\s*$");
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*@(Get|Post|Put|Patch|Delete)Mapping\\b\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern METHOD = Pattern.compile("^\\s*(?:public|protected)\\s+.*?\\b(\\w+)\\s*\\(.*");

    private Map<String, String> guardsIn(String controller) throws IOException {
        List<String> lines = Files.readAllLines(LEAVE.resolve(controller + ".java"), StandardCharsets.UTF_8);
        Map<String, String> guards = new TreeMap<>();
        String guard = null;
        String verb = null;
        String path = null;
        for (String line : lines) {
            Matcher pre = PRE_AUTHORIZE.matcher(line);
            if (pre.matches()) {
                guard = pre.group(1);
                continue;
            }
            Matcher map = MAPPING.matcher(line);
            if (map.find()) {
                verb = map.group(1).toUpperCase();
                path = map.group(2) == null ? "" : map.group(2);
                continue;
            }
            Matcher method = METHOD.matcher(line);
            if (method.matches()) {
                if (verb != null) {
                    String endpoint = path.isEmpty() ? verb : verb + " " + path;
                    guards.put(controller + "." + method.group(1) + " " + endpoint,
                            guard == null ? "<none>" : guard);
                }
                guard = null;
                verb = null;
                path = null;
            }
        }
        return guards;
    }

    @Test
    void everyLeaveBalanceEndpointCarriesTheGuardItWasGiven() throws IOException {
        Map<String, String> actual = new TreeMap<>();
        actual.putAll(guardsIn("LeaveBalanceController"));
        actual.putAll(guardsIn("LeaveBalanceControllerWeb"));

        assertThat(actual)
                .as("guard per leave-balance mapping, both twins. A balance read that leaves the "
                        + "approve grant refuses the approver again; a ledger read or a command that "
                        + "joins it is a widening this table has to record")
                .containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }
}
