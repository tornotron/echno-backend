package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.project.Project;

/**
 * A grant held only on a project counts only in an area that is scope aware. An area that is not
 * narrows none of its queries, so a role given on one project would otherwise reach every record of
 * the organization there: a project-limited vendors or finance grant is ignored, while the same role
 * given across the organization holds, and a scope-aware area keeps honouring the project.
 */
class ProjectLimitedGrantIT extends RoleAssignmentITSupport {

    private static final AccessPermission EDIT_VENDORS = AccessPermission.of("vendors:edit");
    private static final AccessPermission EDIT_FINANCE = AccessPermission.of("finance:edit");
    private static final AccessPermission EDIT_ASSETS = AccessPermission.of(SCOPE_AWARE_AREA + ":edit");

    @Autowired
    private AccessService access;

    private Project project;
    private Project another;
    private AccessRole custom;

    @BeforeEach
    void aCustomRole() {
        project = fixtures.project(organization, "Project " + System.nanoTime());
        another = fixtures.project(organization, "Another " + System.nanoTime());
        custom = fixtures.newRole(organization, "site-buyer-" + System.nanoTime());
        custom.grant(EDIT_VENDORS, AccessScope.ALL);
        custom.grant(EDIT_FINANCE, AccessScope.ALL);
        custom.grant(EDIT_ASSETS, AccessScope.ALL);
        em.persist(custom);
        flush();
    }

    @Test
    void givenOnAProjectItReachesNoAreaThatIsNotScopeAware() {
        AccessSubject person = holding(project.getId());

        assertThat(access.check(person, EDIT_VENDORS)).isFalse();
        assertThat(access.check(person, EDIT_FINANCE)).isFalse();
        assertThat(access.check(person, EDIT_VENDORS, RecordFacts.of(organization.getId(), project.getId())))
                .isFalse();
        assertThat(access.filter(person, EDIT_FINANCE).isEmpty()).isTrue();
        assertThat(access.grantsFor(person).holds(EDIT_VENDORS)).isFalse();
    }

    @Test
    void givenAcrossTheOrganizationItReachesThem() {
        AccessSubject person = holding(null);

        assertThat(access.check(person, EDIT_VENDORS)).isTrue();
        assertThat(access.check(person, EDIT_FINANCE)).isTrue();
        assertThat(access.check(person, EDIT_ASSETS)).isTrue();
    }

    @Test
    void givenOnAProjectAScopeAwareAreaStillHonoursTheProject() {
        AccessSubject person = holding(project.getId());

        assertThat(access.check(person, EDIT_ASSETS)).isTrue();
        assertThat(access.check(person, EDIT_ASSETS, RecordFacts.of(organization.getId(), project.getId())))
                .isTrue();
        assertThat(access.check(person, EDIT_ASSETS, RecordFacts.of(organization.getId(), another.getId())))
                .isFalse();
        assertThat(access.filter(person, EDIT_ASSETS).projectIds()).containsExactly(project.getId());
    }

    private AccessSubject holding(Long projectId) {
        Employee person = employee("project-limited-" + System.nanoTime());
        fixtures.assign(organization, person, custom, projectId);
        flush();
        return new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
    }
}
