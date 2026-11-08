package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrantable;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRoleRequest;
import org.tornotron.echno_backend.organization.Organization;

/**
 * The Roles &amp; Permissions writes on CockroachDB, one test per rule of design note 2.6 that is
 * not covered by its own test class: system roles are fixed (rule 2), a caller changes only what
 * their roles may assign and gives only what they hold (rule 1), every change is in the history
 * (rule 6), nothing of another organization is reached (rule 7), names are unique ignoring case
 * (rule 8), and only enforced actions are granted.
 */
class AccessAdminWriterIT extends RoleAssignmentITSupport {

    private static final List<AccessGrantRequest> FINANCE_READ = List.of(new AccessGrantRequest("finance:read", "ALL"));

    @Test
    void systemAdminCannotBeRenamedEditedOrDeleted() {
        administrator("fixed-admin");
        UUID admin = roleId("system-admin");
        List<String> grants = grantsOf(admin);

        assertThatThrownBy(() -> writer.updateRole(admin, "Owner", null, null))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> writer.replaceRoleGrants(admin, FINANCE_READ))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(admin, List.of()))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> writer.deleteRole(admin))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThat(grantsOf(admin)).isEqualTo(grants);
        assertThat(logActionsAboutRole(admin)).isEmpty();
    }

    @Test
    void theBaselineCannotBeRenamedListedOrDeletedOrEditedWithoutTheEmployeeRolePermission() {
        administrator("baseline-admin");
        UUID baseline = roleId("employee");

        assertThatThrownBy(() -> writer.updateRole(baseline, "Staff", null, null))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(baseline, List.of()))
                .isInstanceOf(UnprocessableRequestException.class);
        assertThatThrownBy(() -> writer.deleteRole(baseline))
                .isInstanceOf(UnprocessableRequestException.class);

        Employee editor = accessEditor("access-editor");
        signInAs(editor);
        assertThatThrownBy(() -> writer.replaceRoleGrants(baseline, FINANCE_READ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("access:edit-employee-role");
        assertThat(logActionsAboutRole(baseline)).isEmpty();
    }

    @Test
    void aNewRoleCanBeHandedOutByTheAdministratorAndIsInTheHistory() {
        administrator("maker");
        Employee person = employee("new-holder");

        UUID auditor = writer.createRole("Auditor", "Reads the books", null).id();
        writer.replaceRoleGrants(auditor, FINANCE_READ);
        writer.replaceUserRoles(person.getId(), List.of(new AccessUserRoleRequest(auditor, null)));

        assertThat(grantsOf(auditor)).containsExactly("finance:read ALL");
        assertThat(assignmentsOf(person)).containsExactly(slugOf(auditor));
        assertThat(jdbc.queryForObject("SELECT customized FROM access_role WHERE id = ?", Boolean.class, auditor)).isTrue();
        assertThat(logActionsAboutRole(auditor)).containsExactlyInAnyOrder("role.created", "role.grants-changed");
        assertThat(logActionsAbout(person)).containsExactly("role.assigned");
    }

    @Test
    void anyActionBringsReadWithTheSameScope() {
        administrator("reader-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();

        writer.replaceRoleGrants(clerk, List.of(new AccessGrantRequest("finance:edit", "ALL")));

        assertThat(grantsOf(clerk)).containsExactly("finance:edit ALL", "finance:read ALL");
    }

    @Test
    void aCallerGivesAndTakesOnlyGrantsTheyHold() {
        administrator("holder-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        Employee manager = manager("manager", clerk, "finance:read");
        signInAs(manager);

        assertThatThrownBy(() -> writer.replaceRoleGrants(clerk, List.of(new AccessGrantRequest("finance:edit", "ALL"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("finance:edit");
        writer.replaceRoleGrants(clerk, FINANCE_READ);

        assertThat(grantsOf(clerk)).containsExactly("finance:read ALL");
    }

    @Test
    void aCallerChangesAndListsOnlyRolesTheirRolesMayAssign() {
        administrator("assign-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        UUID treasurer = writer.createRole("Treasurer", null, null).id();
        Employee manager = manager("lister", clerk, "finance:read");
        signInAs(manager);

        assertThatThrownBy(() -> writer.replaceRoleGrants(treasurer, FINANCE_READ))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.updateRole(treasurer, "Cashier", null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.deleteRole(treasurer))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(clerk, List.of(treasurer)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.replaceUserRoles(employee("someone").getId(),
                List.of(new AccessUserRoleRequest(treasurer, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theGrantableListIsReplacedAndRecorded() {
        administrator("grantable-admin");
        UUID lead = writer.createRole("Lead", null, null).id();
        UUID clerk = writer.createRole("Clerk", null, null).id();

        writer.replaceGrantableRoles(lead, List.of(clerk));

        assertThat(jdbc.queryForList("SELECT grantable_role_id FROM access_role_grantable WHERE role_id = ?",
                UUID.class, lead)).containsExactly(clerk);
        assertThat(logActionsAboutRole(lead)).containsExactlyInAnyOrder("role.created", "role.grantables-changed");
        assertThatThrownBy(() -> writer.replaceGrantableRoles(lead, List.of(roleId("employee"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void anActionNoGuardEnforcesIsNotGrantedButAHeldOneMayBeSentBack() {
        administrator("enforced-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();

        assertThatThrownBy(() -> writer.replaceRoleGrants(clerk, List.of(new AccessGrantRequest("wbs:edit", "ALL"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not yet controlled");

        AccessRole role = em.find(AccessRole.class, clerk);
        role.grant(AccessPermission.of("wbs:read"), AccessScope.ALL);
        role.grant(AccessPermission.of("wbs:edit"), AccessScope.ALL);
        flush();
        writer.replaceRoleGrants(clerk, List.of(new AccessGrantRequest("wbs:edit", "ALL"),
                new AccessGrantRequest("finance:read", "ALL")));

        assertThat(grantsOf(clerk)).containsExactly("finance:read ALL", "wbs:edit ALL", "wbs:read ALL");
    }

    @Test
    void namesAreUniqueInTheOrganizationIgnoringCase() {
        administrator("names-admin");
        UUID auditor = writer.createRole("Auditor", null, null).id();
        UUID clerk = writer.createRole("Clerk", null, null).id();

        assertThatThrownBy(() -> writer.createRole("AUDITOR", null, null))
                .isInstanceOf(DuplicateResourceException.class);
        assertThatThrownBy(() -> writer.updateRole(clerk, "auditor", null, null))
                .isInstanceOf(DuplicateResourceException.class);
        writer.updateRole(auditor, "AUDITOR", null, null);
        assertThat(logActionsAboutRole(auditor)).containsExactlyInAnyOrder("role.created", "role.updated");
    }

    @Test
    void aNewRoleNeverTakesAStartingRolesSlug() {
        administrator("slug-admin");
        writer.deleteRole(roleId("finance-manager"));

        String slug = writer.createRole("Finance Manager", null, null).slug();

        assertThat(slug).isEqualTo("finance-manager-2");
    }

    @Test
    void aRoleSomeoneHoldsOrThatStandsInForAnOldRoleIsNotDeleted() {
        administrator("delete-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        writer.replaceUserRoles(employee("clerk").getId(), List.of(new AccessUserRoleRequest(clerk, null)));

        assertThatThrownBy(() -> writer.deleteRole(clerk))
                .isInstanceOf(UnprocessableRequestException.class)
                .hasMessageContaining("held by 1 person");
        assertThatThrownBy(() -> writer.deleteRole(roleId("project-manager")))
                .isInstanceOf(UnprocessableRequestException.class)
                .hasMessageContaining("old role");
    }

    @Test
    void aDeletedStartingRoleStaysDeletedAndTheDeletionIsRecorded() {
        administrator("tombstone-admin");
        UUID director = roleId("management-director");

        writer.deleteRole(director);
        reconciler.reconcile(organization.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_role WHERE organization_id = ? AND slug = ?",
                Integer.class, organization.getId(), "management-director")).isZero();
        assertThat(logActionsAboutRole(director)).containsExactly("role.deleted");
    }

    @Test
    void aGrantableRowToARoleTheOrganizationMadeSurvivesTheReconciler() {
        administrator("survivor-admin");
        UUID auditor = writer.createRole("Auditor", null, null).id();

        reconciler.reconcile(organization.getId());

        assertThat(jdbc.queryForList("SELECT role_id FROM access_role_grantable WHERE grantable_role_id = ?",
                UUID.class, auditor)).contains(roleId("system-admin"));
    }

    @Test
    void anotherOrganizationsRolesAndPeopleReadAsAbsent() {
        administrator("cross-admin");
        UUID clerk = writer.createRole("Clerk", null, null).id();
        Organization other = fixtures.organization("Elsewhere " + System.nanoTime());
        AccessRole theirs = fixtures.role(other, "auditor");
        Employee outsider = fixtures.employee(other, "outsider-" + System.nanoTime(), EmployeeStatus.active);
        flush();
        UUID foreign = theirs.getId();

        assertThatThrownBy(() -> writer.updateRole(foreign, "Mine", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.replaceRoleGrants(foreign, FINANCE_READ))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(foreign, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.deleteRole(foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(clerk, List.of(foreign)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.replaceUserRoles(outsider.getId(), List.of(new AccessUserRoleRequest(clerk, null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> writer.replaceUserRoles(employee("insider").getId(),
                List.of(new AccessUserRoleRequest(foreign, null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_change_log WHERE organization_id = ?",
                Integer.class, other.getId())).isZero();
    }

    @Test
    void aRoleWithAnOldCounterpartTakesNoProjectThroughTheScreen() {
        administrator("project-admin");
        Employee person = employee("site");
        Long tower = fixtures.project(organization, "Tower A").getId();
        flush();

        assertThatThrownBy(() -> writer.replaceUserRoles(person.getId(),
                List.of(new AccessUserRoleRequest(roleId("site-engineer"), tower))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(assignmentsOf(person)).isEmpty();
    }

    /** Someone holding a custom role with {@code access:read} and {@code access:edit}, nothing more. */
    private Employee accessEditor(String name) {
        AccessRole editor = fixtures.newRole(organization, "editor-" + System.nanoTime());
        editor.grant(AccessPermission.of("access:read"), AccessScope.ALL);
        editor.grant(AccessPermission.of("access:edit"), AccessScope.ALL);
        em.persist(editor);
        Employee person = employee(name);
        fixtures.assign(organization, person, editor, null);
        flush();
        return person;
    }

    /** Someone who may assign {@code target} and holds {@code access:edit} and the extra permission. */
    private Employee manager(String name, UUID target, String held) {
        AccessRole manager = fixtures.newRole(organization, "manager-" + System.nanoTime());
        manager.grant(AccessPermission.of("access:read"), AccessScope.ALL);
        manager.grant(AccessPermission.of("access:edit"), AccessScope.ALL);
        manager.grant(AccessPermission.of(held), AccessScope.ALL);
        em.persist(manager);
        AccessRoleGrantable grantable = new AccessRoleGrantable();
        grantable.setOrganization(organization);
        grantable.setRole(manager);
        grantable.setGrantableRole(em.find(AccessRole.class, target));
        em.persist(grantable);
        Employee person = employee(name);
        fixtures.assign(organization, person, manager, null);
        flush();
        return person;
    }

    private String slugOf(UUID role) {
        return jdbc.queryForObject("SELECT slug FROM access_role WHERE id = ?", String.class, role);
    }
}
