package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.tornotron.echno_backend.modules.inspections.api.RegionShape;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;
import org.tornotron.echno_backend.modules.inspections.api.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.api.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.observation.IntakeReferenceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What a producer may cite on intake, against a real database: its own upload for the project and
 * evidence already on the project, and nothing else of the organisation. Each refusal carries the
 * code a producer can act on.
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
class ObservationProducerEvidenceIT extends AbstractIntegrationTest {

    private static final String PRODUCER = "drone-fleet-service-account";

    @Autowired
    private ObservationService service;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization org;
    private Organization otherOrg;
    private Long projectId;
    private Long otherProjectId;

    @BeforeEach
    void seed() {
        org = persistOrganization("Evidence Org A");
        otherOrg = persistOrganization("Evidence Org B");
        projectId = persistProject(org, "Tower E");
        otherProjectId = persistProject(org, "Tower F");
        entityManager.flush();
        TenantContext.setCurrentOrgId(org.getId());
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", org.getId());
        actAs(PRODUCER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aProducerMayCiteItsOwnUploadForTheProject() {
        Long image = upload(org, projectId, PRODUCER, "frame.jpg", "image/jpeg");

        ObservationDto taken = service.intake(request("cap-1", region(image), null)).observation();

        assertThat(taken.regions()).singleElement().satisfies(r -> assertThat(r.attachmentId()).isEqualTo(image));
    }

    @Test
    void anotherProducersUploadIsRefused() {
        Long theirs = upload(org, projectId, "camera-fleet-service-account", "frame.jpg", "image/jpeg");

        assertNotAllowed(request("cap-2", region(theirs), null));
    }

    @Test
    void anUploadForAnotherProjectIsRefused() {
        Long elsewhere = upload(org, otherProjectId, PRODUCER, "frame.jpg", "image/jpeg");

        assertNotAllowed(request("cap-3", region(elsewhere), null));
    }

    @Test
    void evidenceOnTheProjectMayBeCitedThroughItsInspectionOrObservation() {
        Long onInspection = attachment(org, InspectionEvidence.ENTITY_TYPE, inspectionOn(projectId), null, "image/jpeg");
        Long onObservation = attachment(org, ObservationEvidence.ENTITY_TYPE,
                service.intake(request("cap-anchor", null, null)).observation().id(), null, "image/jpeg");

        assertThatCode(() -> service.intake(request("cap-4", region(onInspection), null))).doesNotThrowAnyException();
        assertThatCode(() -> service.intake(request("cap-5", region(onObservation), null))).doesNotThrowAnyException();
    }

    @Test
    void evidenceOnAnotherProjectIsRefused() {
        Long onInspection = attachment(org, InspectionEvidence.ENTITY_TYPE, inspectionOn(otherProjectId), null, "image/jpeg");

        assertNotAllowed(request("cap-6", region(onInspection), null));
    }

    @Test
    void anAttachmentOfAnyOtherKindIsRefused() {
        Long hr = attachment(org, "EMPLOYEE_DOCUMENTS", UUID.randomUUID(), projectId, "image/jpeg");

        assertNotAllowed(request("cap-7", region(hr), null));
    }

    @Test
    void anAttachmentInAnotherOrganizationReadsAsNotFound() {
        Long theirs = attachment(otherOrg, InspectionEvidence.ENTITY_TYPE, UUID.randomUUID(), null, "image/jpeg");

        assertThatThrownBy(() -> service.intake(request("cap-8", region(theirs), null)))
                .isInstanceOf(IntakeReferenceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void evidenceRefsAreHeldToTheSameRuleAndOtherEntriesPassThrough() {
        Long mine = upload(org, projectId, PRODUCER, "frame.jpg", "image/jpeg");
        Long theirs = upload(org, projectId, "camera-fleet-service-account", "frame2.jpg", "image/jpeg");

        ObservationDto taken = service.intake(request("cap-9", null, List.of(
                Map.of("attachmentId", mine), Map.of("frameIndex", 42, "cloud", "pc-7")))).observation();

        assertThat(taken.evidenceRefs()).hasSize(2);
        assertNotAllowed(request("cap-10", null, List.of(Map.of("attachmentId", theirs))));
    }

    @Test
    void evidenceRefsHaveCaps() {
        assertValidation(request("cap-11", null, Collections.nCopies(21, Map.of("frameIndex", 1))));
        assertValidation(request("cap-12", null, List.of(Map.of("blob", "x".repeat(8 * 1024)))));
        assertValidation(request("cap-13", null, List.of(Map.of("attachmentId", "not-a-number"))));
    }

    @Test
    void anUnknownProjectAndABadShapeCarryTheirCodes() {
        Long image = upload(org, projectId, PRODUCER, "frame.jpg", "image/jpeg");

        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(987654L, "cap-14", ObservationSource.AI, "d",
                null, null, null, null, LocalDateTime.of(2026, 11, 8, 9, 0), "t", null, null, null, null, null, null,
                null, null)))
                .isInstanceOf(IntakeReferenceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "PROJECT_NOT_FOUND");
        ObservationRegionRequest inverted = new ObservationRegionRequest(image, RegionShape.BOX,
                List.of(new BigDecimal("0.5"), new BigDecimal("0.1"), new BigDecimal("0.4"), new BigDecimal("0.2")),
                null, "ppe_helmet", null, null, null);
        assertThatThrownBy(() -> service.intake(request("cap-15", inverted, null)))
                .isInstanceOf(IntakeRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_GEOMETRY");
    }

    private void assertNotAllowed(IntakeObservationRequest request) {
        assertThatThrownBy(() -> service.intake(request))
                .isInstanceOf(IntakeRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "ATTACHMENT_NOT_ALLOWED");
    }

    private void assertValidation(IntakeObservationRequest request) {
        assertThatThrownBy(() -> service.intake(request))
                .isInstanceOf(IntakeRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION");
    }

    private IntakeObservationRequest request(String ref, ObservationRegionRequest region,
                                             List<Map<String, Object>> evidenceRefs) {
        return new IntakeObservationRequest(projectId, ref, ObservationSource.DRONE, "drone-1", null, null, null, null,
                LocalDateTime.of(2026, 11, 8, 9, 0), "Seen", null, null, null, null, null, null, evidenceRefs,
                region == null ? null : List.of(region));
    }

    private static ObservationRegionRequest region(Long attachmentId) {
        return new ObservationRegionRequest(attachmentId, RegionShape.BOX,
                List.of(new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.2")),
                null, "ppe_helmet", null, null, null);
    }

    private Long upload(Organization owner, Long project, String subject, String name, String contentType) {
        ObservationIntakeUpload row = new ObservationIntakeUpload();
        row.setId(UUID.randomUUID());
        row.setOrganization(owner);
        row.setProjectId(project);
        row.setProducerSubject(subject);
        entityManager.persist(row);
        return attachment(owner, ObservationEvidence.INTAKE_UPLOAD_ENTITY_TYPE, row.getId(), project, contentType);
    }

    private Long attachment(Organization owner, String type, UUID entity, Long project, String contentType) {
        Attachment attachment = new Attachment();
        attachment.setEntityType(type);
        attachment.setEntityUuid(entity);
        attachment.setStorageKey("test/" + UUID.randomUUID());
        attachment.setOriginalFilename("frame.jpg");
        attachment.setContentType(contentType);
        attachment.setFileSize(1024L);
        attachment.setOrganization(owner);
        if (project != null) {
            attachment.setProject(entityManager.getReference(Project.class, project));
        }
        entityManager.persist(attachment);
        entityManager.flush();
        return attachment.getId();
    }

    private UUID inspectionOn(Long project) {
        Inspection inspection = new Inspection();
        inspection.setOrganization(org);
        inspection.setProjectId(project);
        inspection.setInspectionNumber("INSP-" + UUID.randomUUID().toString().substring(0, 8));
        inspection.setTitle("Slab");
        inspection.setType(InspectionType.QUALITY);
        entityManager.persist(inspection);
        entityManager.flush();
        return inspection.getId();
    }

    private Long persistProject(Organization owner, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(owner);
        entityManager.persist(project);
        entityManager.flush();
        return project.getId();
    }

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        entityManager.persist(organization);
        return organization;
    }

    private static void actAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a", AuthorityUtils.NO_AUTHORITIES));
    }
}
