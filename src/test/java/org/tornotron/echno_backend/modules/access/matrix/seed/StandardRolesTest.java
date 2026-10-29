package org.tornotron.echno_backend.modules.access.matrix.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRole.Grant;

/**
 * The starting roles hold together: one baseline, a role for every fixed org role, unique slugs,
 * view alongside every action, grantable roles that exist, and the safeguards the roles guide
 * asks for.
 */
class StandardRolesTest {

    private final List<StandardRole> roles = StandardRoles.all();

    @Test
    void exactlyOneRoleIsTheBaselineAndItIsMember() {
        assertThat(roles).filteredOn(StandardRole::baseline).singleElement()
                .satisfies(role -> assertThat(role.slug()).isEqualTo(StandardRoles.MEMBER));
    }

    @Test
    void everyFixedOrgRoleHasAStandardRoleOfTheSameName() {
        Set<String> slugs = roles.stream().map(StandardRole::slug).collect(Collectors.toSet());

        assertThat(slugs).containsAll(Arrays.stream(OrgRole.values()).map(OrgRole::getGroupName).toList());
        assertThat(slugs).hasSize(roles.size());
    }

    @Test
    void everyActionComesWithViewOnTheSameAreaAndScope() {
        for (StandardRole role : roles) {
            Set<String> held = role.grants().stream()
                    .map(g -> g.permission().key() + "@" + g.scope())
                    .collect(Collectors.toSet());
            for (Grant grant : role.grants()) {
                assertThat(held)
                        .as("%s holds %s, so it must hold view on %s", role.slug(), grant.permission(),
                                grant.permission().area())
                        .contains(grant.permission().area() + ":view@" + grant.scope());
            }
        }
    }

    @Test
    void grantableRolesNameRolesThatExist() {
        Set<String> slugs = roles.stream().map(StandardRole::slug).collect(Collectors.toSet());

        for (StandardRole role : roles) {
            assertThat(slugs).as("roles %s may grant", role.slug()).containsAll(role.grantable());
        }
    }

    @Test
    void theBaselineLocksAPersonsOwnLeaveAndAttendance() {
        StandardRole member = StandardRoles.member();

        assertThat(member.grants()).filteredOn(Grant::locked)
                .extracting(g -> g.permission().key(), Grant::scope)
                .contains(
                        tuple("leave:create", AccessScope.OWN),
                        tuple("leave-regularisation:create", AccessScope.OWN));
    }

    @Test
    void hrCannotMakeAnyoneACompanyAdministrator() {
        assertThat(StandardRoles.hrAdmin().grantable()).doesNotContain(StandardRoles.SYSTEM_ADMIN, "hr-admin");
    }

    @Test
    void aStoreKeeperCannotApproveTheirOwnStockCorrections() {
        assertThat(StandardRoles.storeKeeper().grants())
                .extracting(Grant::permission)
                .doesNotContain(AccessPermission.of("inventory:approve"), AccessPermission.of("inventory:delete"));
    }

    @Test
    void aSiteEngineerPreparesAndVerifiesBillsButNeverCertifiesOrApprovesThem() {
        assertThat(StandardRoles.siteEngineer().grants()).extracting(g -> g.permission().key())
                .contains("progress-bills:prepare", "progress-bills:verify")
                .doesNotContain("progress-bills:certify", "progress-bills:approve");
    }

    @Test
    void assetsAreReadByEveryMemberAndMaintainedByTheAdministratorAndProjectManager() {
        // The asset register's guards before it moved onto the matrix: any member read it, and
        // only the system administrator and the project manager wrote to it.
        assertThat(StandardRoles.member().grants()).extracting(g -> g.permission().key(), Grant::scope)
                .contains(tuple("assets:view", AccessScope.ALL));
        assertThat(roles).filteredOn(role -> role.grants().stream()
                        .anyMatch(g -> g.permission().area().equals("assets") && !g.permission().action().equals("view")))
                .extracting(StandardRole::slug)
                .containsExactlyInAnyOrder(StandardRoles.SYSTEM_ADMIN, "project-manager");
        assertThat(StandardRoles.projectManager().grants()).extracting(g -> g.permission().key(), Grant::scope)
                .contains(tuple("assets:create", AccessScope.ALL), tuple("assets:edit", AccessScope.ALL),
                        tuple("assets:delete", AccessScope.ALL));
    }

    @Test
    void theCompanyAdministratorHoldsEveryActionOnEveryArea() {
        Set<String> held = StandardRoles.systemAdmin().grants().stream()
                .map(g -> g.permission().key()).collect(Collectors.toSet());

        for (String area : StandardRoles.AREAS) {
            assertThat(held).contains(area + ":view", area + ":create", area + ":edit", area + ":approve",
                    area + ":delete");
        }
        assertThat(held).contains("progress-bills:certify");
    }
}
