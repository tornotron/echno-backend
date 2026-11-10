package org.tornotron.echno_backend.modules.documentingestion;

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
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewAccess;

/**
 * Moving document ingestion onto the access matrix changes nobody's access (design note 2.5): every
 * member still reads the module through the baseline's document-ingestion:read; the system admin,
 * project manager, QA engineer and site engineer upload; the pair apply; the system admin alone
 * administers it. Which candidates a reviewer may decide is unchanged too: the pair every kind, the
 * QA engineer quality requirements and the site engineer drawings.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(ReviewAccess.class)
class DocumentIngestionAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> UPLOADERS = Set.of("system-admin", "project-manager", "qa-engineer", "site-engineer");
    private static final Set<String> PAIR = Set.of("system-admin", "project-manager");
    private static final Set<String> ADMIN = Set.of("system-admin");

    /** Each handler's guard before the move, by the action it now asks for. */
    private static final Map<String, Set<String>> OLD_GUARD_BY_ACTION = Map.ofEntries(
            entry("read", MEMBERS),
            entry("upload", UPLOADERS),
            entry(DocumentIngestionGuardsTest.REVIEW, UPLOADERS),
            entry("apply", PAIR),
            entry("admin", ADMIN));

    @Autowired
    private AccessService access;

    @Autowired
    private ReviewAccess reviewAccess;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(DocumentIngestionGuardsTest.ACTION_BY_HANDLER.values())
                .allMatch(OLD_GUARD_BY_ACTION::containsKey);
        assertThat(DocumentIngestionGuardsTest.handlers()).hasSize(DocumentIngestionGuardsTest.ACTION_BY_HANDLER.size());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("ingest-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldChecksGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatTheBaselineDoes(String slug) {
        Employee person = employee("ingest-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldChecksGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("ingest-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldChecksGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldChecksGave(Employee person, Set<String> oldRoles) {
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : DocumentIngestionGuardsTest.handlers()) {
            String key = DocumentIngestionGuardsTest.key(handler);
            Set<String> guard = OLD_GUARD_BY_ACTION.get(DocumentIngestionGuardsTest.ACTION_BY_HANDLER.get(key));
            boolean before = guard == MEMBERS || guard.stream().anyMatch(oldRoles::contains);

            assertThat(anyHeld(subject, handler)).as("%s for %s", key, oldRoles).isEqualTo(before);
        }
        for (CandidateKind kind : CandidateKind.values()) {
            assertThat(reviewAccess.mayReview(kind)).as("reviewing %s for %s", kind, oldRoles)
                    .isEqualTo(reviewedBefore(kind, oldRoles));
        }
    }

    /** ReviewService's rule before the matrix: the pair every kind, and one engineer each kind of their own. */
    private static boolean reviewedBefore(CandidateKind kind, Set<String> oldRoles) {
        if (oldRoles.stream().anyMatch(PAIR::contains)) {
            return true;
        }
        if (kind == CandidateKind.QUALITY_REQUIREMENT && oldRoles.contains("qa-engineer")) {
            return true;
        }
        return kind == CandidateKind.DRAWING && oldRoles.contains("site-engineer");
    }

    /** A guard of {@code @access.can} calls joined by {@code or} allows when one of them does. */
    private boolean anyHeld(AccessSubject subject, Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).isNotEmpty();
        return permissions.stream().anyMatch(permission -> access.check(subject, permission));
    }
}
