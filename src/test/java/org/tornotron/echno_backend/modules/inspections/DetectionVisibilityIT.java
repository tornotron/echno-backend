package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.tornotron.echno_backend.modules.inspections.api.RegionShape;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.api.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationListFilter;
import org.tornotron.echno_backend.modules.inspections.api.ObservationRegionRequest;
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
    private Long imageId;

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
        imageId = image.getId();
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

    @Test
    void theModelFiltersCombine() {
        UUID ppeHigh = detection("f1", "ppe-f", "0.1.0", "ppe_helmet", "0.90");
        UUID ppeLow = detection("f2", "ppe-f", "0.1.0", "ppe_vest", "0.40");
        UUID ppeNext = detection("f3", "ppe-f", "0.2.0", "ppe_helmet", "0.70");
        UUID other = detection("f4", "objects-f", "0.1.0", "rebar", "0.95");

        assertThat(filtered(f -> f.modelName("ppe-f"))).containsExactlyInAnyOrder(ppeHigh, ppeLow, ppeNext);
        assertThat(filtered(f -> f.modelName("ppe-f").modelVersion("0.1.0"))).containsExactlyInAnyOrder(ppeHigh, ppeLow);
        assertThat(filtered(f -> f.categories(List.of("ppe_vest", "rebar")))).containsExactlyInAnyOrder(ppeLow, other);
        assertThat(filtered(f -> f.modelName("ppe-f").minConfidence("0.5"))).containsExactlyInAnyOrder(ppeHigh, ppeNext);
        assertThat(filtered(f -> f.modelName("ppe-f").minConfidence("0.5").maxConfidence("0.8")))
                .containsExactly(ppeNext);
        assertThat(filtered(f -> f.attachmentId(imageId))).contains(ppeHigh, modelDetection, droneWithRegion)
                .doesNotContain(droneWithoutRegion);
        assertThat(filtered(f -> f.hasRegions(true))).contains(ppeHigh).doesNotContain(droneWithoutRegion, personsFinding);
        assertThat(filtered(f -> f.hasRegions(false))).contains(droneWithoutRegion, personsFinding).doesNotContain(ppeHigh);
    }

    @Test
    void aRetiredRegionDoesNotCountAsARegion() {
        UUID id = detection("f5", "ppe", "0.1.0", "ppe_helmet", "0.90");
        entityManager.createNativeQuery("UPDATE inspection_observation_regions SET retired_at = now() "
                + "WHERE observation_id = :id").setParameter("id", id).executeUpdate();

        assertThat(filtered(f -> f.hasRegions(true))).doesNotContain(id);
        assertThat(filtered(f -> f.hasRegions(false))).contains(id);
    }

    @Test
    void theListSortsByConfidence() {
        UUID low = detection("s1", "sort-model", "0.1.0", "ppe_helmet", "0.10");
        UUID high = detection("s2", "sort-model", "0.1.0", "ppe_helmet", "0.90");
        UUID mid = detection("s3", "sort-model", "0.1.0", "ppe_helmet", "0.50");

        assertThat(service.findAll(new FilterBuilder().modelName("sort-model").build(projectId),
                        PageRequest.of(0, 10, Sort.by("confidence").descending()))
                .getContent().stream().map(ObservationDto::id).toList()).containsExactly(high, mid, low);
    }

    private List<UUID> filtered(UnaryOperator<FilterBuilder> configure) {
        return service.findAll(configure.apply(new FilterBuilder()).build(projectId), PageRequest.of(0, 50))
                .getContent().stream().map(ObservationDto::id).toList();
    }

    private UUID detection(String ref, String model, String version, String classKey, String confidence) {
        return service.intake(new IntakeObservationRequest(projectId, ref, ObservationSource.AI, "unit-1", null, null, null,
                null, LocalDateTime.of(2026, 11, 8, 9, 0), "Seen", null, null, null, model, version,
                new BigDecimal(confidence), null, List.of(new ObservationRegionRequest(imageId, RegionShape.BOX,
                        List.of(new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.2")),
                        null, classKey, new BigDecimal(confidence), null, null)))).observation().id();
    }

    /** Reads better at the call site than fifteen positional nulls. */
    private static final class FilterBuilder {
        private String modelName;
        private String modelVersion;
        private List<String> categories;
        private BigDecimal minConfidence;
        private BigDecimal maxConfidence;
        private Boolean hasRegions;
        private Long attachmentId;

        FilterBuilder modelName(String value) { modelName = value; return this; }
        FilterBuilder modelVersion(String value) { modelVersion = value; return this; }
        FilterBuilder categories(List<String> value) { categories = value; return this; }
        FilterBuilder minConfidence(String value) { minConfidence = new BigDecimal(value); return this; }
        FilterBuilder maxConfidence(String value) { maxConfidence = new BigDecimal(value); return this; }
        FilterBuilder hasRegions(boolean value) { hasRegions = value; return this; }
        FilterBuilder attachmentId(Long value) { attachmentId = value; return this; }

        ObservationListFilter build(Long projectId) {
            return new ObservationListFilter(projectId, null, null, null, null, null, null, true, modelName, modelVersion,
                    categories, minConfidence, maxConfidence, hasRegions, attachmentId);
        }
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
