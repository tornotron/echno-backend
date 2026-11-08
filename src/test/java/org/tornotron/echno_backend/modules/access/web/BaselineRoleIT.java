package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;

/**
 * Design note 2.6, rule 3: the baseline role every employee holds is changed only by a holder of
 * {@code access:edit-employee-role}, and never holds a grant on the access area.
 */
class BaselineRoleIT extends RoleAssignmentITSupport {

    @Test
    void aHolderOfTheEmployeeRolePermissionChangesTheBaseline() {
        administrator("baseline-editor");
        UUID baseline = roleId("employee");

        writer.replaceRoleGrants(baseline, withHeld(baseline, new AccessGrantRequest("assets:edit", "ALL")));

        assertThat(grantsOf(baseline)).contains("assets:edit ALL", "finance:read OWN");
        assertThat(jdbc.queryForObject("SELECT customized FROM access_role WHERE id = ?", Boolean.class, baseline)).isTrue();
        assertThat(logActionsAboutRole(baseline)).containsExactly("role.grants-changed");
    }

    @Test
    void theBaselineNeverHoldsAGrantOnTheAccessArea() {
        administrator("baseline-access");
        UUID baseline = roleId("employee");
        List<String> before = grantsOf(baseline);

        for (String permission : List.of("access:read", "access:edit", "access:edit-employee-role")) {
            assertThatThrownBy(() -> writer.replaceRoleGrants(baseline,
                    withHeld(baseline, new AccessGrantRequest(permission, "ALL"))))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("access");
        }
        assertThat(grantsOf(baseline)).isEqualTo(before);
        assertThat(logActionsAboutRole(baseline)).isEmpty();
    }

    @Test
    void holdingAccessEditIsNotEnoughToChangeTheBaseline() {
        administrator("baseline-admin");
        UUID baseline = roleId("employee");
        AccessRole editor = fixtures.newRole(organization, "editor-" + System.nanoTime());
        editor.grant(AccessPermission.of("access:read"), AccessScope.ALL);
        editor.grant(AccessPermission.of("access:edit"), AccessScope.ALL);
        editor.grant(AccessPermission.of("assets:edit"), AccessScope.ALL);
        em.persist(editor);
        Employee person = employee("plain-editor");
        fixtures.assign(organization, person, editor, null);
        flush();
        signInAs(person);

        assertThatThrownBy(() -> writer.replaceRoleGrants(baseline,
                withHeld(baseline, new AccessGrantRequest("assets:edit", "ALL"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("access:edit-employee-role");
    }

    /** The role's current grants, sent back as they are, plus one more. */
    private List<AccessGrantRequest> withHeld(UUID role, AccessGrantRequest extra) {
        List<AccessGrantRequest> grants = new ArrayList<>(grantsOf(role).stream()
                .map(row -> new AccessGrantRequest(row.split(" ")[0], row.split(" ")[1]))
                .toList());
        grants.add(extra);
        return grants;
    }
}
