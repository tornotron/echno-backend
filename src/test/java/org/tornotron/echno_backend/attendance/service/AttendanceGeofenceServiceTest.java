package org.tornotron.echno_backend.attendance.service;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.validator.GeofenceValidator;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Evaluating a punch against a project's geofence, and naming who decides an exception.
 *
 * <p>The two halves are here for different reasons. The evaluation has to be able to say "no
 * verdict", because roughly one project in six carries no coordinates and a punch can arrive
 * without a position; the columns held a hard-coded {@code false} and {@code 0.0} for months
 * precisely because there was nowhere to put that answer. The approver ladder has to fall back,
 * because the reporting-manager relation exists but is set on a minority of employees.
 */
class AttendanceGeofenceServiceTest {

    private static final double PROJECT_LAT = 13.0827;
    private static final double PROJECT_LON = 80.2707;

    private final AttendanceGeofenceService service =
            new AttendanceGeofenceService(new GeofenceValidator());

    private Project project(Float latitude, Float longitude) {
        Project project = new Project();
        project.setId(12L);
        project.setProjectLatitude(latitude);
        project.setProjectLongitude(longitude);
        project.setEmployees(new ArrayList<>());
        return project;
    }

    private Project locatedProject() {
        return project((float) PROJECT_LAT, (float) PROJECT_LON);
    }

    private AttendanceSettings settings(Integer radius) {
        return AttendanceSettings.builder().geofenceRadiusMeters(radius).build();
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    @Test
    void aPunchOnSiteIsInsideTheFence_withTheRealDistanceAndRadius() {
        AttendanceGeofenceService.Evaluation evaluation = service.evaluate(
                locatedProject(), settings(100), PROJECT_LAT + 0.00005, PROJECT_LON + 0.00005);

        assertThat(evaluation.isEvaluated()).isTrue();
        assertThat(evaluation.withinFence()).isTrue();
        assertThat(evaluation.distanceMeters()).isCloseTo(7.8, within(2.0));
        assertThat(evaluation.radiusMeters()).isEqualTo(100);
    }

    @Test
    void aPunchAQuarterOfAKilometreAwayIsOutsideTheFence() {
        AttendanceGeofenceService.Evaluation evaluation = service.evaluate(
                locatedProject(), settings(100), PROJECT_LAT + 0.0023, PROJECT_LON);

        assertThat(evaluation.isOutsideFence()).isTrue();
        assertThat(evaluation.distanceMeters()).isCloseTo(256.0, within(5.0));
    }

    @Test
    void aProjectWithNoCoordinatesLeavesThePunchUnevaluated() {
        // Five of the twenty-eight projects on staging are in this state. An absent verdict here
        // is ordinary, and it must not read as a violation.
        AttendanceGeofenceService.Evaluation evaluation = service.evaluate(
                project(null, null), settings(100), PROJECT_LAT, PROJECT_LON);

        assertThat(evaluation.isEvaluated()).isFalse();
        assertThat(evaluation.isOutsideFence()).isFalse();
        assertThat(evaluation.withinFence()).isNull();
        assertThat(evaluation.distanceMeters()).isNull();
        assertThat(evaluation.radiusMeters()).isNull();
    }

    @Test
    void aPunchWithNoPositionLeavesThePunchUnevaluated() {
        assertThat(service.evaluate(locatedProject(), settings(100), null, null).isEvaluated())
                .isFalse();
    }

    @Test
    void noRadiusLeavesThePunchUnevaluated() {
        assertThat(service.evaluate(locatedProject(), settings(null), PROJECT_LAT, PROJECT_LON)
                .isEvaluated()).isFalse();
    }

    @Test
    void applyingAnUnevaluatedResultClearsAllThreeFields() {
        ClockEvent event = new ClockEvent();
        event.setIsWithinGeofence(true);
        event.setDistanceFromProject(12.0);
        event.setGeofenceRadiusMeters(100);

        service.applyTo(event, AttendanceGeofenceService.Evaluation.notEvaluated());

        assertThat(event.getIsWithinGeofence()).isNull();
        assertThat(event.getDistanceFromProject()).isNull();
        assertThat(event.getGeofenceRadiusMeters()).isNull();
    }

    @Test
    void theApproverIsTheEmployeesReportingManager() {
        Employee employee = employee(7L);
        employee.setManager(employee(55L));

        assertThat(service.resolveApprover(employee, locatedProject())).isEqualTo(55L);
    }

    @Test
    void withNoReportingManager_theApproverIsAProjectManagerOnTheSite() {
        // The fallback is deliberately the narrowest thing the schema can already answer. A
        // project has no manager pointer of its own, only membership plus the organization-wide
        // project-manager role, so it is the intersection of the two.
        Employee siteEngineer = employee(7L);
        siteEngineer.setOrgRoles(Set.of(OrgRole.SITE_ENGINEER));
        Employee projectManager = employee(31L);
        projectManager.setOrgRoles(Set.of(OrgRole.PROJECT_MANAGER));

        Project project = locatedProject();
        project.setEmployees(List.of(siteEngineer, projectManager));

        assertThat(service.resolveApprover(siteEngineer, project)).isEqualTo(31L);
    }

    @Test
    void withSeveralProjectManagers_theSameOneIsAlwaysNamed() {
        Employee first = employee(31L);
        first.setOrgRoles(Set.of(OrgRole.PROJECT_MANAGER));
        Employee second = employee(12L);
        second.setOrgRoles(Set.of(OrgRole.PROJECT_MANAGER));

        Project project = locatedProject();
        project.setEmployees(List.of(first, second));

        assertThat(service.resolveApprover(employee(7L), project)).isEqualTo(12L);
    }

    @Test
    void anEmployeeIsNeverNamedAsTheirOwnApprover() {
        // A project manager marking their own attendance from off site is the case this guards:
        // the point of the decision is that somebody else vouches for the absence.
        Employee projectManager = employee(31L);
        projectManager.setOrgRoles(Set.of(OrgRole.PROJECT_MANAGER));

        Project project = locatedProject();
        project.setEmployees(List.of(projectManager));

        assertThat(service.resolveApprover(projectManager, project)).isNull();
    }

    @Test
    void withNeitherAManagerNorAProjectManager_noApproverIsNamed() {
        // Null does not leave the record unapprovable: it falls to the record-management roles,
        // which is who decides every attendance record today.
        assertThat(service.resolveApprover(employee(7L), locatedProject())).isNull();
    }
}
