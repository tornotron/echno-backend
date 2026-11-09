package org.tornotron.echno_backend.chat;

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
 * Moving chat onto the access matrix changes nobody's access (design note 2.5): every handler was
 * open to any member of the organization, and every member still reaches every one of them through
 * the employee baseline, whatever role they hold, the roles with no old counterpart and the baseline
 * alone included. Which rooms and messages a member reaches is still decided by room participation.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a record on a project.
 */
class ChatAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Stands for the old membership guard, which every member of the organization passed. */
    private static final Set<String> MEMBERS = Set.of("any member");

    /** Each handler's guard before the move, by controller and handler: every one was membership. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ChatControllerWeb#readRooms", MEMBERS),
            entry("ChatControllerWeb#readRoom", MEMBERS),
            entry("ChatControllerWeb#openDirectRoom", MEMBERS),
            entry("ChatControllerWeb#markRoomRead", MEMBERS),
            entry("ChatControllerWeb#readMessages", MEMBERS),
            entry("ChatControllerWeb#sendMessage", MEMBERS),
            entry("ChatControllerWeb#sendMessageWithAttachments", MEMBERS),
            entry("ChatControllerWeb#editMessage", MEMBERS),
            entry("ChatControllerWeb#toggleReaction", MEMBERS),
            entry("ChatControllerWeb#deleteMessage", MEMBERS),
            entry("ChatControllerWeb#archiveRoom", MEMBERS),
            entry("ChatControllerWeb#stream", MEMBERS));

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
        assertThat(ChatGuardsTest.handlers()).extracting(ChatGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("chat-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesEveryHandler(String slug) {
        Employee person = employee("chat-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesEveryHandler() {
        Employee person = employee("chat-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        // A record on a project, linked to nobody. Chat is not scope aware, so only an organization-wide grant reaches it.
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        for (Method handler : ChatGuardsTest.handlers()) {
            String key = ChatGuardsTest.key(handler);
            Set<String> guard = OLD_GUARDS.get(key);
            // Every person here is an active member of the organization, so the membership guard
            // admitted each of them.
            boolean before = guard == MEMBERS;
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on a record for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}
