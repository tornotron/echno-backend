package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventType;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.observation.ObservationAlreadyReviewedException;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.RegionEditOp;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRegionRepository;
import java.math.BigDecimal;

/**
 * Observation regions against a real database: intake and human create with box and polygon
 * regions, the validation that refuses a bad one before anything is written, the MODIFY ops that
 * retire producer rows and insert reviewer rows, the reject reason, the attachment FK, and
 * tenant isolation on the reads.
 *
 * <p>Same annotations and {@code @Import} list as {@link ObservationServiceIT}, to the letter,
 * so the context cache hands both one Spring context.
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
class ObservationRegionIT extends AbstractIntegrationTest {

    @Autowired
    private ObservationService service;

    @Autowired
    private ObservationRegionRepository regions;

    @Autowired
    private AttachmentRepository attachments;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long otherProjectId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Region Org A");
            Organization orgB = persistOrganization("Region Org B");
            Project project = new Project();
            project.setProjectName("Tower R");
            project.setOrganization(orgA);
            entityManager.persist(project);
            Project other = new Project();
            other.setProjectName("Tower R, org B");
            other.setOrganization(orgB);
            entityManager.persist(other);
            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
            otherProjectId = other.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
        enableOrgFilter(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        disableOrgFilter();
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM inspection_events WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_observation_regions WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_observations WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM attachment WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    // ------------------------------------------------------------ intake

    @Test
    void intakeWithABoxAndAPolygonOnOneImageShowsBothOnRead() {
        Long photo = attachment(orgAId, "frame-42.jpg", "image/jpeg");

        ObservationDto taken = service.intake(intake("cv:1", List.of(
                box(photo, "ppe_helmet", "0.81", "0.412", "0.188", "0.471", "0.262"),
                new ObservationRegionRequest(photo, RegionShape.POLYGON, null,
                        List.of(pt("0.10", "0.52"), pt("0.18", "0.49"), pt("0.21", "0.61"), pt("0.10", "0.52")),
                        "crack", new BigDecimal("0.66"), 4032, 3024)))).observation();
        entityManager.flush();
        entityManager.clear();

        ObservationDto read = service.findById(taken.id());
        assertThat(read.regions()).hasSize(2);
        ObservationRegionDto helmet = read.regions().get(0);
        assertThat(helmet.shape()).isEqualTo(RegionShape.BOX);
        assertThat(helmet.attachmentId()).isEqualTo(photo);
        assertThat(helmet.box()).containsExactly(d("0.412000"), d("0.188000"), d("0.471000"), d("0.262000"));
        assertThat(helmet.score()).isEqualByComparingTo("0.81");
        assertThat(helmet.origin()).isEqualTo(RegionOrigin.PRODUCER);
        assertThat(helmet.createdById()).isNull();
        ObservationRegionDto crack = read.regions().get(1);
        assertThat(crack.shape()).isEqualTo(RegionShape.POLYGON);
        assertThat(crack.points()).hasSize(3);
        assertThat(crack.box()).containsExactly(d("0.100000"), d("0.490000"), d("0.210000"), d("0.610000"));
        assertThat(crack.imageWidth()).isEqualTo(4032);
        assertThat(crack.lineOrder()).isEqualTo(1);
        // the list read carries them too
        assertThat(service.findAll(projectId, null, null, null, null, null, null, PageRequest.of(0, 10))
                .getContent().get(0).regions()).hasSize(2);
    }

    @Test
    void anInvalidRegionIsRefusedBeforeAnythingIsWritten() {
        Long photo = attachment(orgAId, "frame-1.jpg", "image/jpeg");
        Long clip = attachment(orgAId, "clip-1.mp4", "video/mp4");
        Long theirs = attachment(orgBId, "theirs.jpg", "image/jpeg");

        assertRefused("out-of-range", box(photo, "ppe_helmet", "0.5", "0.1", "0.1", "1.2", "0.3"), "[0, 1]");
        assertRefused("inverted", box(photo, "ppe_helmet", "0.5", "0.5", "0.1", "0.4", "0.3"), "x1 < x2");
        assertRefused("two-points", polygon(photo, List.of(pt("0.1", "0.1"), pt("0.2", "0.2"))), "3 to 200");
        assertRefused("bow-tie", polygon(photo,
                List.of(pt("0.1", "0.1"), pt("0.5", "0.5"), pt("0.5", "0.1"), pt("0.1", "0.5"))), "cross");
        assertRefused("on-a-clip", box(clip, "ppe_helmet", "0.5", "0.1", "0.1", "0.2", "0.2"), "images");
        assertRefused("score", box(photo, "ppe_helmet", "1.5", "0.1", "0.1", "0.2", "0.2"), "score");
        assertThatThrownBy(() -> service.intake(intake("cv:theirs",
                List.of(box(theirs, "ppe_helmet", "0.5", "0.1", "0.1", "0.2", "0.2")))))
                .isInstanceOf(ResourceNotFoundException.class);

        entityManager.flush();
        assertThat(count("SELECT count(*) FROM inspection_observations WHERE organization_id = " + orgAId)).isZero();
        assertThat(count("SELECT count(*) FROM inspection_observation_regions")).isZero();
    }

    // ------------------------------------------------------------- human

    @Test
    void aPersonsRegionsAreHumanAndCarryNoScore() {
        Long photo = attachment(orgAId, "c14.jpg", "image/jpeg");
        assertThatThrownBy(() -> service.create(human(List.of(
                box(photo, "crack", "0.9", "0.1", "0.1", "0.2", "0.2")))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no score");

        ObservationDto dto = service.create(human(List.of(box(photo, "crack", null, "0.1", "0.1", "0.2", "0.2"))));

        assertThat(dto.regions()).singleElement().satisfies(r -> {
            assertThat(r.origin()).isEqualTo(RegionOrigin.HUMAN);
            assertThat(r.score()).isNull();
        });
    }

    // ------------------------------------------------------------ review

    @Test
    void modifyReplacesRemovesAndAddsWithoutTouchingTheProducersRows() {
        Long photo = attachment(orgAId, "frame-7.jpg", "image/jpeg");
        Long second = attachment(orgAId, "frame-8.jpg", "image/jpeg");
        IntakeObservationRequest req = intake("cv:7", List.of(
                box(photo, "ppe_helmet", "0.81", "0.40", "0.10", "0.50", "0.20"),
                box(photo, "ppe_helmet", "0.55", "0.60", "0.10", "0.70", "0.20")));
        ObservationDto taken = service.intake(withEvidence(req, second)).observation();
        UUID moved = taken.regions().get(0).id();
        UUID dropped = taken.regions().get(1).id();

        ObservationDto reviewed = service.review(taken.id(), modify(List.of(
                new RegionEditOp(RegionEditKind.REPLACE, moved, null, RegionShape.BOX,
                        List.of(d("0.40"), d("0.18"), d("0.48"), d("0.27")), null, "ppe_vest", null, null),
                new RegionEditOp(RegionEditKind.REMOVE, dropped, null, null, null, null, null, null, null),
                new RegionEditOp(RegionEditKind.ADD, null, second, RegionShape.BOX,
                        List.of(d("0.61"), d("0.20"), d("0.66"), d("0.27")), null, "ppe_helmet", 4032, 3024))));
        entityManager.flush();
        entityManager.clear();

        assertThat(reviewed.reviewStatus()).isEqualTo(ObservationReviewStatus.MODIFIED);
        assertThat(reviewed.regions()).hasSize(2).allSatisfy(r -> {
            assertThat(r.origin()).isEqualTo(RegionOrigin.REVIEWER);
            assertThat(r.score()).isNull();
        });
        ObservationRegionDto replacement = reviewed.regions().get(0);
        assertThat(replacement.supersedesId()).isEqualTo(moved);
        assertThat(replacement.classKey()).isEqualTo("ppe_vest");
        assertThat(replacement.lineOrder()).isZero();
        assertThat(reviewed.regions().get(1).attachmentId()).isEqualTo(second);
        assertThat(reviewed.regions().get(1).supersedesId()).isNull();

        // the producer's rows survive, retired and unchanged
        List<ObservationRegionDto> all = service.findById(taken.id(), true).regions();
        assertThat(all).hasSize(4);
        ObservationRegionDto original = all.stream().filter(r -> r.id().equals(moved)).findFirst().orElseThrow();
        assertThat(original.retiredAt()).isNotNull();
        assertThat(original.box()).containsExactly(d("0.400000"), d("0.100000"), d("0.500000"), d("0.200000"));
        assertThat(original.classKey()).isEqualTo("ppe_helmet");
        assertThat(original.score()).isEqualByComparingTo("0.81");

        Map<String, Object> diff = reviewed.reviewChanges().stream()
                .filter(c -> "regions".equals(c.get("field"))).findFirst().orElseThrow();
        assertThat(diff.get("before")).isEqualTo(List.of(moved.toString(), dropped.toString()));
        assertThat(diff.get("after")).isEqualTo(reviewed.regions().stream().map(r -> r.id().toString()).toList());

        InspectionEvent event = events(taken.id(), InspectionEventType.OBSERVATION_REVIEWED).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> regionsChange = (Map<String, Object>) event.getAfter().get("regions");
        assertThat(regionsChange.get("before")).isEqualTo(diff.get("before"));
        List<Object> opNames = ((List<?>) regionsChange.get("ops")).stream()
                .map(op -> (Object) ((Map<?, ?>) op).get("op"))
                .toList();
        assertThat(opNames).containsExactly("replace", "remove", "add");
    }

    @Test
    void modifyRefusesEditsThatCannotStand() {
        Long photo = attachment(orgAId, "frame-9.jpg", "image/jpeg");
        Long stray = attachment(orgAId, "unrelated.jpg", "image/jpeg");
        ObservationDto taken = service.intake(intake("cv:9", List.of(
                box(photo, "ppe_helmet", "0.81", "0.40", "0.10", "0.50", "0.20")))).observation();
        ObservationDto other = service.intake(intake("cv:10", List.of(
                box(photo, "ppe_helmet", "0.70", "0.10", "0.10", "0.20", "0.20")))).observation();
        UUID only = taken.regions().get(0).id();
        entityManager.flush();

        // removing every region of a producer's observation: reject it instead
        assertModifyRefused(taken.id(), InvalidRequestException.class, "reject the observation instead",
                new RegionEditOp(RegionEditKind.REMOVE, only, null, null, null, null, null, null, null));
        // drawing on an image the observation does not cite
        assertModifyRefused(taken.id(), InvalidRequestException.class, "not evidence of this observation",
                new RegionEditOp(RegionEditKind.ADD, null, stray, RegionShape.BOX, box4("0.1", "0.1", "0.2", "0.2"),
                        null, "ppe_helmet", null, null));
        // a replacement that changes the shape kind
        assertModifyRefused(taken.id(), InvalidRequestException.class, "keeps the shape",
                new RegionEditOp(RegionEditKind.REPLACE, only, null, RegionShape.POLYGON, null,
                        List.of(pt("0.1", "0.1"), pt("0.2", "0.1"), pt("0.2", "0.2")), "ppe_helmet", null, null));
        // a replacement identical to the row it replaces
        assertModifyRefused(taken.id(), InvalidRequestException.class, "identical",
                new RegionEditOp(RegionEditKind.REPLACE, only, null, RegionShape.BOX, box4("0.4", "0.1", "0.5", "0.2"),
                        null, "ppe_helmet", null, null));
        // a region of another observation
        assertModifyRefused(taken.id(), ResourceNotFoundException.class, "was not found",
                new RegionEditOp(RegionEditKind.REMOVE, other.regions().get(0).id(), null, null, null, null, null,
                        null, null));

        assertThat(service.findById(taken.id(), true).regions()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(only);
            assertThat(r.retiredAt()).isNull();
        });
        assertThat(service.findById(taken.id()).reviewStatus()).isEqualTo(ObservationReviewStatus.PENDING);
    }

    @Test
    void rejectingAProducersRegionsNeedsAReason() {
        Long photo = attachment(orgAId, "frame-3.jpg", "image/jpeg");
        ObservationDto taken = service.intake(intake("cv:3", List.of(
                box(photo, "ppe_helmet", "0.41", "0.40", "0.10", "0.50", "0.20")))).observation();

        assertThatThrownBy(() -> service.review(taken.id(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, "a cone, not a helmet", null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("rejectReason");
        assertThatThrownBy(() -> service.review(taken.id(), new ReviewObservationRequest(
                ObservationReviewDecision.ACCEPT, null, null, null, ObservationRejectReason.DUPLICATE)))
                .isInstanceOf(InvalidRequestException.class);

        ObservationDto rejected = service.review(taken.id(), new ReviewObservationRequest(
                ObservationReviewDecision.REJECT, "a cone, not a helmet", null, null,
                ObservationRejectReason.FALSE_POSITIVE));

        assertThat(rejected.reviewStatus()).isEqualTo(ObservationReviewStatus.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo(ObservationRejectReason.FALSE_POSITIVE);
        // the regions stay active: they are what the reason applies to
        assertThat(rejected.regions()).hasSize(1);
        assertThat(events(taken.id(), InspectionEventType.OBSERVATION_REVIEWED).get(0).getAfter())
                .containsEntry("rejectReason", "false-positive");
    }

    // ------------------------------------------------------- the image FK

    @Test
    void anImageARegionIsDrawnOnCannotBeDeleted() {
        Long photo = attachment(orgAId, "frame-5.jpg", "image/jpeg");
        service.intake(intake("cv:5", List.of(box(photo, "ppe_helmet", "0.5", "0.1", "0.1", "0.2", "0.2"))));
        entityManager.flush();

        assertThat(regions.findObservationIdsByAttachmentScoped(photo)).hasSize(1);
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("DELETE FROM attachment WHERE id = :id").setParameter("id", photo)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("fk_insp_obs_region_attachment");
    }

    // ----------------------------------------------------- tenant isolation

    @Test
    void anotherTenantCannotReadTheRegions() {
        Long photo = attachment(orgAId, "frame-6.jpg", "image/jpeg");
        ObservationDto mine = service.intake(intake("cv:6", List.of(
                box(photo, "ppe_helmet", "0.5", "0.1", "0.1", "0.2", "0.2")))).observation();
        entityManager.flush();
        entityManager.clear();

        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgBId);
        enableOrgFilter(orgBId);

        assertThatThrownBy(() -> service.findById(mine.id(), true)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(regions.findByObservationScoped(mine.id())).isEmpty();
        assertThat(regions.findActiveByObservationsScoped(List.of(mine.id()))).isEmpty();
        assertThat(regions.findObservationIdsByAttachmentScoped(photo)).isEmpty();
        // nor draw on org A's image from org B
        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(otherProjectId, "cv:b", ObservationSource.AI,
                "cam-1", null, null, null, null, LocalDateTime.of(2026, 10, 26, 9, 0), "Helmet", null, null, null,
                "ppe", "1.0", null, null, List.of(box(photo, "ppe_helmet", "0.5", "0.1", "0.1", "0.2", "0.2")))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------- helpers

    private void assertRefused(String ref, ObservationRegionRequest region, String message) {
        assertThatThrownBy(() -> service.intake(intake("cv:" + ref, List.of(region))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(message);
    }

    private void assertModifyRefused(UUID id, Class<? extends Throwable> type, String message, RegionEditOp op) {
        assertThatThrownBy(() -> service.review(id, modify(List.of(op))))
                .isInstanceOf(type)
                .hasMessageContaining(message);
        // the refused request may have touched managed rows before failing; the transaction would roll back
        entityManager.clear();
    }

    private static ReviewObservationRequest modify(List<RegionEditOp> ops) {
        return new ReviewObservationRequest(ObservationReviewDecision.MODIFY, null,
                new ReviewObservationRequest.Changes(null, null, null, null, null, ops),
                ReviewObservationRequest.Outcome.none(), null);
    }

    private IntakeObservationRequest intake(String externalRef, List<ObservationRegionRequest> regionList) {
        return new IntakeObservationRequest(projectId, externalRef, ObservationSource.AI, "cam-1", null, null,
                null, "Gate 2", LocalDateTime.of(2026, 10, 26, 9, 0), "Helmet", null, null, null, "ppe", "1.0",
                null, null, regionList);
    }

    private static IntakeObservationRequest withEvidence(IntakeObservationRequest r, Long attachmentId) {
        return new IntakeObservationRequest(r.projectId(), r.externalRef(), r.source(), r.sourceDeviceId(),
                r.missionRef(), r.captureRef(), r.spatialNodeId(), r.locationNote(), r.observedAt(), r.title(),
                r.description(), r.category(), r.suggestedSeverity(), r.modelName(), r.modelVersion(),
                r.confidence(), List.of(Map.of("attachmentId", attachmentId)), r.regions());
    }

    private CreateObservationRequest human(List<ObservationRegionRequest> regionList) {
        return new CreateObservationRequest(projectId, null, null, "Column C-14", null, "Hairline crack", null,
                null, null, null, regionList);
    }

    private static ObservationRegionRequest box(Long attachmentId, String classKey, String score,
                                                String x1, String y1, String x2, String y2) {
        return new ObservationRegionRequest(attachmentId, RegionShape.BOX, box4(x1, y1, x2, y2), null, classKey,
                score == null ? null : new BigDecimal(score), null, null);
    }

    private static ObservationRegionRequest polygon(Long attachmentId, List<List<BigDecimal>> points) {
        return new ObservationRegionRequest(attachmentId, RegionShape.POLYGON, null, points, "crack",
                new BigDecimal("0.5"), null, null);
    }

    private static List<BigDecimal> box4(String x1, String y1, String x2, String y2) {
        return List.of(d(x1), d(y1), d(x2), d(y2));
    }

    private static List<BigDecimal> pt(String x, String y) {
        return List.of(d(x), d(y));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Long attachment(Long orgId, String filename, String contentType) {
        Attachment attachment = new Attachment();
        attachment.setEntityType(InspectionEvidence.ENTITY_TYPE);
        attachment.setEntityUuid(UUID.randomUUID());
        attachment.setStorageKey("inspection/" + filename);
        attachment.setOriginalFilename(filename);
        attachment.setContentType(contentType);
        attachment.setFileSize(1024L);
        attachment.setOrganization(entityManager.getReference(Organization.class, orgId));
        entityManager.persist(attachment);
        entityManager.flush();
        return attachment.getId();
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private List<InspectionEvent> events(UUID subjectId, String type) {
        return entityManager.createQuery(
                        "SELECT e FROM InspectionEvent e WHERE e.subjectId = :id AND e.eventType = :type",
                        InspectionEvent.class)
                .setParameter("id", subjectId)
                .setParameter("type", type)
                .getResultList();
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
