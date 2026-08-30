package org.tornotron.echno_backend.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.events.ProjectApprovedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The state a project is built in is optional while it is being drafted and required at the
 * moment it is approved.
 *
 * <p>Approval is what publishes {@link ProjectApprovedEvent}, and the compliance inspections that
 * event generates are keyed by the state's regulations. A project approved without one is
 * approved successfully and then fails generation out of sight in an AFTER_COMMIT listener, so
 * the user meets the missing field as an absence of inspections rather than as a message. These
 * pin the gate that moves that failure forward to the request that can still fix it, and pin the
 * two things the gate must not break: the address fallback that carries the projects predating
 * the field, and every transition that is not an approval.
 *
 * <p>Only the collaborators this path touches are mocked. Mockito passes null for the rest of the
 * constructor, which is accurate: nothing else is reachable from the method under test.
 */
@ExtendWith(MockitoExtension.class)
class ProjectApprovalStateTest {

    private static final Long ORG_ID = 42L;
    private static final Long PROJECT_ID = 7L;

    @Mock
    private ProjectRepository repository;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserContextService userContextService;

    @Mock
    private StatusTransitionRecorder statusTransitionRecorder;

    @InjectMocks
    private ProjectService service;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * The ordinary case once the field is being filled in: the project says which state it is in,
     * so approval goes through and generation has what it needs.
     */
    @Test
    void approvingAProjectThatNamesItsState_publishesTheEvent() {
        Project project = draftProject("Anywhere at all");
        project.setProjectState("Tamil Nadu");

        approve(project);

        verify(eventPublisher).publishEvent(any(ProjectApprovedEvent.class));
    }

    /**
     * The projects that predate the field. Their state is recoverable from the address line, and
     * generation reads it the same way, so requiring the column alone would refuse projects the
     * generation behind the gate would have handled perfectly well.
     */
    @Test
    void approvingAProjectWhoseAddressNamesItsState_stillPublishesTheEvent() {
        Project project = draftProject("No 32 college road, Chennai, Tamil Nadu");

        approve(project);

        verify(eventPublisher).publishEvent(any(ProjectApprovedEvent.class));
    }

    /**
     * Neither route yields a state, so the approval is refused rather than accepted into a
     * generation that cannot run. An address of "Chennai" names a city and no state at all,
     * which is the exact case the state field was added for.
     */
    @Test
    void approvingAProjectWithNoStateAnywhere_isRefusedAndPublishesNothing() {
        Project project = draftProject("Chennai");

        assertThatThrownBy(() -> approve(project))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be approved without a state");

        verify(eventPublisher, never()).publishEvent(any(ProjectApprovedEvent.class));
        verify(repository, never()).save(any(Project.class));
    }

    /**
     * A user who fills the state in on the same screen that approves sends both fields in one
     * patch, and JSON preserves the order they were written in. The gate therefore has to read
     * the state the patch is setting rather than the one the project arrived with, whichever of
     * the two the payload happens to list first.
     */
    @Test
    void aPatchThatSetsTheStateAndApprovesInOneCall_isJudgedOnTheStateItSets() {
        Project project = draftProject("Chennai");

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("status", ProjectCreationStatus.approved.name());
        updates.put("projectState", "Tamil Nadu");

        patch(project, updates);

        verify(eventPublisher).publishEvent(any(ProjectApprovedEvent.class));
        assertThat(project.getProjectState()).isEqualTo("Tamil Nadu");
    }

    /**
     * The gate is on the transition, not on the row. A project already approved before the field
     * existed still has to be editable, and a save that leaves it approved must neither be
     * refused nor generate its compliance a second time.
     */
    @Test
    void patchingAnAlreadyApprovedProjectWithNoState_isNeitherRefusedNorRepublished() {
        Project project = draftProject("Chennai");
        project.setStatus(ProjectCreationStatus.approved);

        patch(project, Map.of("projectName", "Renamed"));

        verify(eventPublisher, never()).publishEvent(any(ProjectApprovedEvent.class));
        assertThat(project.getProjectName()).isEqualTo("Renamed");
    }

    /**
     * Every other transition is untouched. A project with no state can still be moved through
     * the rest of its lifecycle, which is the point of the field being optional at creation.
     */
    @Test
    void movingAProjectWithNoStateToAnyOtherStatus_isNotGated() {
        Project project = draftProject("Chennai");

        patch(project, Map.of("status", ProjectCreationStatus.onHold.name()));

        verify(eventPublisher, never()).publishEvent(any(ProjectApprovedEvent.class));
        assertThat(project.getStatus()).isEqualTo(ProjectCreationStatus.onHold);
    }

    /**
     * The refusal has to be a 400 rather than a 500, because it is the user's missing field and
     * the message tells them which one. {@link InvalidRequestException} is what the global
     * handler maps to 400, and is the same type generation itself raises for this case.
     */
    @Test
    void theRefusalNamesTheFieldAndAnExampleOfWhatToPutInIt() {
        Project project = draftProject(null);

        assertThatThrownBy(() -> approve(project))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("state")
                .hasMessageContaining("Tamil Nadu");
    }

    private void approve(Project project) {
        patch(project, Map.of("status", ProjectCreationStatus.approved.name()));
    }

    private void patch(Project project, Map<String, Object> updates) {
        when(repository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));

        service.partialUpdateAProject(updates, PROJECT_ID, null, "PROJECT");
    }

    private Project draftProject(String address) {
        Organization organization = new Organization();
        organization.setId(ORG_ID);

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Draft");
        project.setProjectAddress(address);
        project.setStatus(ProjectCreationStatus.upcoming);
        project.setOrganization(organization);
        return project;
    }
}
