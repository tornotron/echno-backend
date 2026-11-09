package org.tornotron.echno_backend.modules.inspections;

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
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving the inspection module onto the access matrix changes nobody's access (design note 2.5): a
 * starting role reaches each handler exactly when the old {@code inspectionSecurity} role list for
 * that handler named its old role, using the defaults of {@code echno.security.inspection.*-roles},
 * which no environment overrides. A sign-off is checked once per discipline: the quality sign-off
 * against the old quality list and the safety sign-off against the old safety list. A role with no
 * old counterpart, and the baseline, reach none of it, as before.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. No
 * inspection area is scope aware, so the check is on the area alone.
 */
class InspectionsAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> READ = Set.of("system-admin", "project-manager", "qa-engineer",
            "safety-officer", "site-engineer");
    private static final Set<String> MANAGE = Set.of("system-admin", "project-manager", "qa-engineer",
            "safety-officer");
    private static final Set<String> REVIEW = MANAGE;
    private static final Set<String> CHECKLISTS = Set.of("system-admin", "qa-engineer");
    private static final Set<String> RAISE = Set.of("system-admin", "qa-engineer", "safety-officer");
    private static final Set<String> CORRECTIVE = Set.of("system-admin", "site-engineer");
    private static final Set<String> COMPLIANCE = Set.of("system-admin", "project-manager");
    private static final Set<String> DATASETS = Set.of("system-admin");
    private static final Set<String> QUALITY_SIGN_OFF = Set.of("system-admin", "qa-engineer");
    private static final Set<String> SAFETY_SIGN_OFF = Set.of("system-admin", "safety-officer");
    /** Stands for the per-report check: the quality or safety list by the report's discipline. */
    private static final Set<String> SIGN_OFF = Set.of("by discipline");

    private static final AccessPermission QUALITY = new AccessPermission("ncrs", "sign-off-quality");
    private static final AccessPermission SAFETY = new AccessPermission("ncrs", "sign-off-safety");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("InspectionControllerWeb#create", MANAGE),
            entry("InspectionControllerWeb#get", READ),
            entry("InspectionControllerWeb#list", READ),
            entry("InspectionControllerWeb#update", MANAGE),
            entry("InspectionControllerWeb#listAnnotations", READ),
            entry("InspectionControllerWeb#replaceAnnotations", MANAGE),
            entry("InspectionControllerWeb#downloadReport", READ),
            entry("InspectionControllerWeb#readEvidence", READ),
            entry("InspectionControllerWeb#attachEvidence", MANAGE),
            entry("InspectionControllerWeb#presignEvidence", MANAGE),
            entry("InspectionControllerWeb#registerEvidence", MANAGE),
            entry("InspectionControllerWeb#removeEvidence", MANAGE),
            entry("ObservationControllerWeb#create", MANAGE),
            entry("ObservationControllerWeb#read", READ),
            entry("ObservationControllerWeb#list", READ),
            entry("ObservationControllerWeb#review", REVIEW),
            entry("ObservationControllerWeb#reviewBatch", REVIEW),
            entry("ObservationControllerWeb#readEvidence", READ),
            entry("ObservationControllerWeb#presignEvidence", MANAGE),
            entry("ObservationControllerWeb#registerEvidence", MANAGE),
            entry("InspectionEventControllerWeb#inspectionTimeline", READ),
            entry("InspectionEventControllerWeb#ncrTimeline", READ),
            entry("InspectionEventControllerWeb#search", READ),
            entry("InspectionEventController#inspectionTimeline", READ),
            entry("InspectionEventController#ncrTimeline", READ),
            entry("InspectionEventController#search", READ),
            entry("ReinspectionControllerWeb#scheduleForNcr", SIGN_OFF),
            entry("ReinspectionControllerWeb#listForNcr", READ),
            entry("ReinspectionControllerWeb#scheduleForDefect", MANAGE),
            entry("ReinspectionControllerWeb#get", READ),
            entry("ReinspectionControllerWeb#recordOutcome", SIGN_OFF),
            entry("ReinspectionController#scheduleForNcr", SIGN_OFF),
            entry("ReinspectionController#listForNcr", READ),
            entry("ReinspectionController#scheduleForDefect", MANAGE),
            entry("ReinspectionController#get", READ),
            entry("ReinspectionController#recordOutcome", SIGN_OFF),
            entry("NcrControllerWeb#create", RAISE),
            entry("NcrControllerWeb#get", READ),
            entry("NcrControllerWeb#list", READ),
            entry("NcrControllerWeb#assign", RAISE),
            entry("NcrControllerWeb#markCorrectiveActionComplete", CORRECTIVE),
            entry("NcrControllerWeb#verify", SIGN_OFF),
            entry("NcrControllerWeb#reject", SIGN_OFF),
            entry("NcrControllerWeb#reopen", SIGN_OFF),
            entry("NcrControllerWeb#close", SIGN_OFF),
            entry("NcrControllerWeb#downloadPunchList", READ),
            entry("NcrControllerWeb#downloadReport", READ),
            entry("ChecklistTemplateControllerWeb#create", CHECKLISTS),
            entry("ChecklistTemplateControllerWeb#get", READ),
            entry("ChecklistTemplateControllerWeb#list", READ),
            entry("ChecklistTemplateControllerWeb#update", CHECKLISTS),
            entry("ChecklistTemplateControllerWeb#applicable", READ),
            entry("ChecklistTemplateControllerWeb#listStarters", READ),
            entry("ChecklistTemplateControllerWeb#adoptStarter", CHECKLISTS),
            entry("ElementTypeControllerWeb#list", READ),
            entry("ElementTypeControllerWeb#catalogue", READ),
            entry("ElementTypeControllerWeb#create", CHECKLISTS),
            entry("ElementTypeControllerWeb#update", CHECKLISTS),
            entry("TradeControllerWeb#list", READ),
            entry("TradeControllerWeb#catalogue", READ),
            entry("TradeControllerWeb#create", CHECKLISTS),
            entry("TradeControllerWeb#update", CHECKLISTS),
            entry("ComplianceControllerWeb#startGeneration", COMPLIANCE),
            entry("ComplianceControllerWeb#readJob", COMPLIANCE),
            entry("ComplianceControllerWeb#readLatestJob", COMPLIANCE),
            entry("ComplianceControllerWeb#regenerate", COMPLIANCE),
            entry("DatasetExportControllerWeb#run", DATASETS),
            entry("DatasetExportControllerWeb#list", DATASETS),
            entry("DatasetExportControllerWeb#get", DATASETS));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(InspectionsGuardsTest.handlers()).extracting(InspectionsGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("inspections-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatTheBaselineDoes(String slug) {
        Employee person = employee("inspections-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesNoneOfIt() {
        Employee person = employee("inspections-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : InspectionsGuardsTest.handlers()) {
            String key = InspectionsGuardsTest.key(handler);
            Set<String> guard = OLD_GUARDS.get(key);
            Set<AccessPermission> permissions = AccessGuardExpressions
                    .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
            if (guard == SIGN_OFF) {
                assertThat(permissions).as(key).containsExactlyInAnyOrder(QUALITY, SAFETY);
                assertThat(access.check(subject, QUALITY)).as("%s quality for %s", key, oldRoles)
                        .isEqualTo(QUALITY_SIGN_OFF.stream().anyMatch(oldRoles::contains));
                assertThat(access.check(subject, SAFETY)).as("%s safety for %s", key, oldRoles)
                        .isEqualTo(SAFETY_SIGN_OFF.stream().anyMatch(oldRoles::contains));
                continue;
            }
            assertThat(permissions).as(key).hasSize(1);
            assertThat(access.check(subject, permissions.iterator().next()))
                    .as("%s for %s", key, oldRoles).isEqualTo(guard.stream().anyMatch(oldRoles::contains));
        }
    }
}
