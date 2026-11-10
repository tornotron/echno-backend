package org.tornotron.echno_backend.employee;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;

/**
 * The starting roles' hold on the team area, stubbed onto a mocked {@code @access} and
 * {@code @teamAccess} for the web slice tests: every member reads and edits their own record and
 * sees who reports to them, the project manager also reads everyone's, and the system admin and HR
 * admin act on everyone's. {@code TeamAccessEquivalenceIT} checks the same answers against the real
 * matrix and the seeded templates.
 */
final class TeamPersonas {

    private static final List<String> OWN_ACTIONS = List.of("read", "edit", "read-reporting-lines");

    private TeamPersonas() {
    }

    /** The baseline alone: the area somewhere, but nobody's record except, at most, one's own. */
    static void member(AccessGuard access, TeamAccess team) {
        when(access.can(anyString(), anyString())).thenReturn(false);
        for (String action : OWN_ACTIONS) {
            when(access.can("team", action)).thenReturn(true);
        }
        when(team.acrossOrganization(anyString())).thenReturn(false);
        when(team.allowsFor(anyString(), anyLong())).thenReturn(false);
    }

    /** The baseline, on the record of {@code employeeId}, which is the caller's own. */
    static void employeeWhoIs(AccessGuard access, TeamAccess team, long employeeId) {
        member(access, team);
        for (String action : OWN_ACTIONS) {
            when(team.allowsFor(action, employeeId)).thenReturn(true);
        }
    }

    /** A project manager: reads every record and changes none but their own. */
    static void projectManager(AccessGuard access, TeamAccess team) {
        member(access, team);
        when(team.acrossOrganization("read")).thenReturn(true);
        when(team.allowsFor(eq("read"), anyLong())).thenReturn(true);
    }

    /** A system admin or HR admin: every action on every record. */
    static void personnelAdmin(AccessGuard access, TeamAccess team) {
        when(access.can(eq("team"), anyString())).thenReturn(true);
        when(team.acrossOrganization(anyString())).thenReturn(true);
        when(team.allowsFor(anyString(), anyLong())).thenReturn(true);
    }
}
