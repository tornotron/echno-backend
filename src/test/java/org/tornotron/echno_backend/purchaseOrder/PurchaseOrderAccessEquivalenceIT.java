package org.tornotron.echno_backend.purchaseOrder;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.project.Project;

/**
 * Moving the purchase order handlers onto the access matrix changes nobody's access (design note
 * 2.5) beyond the gains recorded in {@link #DELIBERATE_GAINS}: for every handler, a starting role
 * reaches it exactly when its old role did, and a role with no old counterpart, like the baseline
 * alone, reaches exactly what any member did, which for these handlers is nothing.
 *
 * <p>Each deliberate gain names the handler, the role that gains it and the decision behind it, and
 * must be a real gain: a recorded exception the old guard already allowed fails, as does any
 * difference that is not recorded.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The
 * old answer is worked out from that row, and the new one from the engine on the seeded starting
 * roles, both for the area and for an order on a project.
 */
class PurchaseOrderAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> STORES_TIER = Set.of("system-admin", "store-keeper", "project-manager");
    private static final Set<String> SYSTEM_ADMIN = Set.of("system-admin");
    private static final Set<String> STORES_READ = Set.of("system-admin", "store-keeper");

    private static final String PROJECT_MANAGER = "project-manager";
    private static final String PRODUCT_OWNER_DECISION = "product owner decision, #914";

    /** Each handler's guard before the move: the old roles it admitted. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("PurchaseOrderControllerWeb.createPurchaseOrder", SYSTEM_ADMIN),
            entry("PurchaseOrderControllerWeb.updatePurchaseOrder", SYSTEM_ADMIN),
            entry("PurchaseOrderController.createPurchaseOrder", SYSTEM_ADMIN),
            entry("PurchaseOrderController.updatePurchaseOrder", SYSTEM_ADMIN),
            entry("PurchaseOrderItemControllerWeb.createPurchaseOrderItem", SYSTEM_ADMIN),
            entry("PurchaseOrderItemControllerWeb.updatePurchaseOrderItem", SYSTEM_ADMIN),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemById", STORES_READ),
            entry("PurchaseOrderItemControllerWeb.getAllPurchaseOrderItems", STORES_READ),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemsPaginated", STORES_READ),
            entry("PurchaseOrderItemControllerWeb.getItemsByPurchaseOrderId", STORES_READ),
            entry("PurchaseOrderItemControllerWeb.getItemsByMaterialId", STORES_READ),
            entry("PurchaseOrderItemController.createPurchaseOrderItem", SYSTEM_ADMIN),
            entry("PurchaseOrderItemController.updatePurchaseOrderItem", SYSTEM_ADMIN),
            entry("PurchaseOrderItemController.getPurchaseOrderItemById", STORES_READ),
            entry("PurchaseOrderItemController.getAllPurchaseOrderItems", STORES_READ),
            entry("PurchaseOrderItemController.getPurchaseOrderItemsPaginated", STORES_READ),
            entry("PurchaseOrderItemController.getItemsByPurchaseOrderId", STORES_READ),
            entry("PurchaseOrderItemController.getItemsByMaterialId", STORES_READ),
            entry("PurchaseOrderControllerWeb.getPurchaseOrderById", STORES_TIER),
            entry("PurchaseOrderControllerWeb.readStatusHistory", STORES_TIER),
            entry("PurchaseOrderControllerWeb.getAllPurchaseOrders", STORES_TIER),
            entry("PurchaseOrderControllerWeb.getAllPurchaseOrdersPaginated", STORES_TIER),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByVendor", STORES_TIER),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByIndent", STORES_TIER),
            entry("PurchaseOrderControllerWeb.getPurchaseOrdersByStatus", STORES_TIER),
            entry("PurchaseOrderControllerWeb.updatePurchaseOrderStatus", SYSTEM_ADMIN),
            entry("PurchaseOrderController.getPurchaseOrderById", STORES_TIER),
            entry("PurchaseOrderController.getAllPurchaseOrders", STORES_TIER),
            entry("PurchaseOrderController.getAllPurchaseOrdersPaginated", STORES_TIER),
            entry("PurchaseOrderController.getPurchaseOrdersByVendor", STORES_TIER),
            entry("PurchaseOrderController.getPurchaseOrdersByIndent", STORES_TIER),
            entry("PurchaseOrderController.getPurchaseOrdersByStatus", STORES_TIER),
            entry("PurchaseOrderController.updatePurchaseOrderStatus", SYSTEM_ADMIN),
            entry("PurchaseOrderItemControllerWeb.deletePurchaseOrderItem", SYSTEM_ADMIN),
            entry("PurchaseOrderItemController.deletePurchaseOrderItem", SYSTEM_ADMIN));

    /** A starting role that reaches a handler its old role did not, on purpose. */
    private record Gain(String role, String reason) {
    }

    /**
     * Project managers manage purchases by default, and an organization takes that away on the Roles
     * &amp; Permissions screen if it wants to: they raise and edit orders and lines, and read the
     * lines of the orders they already read. Approving and removing stay with the system admin.
     */
    private static final Map<String, Gain> DELIBERATE_GAINS = Map.ofEntries(
            entry("PurchaseOrderControllerWeb.createPurchaseOrder", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderControllerWeb.updatePurchaseOrder", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderController.createPurchaseOrder", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderController.updatePurchaseOrder", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.createPurchaseOrderItem", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.updatePurchaseOrderItem", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemById", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.getAllPurchaseOrderItems", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.getPurchaseOrderItemsPaginated", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.getItemsByPurchaseOrderId", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemControllerWeb.getItemsByMaterialId", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.createPurchaseOrderItem", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.updatePurchaseOrderItem", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.getPurchaseOrderItemById", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.getAllPurchaseOrderItems", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.getPurchaseOrderItemsPaginated", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.getItemsByPurchaseOrderId", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)),
            entry("PurchaseOrderItemController.getItemsByMaterialId", new Gain(PROJECT_MANAGER, PRODUCT_OWNER_DECISION)));

    @Autowired
    private AccessService access;

    private Project project;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        project = fixtures.project(organization, "Equivalence " + System.nanoTime());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(PurchaseOrderGuardsTest.handlers()).extracting(PurchaseOrderGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
        assertThat(OLD_GUARDS.keySet()).containsAll(DELIBERATE_GAINS.keySet());
        DELIBERATE_GAINS.forEach((handler, gain) -> assertThat(gain.reason()).as(handler).isNotBlank());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("po-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatAnyMemberDid(String slug) {
        Employee person = employee("po-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("po-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        RecordFacts onAProject = PurchaseOrderAccess.facts(organization.getId(), project.getId(), null, null);
        for (Method handler : PurchaseOrderGuardsTest.handlers()) {
            String name = PurchaseOrderGuardsTest.key(handler);
            boolean before = OLD_GUARDS.get(name).stream().anyMatch(oldRoles::contains);
            Gain gain = DELIBERATE_GAINS.get(name);
            boolean gained = gain != null && oldRoles.contains(gain.role());
            if (gained) {
                assertThat(before).as("%s is recorded as a gain for %s (%s) but the old guard already let it in",
                        name, gain.role(), gain.reason()).isFalse();
            }
            boolean after = before || gained;
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", name, oldRoles).isEqualTo(after);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on an order for %s", name, oldRoles).isEqualTo(after);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}
