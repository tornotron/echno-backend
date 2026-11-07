package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationListFilter;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.DetectionVisibility;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Which rows count as a model's detection, against a real database, and that a caller without the
 * role does not get them by list, by id or through the event log. Everything else stays visible:
 * a person's finding, a device's capture with no region, and a suggestion from the compliance model.
 *
 * <p>Same annotations and {@code @Import} list as {@link ObservationServiceIT}, so the context cache
 * hands them one Spring context.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, SpatialNodeService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        TradeService.class, TradeMapperImpl.class,
        ElementTypeService.class, ElementTypeMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        ObservationService.class, ObservationMapperImpl.class,
        InspectionEventRecorder.class, InspectionEventService.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class,
        TransactionalWorkRunner.class, TransactionRetryTemplate.class, SimpleMeterRegistry.class})
class DetectionVisibilityIT extends AbstractIntegrationTest {

    @Autowired
    private ObservationService service;

    @Autowired
    private InspectionEventService events;

    @Autowired
    private ObservationRepository observations;

    @PersistenceContext
    private EntityManager entityManager;

    private Long projectId;
    private UUID modelDetection;
    private UUID regionlessAiFinding;
    private UUID droneWithRegion;
    private UUID droneWithoutRegion;
    private UUID personsFinding;

    @BeforeEach
    void seed() {
        Organization org = new Organization();
        org.setOrganizationName("Detection Org");
        org.setOrganizationAddress("Detection Org address");
        org.setOrganizationEmail("detection@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        Project project = new Project();
        project.setProjectName("Tower D");
        project.setOrganization(org);
        entityManager.persist(project);
        Attachment image = new Attachment();
        image.setEntityType(InspectionEvidence.ENTITY_TYPE);
        image.setEntityUuid(UUID.randomUUID());
        image.setStorageKey("inspection/frame.jpg");
        image.setContentType("image/jpeg");
        image.setOrganization(org);
        image.setProject(project);
        entityManager.persist(image);
        entityManager.flush();
        projectId = project.getId();
        TenantContext.setCurrentOrgId(org.getId());
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", org.getId());

        ObservationRegionRequest producerRegion = new ObservationRegionRequest(image.getId(), RegionShape.BOX,
                List.of(new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.2")),
                null, "ppe_helmet", new BigDecimal("0.8"), null, null);
        modelDetection = intake("cv/1", ObservationSource.AI, List.of(producerRegion));
        regionlessAiFinding = intake("cv/2", ObservationSource.AI, null);
        droneWithRegion = intake("drone/1", ObservationSource.DRONE, List.of(producerRegion));
        droneWithoutRegion = intake("drone/2", ObservationSource.DRONE, null);
        personsFinding = service.create(new CreateObservationRequest(projectId, null, null, "C-14", null, "Crack", null,
                null, null, null, null)).id();
        entityManager.flush();
    }

    @AfterEach
    void clearTenant() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @Test
    void theListDropsDetectionsForACallerWithoutTheRole() {
        assertThat(list(false)).containsExactlyInAnyOrder(droneWithoutRegion, personsFinding);
    }

    @Test
    void theListKeepsThemForACallerWithTheRole() {
        assertThat(list(true)).containsExactlyInAnyOrder(modelDetection, regionlessAiFinding, droneWithRegion,
                droneWithoutRegion, personsFinding);
    }

    @Test
    void aDecisionDoesNotMakeADetectionVisible() {
        service.review(modelDetection, new ReviewObservationRequest(ObservationReviewDecision.ACCEPT, null, null, null, null));
        entityManager.flush();

        assertThat(list(false)).doesNotContain(modelDetection);
    }

    @Test
    void anAiSuggestionWithNoExternalReferenceIsNotADetection() {
        assertThat(observations.countDetectionScoped(personsFinding)).isZero();
        assertThat(observations.countDetectionScoped(droneWithoutRegion)).isZero();
        assertThat(observations.countDetectionScoped(modelDetection)).isEqualTo(1);
        assertThat(observations.countDetectionScoped(regionlessAiFinding)).isEqualTo(1);
        assertThat(observations.countDetectionScoped(droneWithRegion)).isEqualTo(1);
    }

    @Test
    void readingOneByIdFollowsTheRole() {
        DetectionVisibility without = visibility(false);
        DetectionVisibility with = visibility(true);

        assertThat(without.maySee(modelDetection)).isFalse();
        assertThat(without.maySee(personsFinding)).isTrue();
        assertThat(without.maySee(UUID.randomUUID())).as("an unknown id passes through to the 404").isTrue();
        assertThat(with.maySee(modelDetection)).isTrue();
    }

    @Test
    void theEventLogDropsADetectionsEventsForACallerWithoutTheRole() {
        assertThat(eventSubjects(false)).contains(droneWithoutRegion, personsFinding)
                .doesNotContain(modelDetection, regionlessAiFinding, droneWithRegion);
        assertThat(eventSubjects(true)).contains(modelDetection, regionlessAiFinding, droneWithRegion);
    }

    private List<UUID> list(boolean includeDetections) {
        return service.findAll(new ObservationListFilter(projectId, null, null, null, null, null, null, includeDetections),
                        PageRequest.of(0, 20))
                .getContent().stream().map(ObservationDto::id).toList();
    }

    private List<UUID> eventSubjects(boolean includeDetections) {
        return events.search(projectId, InspectionEventSubjectType.OBSERVATION, null, null, null, null,
                        includeDetections, PageRequest.of(0, 50))
                .getContent().stream().map(InspectionEventDto::subjectId).toList();
    }

    private DetectionVisibility visibility(boolean holdsRole) {
        OrganizationSecurityService orgSecurity = mock(OrganizationSecurityService.class);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(holdsRole);
        return new DetectionVisibility(orgSecurity, observations, new String[] {"detection-reviewer"});
    }

    private UUID intake(String ref, ObservationSource source, List<ObservationRegionRequest> regions) {
        return service.intake(new IntakeObservationRequest(projectId, ref, source, "unit-1", null, null, null, null,
                LocalDateTime.of(2026, 11, 8, 9, 0), "Seen", null, null, null, "ppe", "0.1.0", null, null, regions))
                .observation().id();
    }
}
