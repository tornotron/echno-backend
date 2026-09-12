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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateNcrRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Machine intake against a real database: a device's finding lands pending with its metadata,
 * a repeat on the producer's reference returns the same row, the human source is refused, the
 * spatial node is checked against the project, and the reference is unique per organisation
 * rather than globally.
 *
 * <p>Same annotations and {@code @Import} list as {@link InspectionServiceIT}, to the letter,
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
        TenantEntityHelper.class, EntryNumberGenerator.class})
class ObservationIntakeIT extends AbstractIntegrationTest {

    @Autowired
    private ObservationService service;

    @Autowired
    private InspectionService inspections;

    @Autowired
    private NcrService ncrs;

    @Autowired
    private SpatialNodeService spatial;

    @Autowired
    private ObservationRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;
    private Long otherProjectId;
    private UUID zone;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org A");
            Organization orgB = persistOrganization("Org B");
            Project project = new Project();
            project.setProjectName("Tower A");
            project.setOrganization(orgA);
            entityManager.persist(project);
            Project other = new Project();
            other.setProjectName("Tower B");
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
        UUID building = node(null, SpatialLevel.BUILDING, "B1");
        UUID floor = node(building, SpatialLevel.FLOOR, "L01");
        zone = node(floor, SpatialLevel.ZONE, "Z1");
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
            deleteForOrgs("DELETE FROM ncrs WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_observations WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_trades WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM org_element_types WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project_spatial_node WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void aDeviceFindingLandsPendingWithItsMetadataAndIsIdempotentOnTheReference() {
        IntakeObservationRequest req = new IntakeObservationRequest(projectId, "drone-7:mission-3:42",
                ObservationSource.DRONE, "drone-7", "mission-3", "cap-9f", zone, null,
                LocalDateTime.of(2026, 9, 12, 8, 15), "Exposed rebar", "Rebar visible at slab edge",
                "Structural", DefectSeverity.MAJOR, "crackdet", "1.4.0", new BigDecimal("0.8125"),
                List.of(Map.of("frame", 1200, "bbox", List.of(10, 20, 110, 90))));

        ObservationService.IntakeResult first = service.intake(req);
        assertThat(first.created()).isTrue();
        ObservationDto dto = first.observation();
        assertThat(dto.reviewStatus()).isEqualTo(ObservationReviewStatus.PENDING);
        assertThat(dto.source()).isEqualTo(ObservationSource.DRONE);
        assertThat(dto.sourceDeviceId()).isEqualTo("drone-7");
        assertThat(dto.missionRef()).isEqualTo("mission-3");
        assertThat(dto.captureRef()).isEqualTo("cap-9f");
        assertThat(dto.externalRef()).isEqualTo("drone-7:mission-3:42");
        assertThat(dto.modelName()).isEqualTo("crackdet");
        assertThat(dto.modelVersion()).isEqualTo("1.4.0");
        assertThat(dto.confidence()).isEqualByComparingTo("0.8125");
        assertThat(dto.inspectionId()).isNull();
        assertThat(dto.reviewedAt()).isNull();
        assertThat(dto.outcomeKind()).isEqualTo(ObservationOutcomeKind.NONE);
        assertThat(dto.spatialPath()).hasSize(3);
        assertThat(dto.evidenceRefs()).hasSize(1);
        assertThat(events(dto.id(), InspectionEventType.OBSERVATION_CREATED)).hasSize(1)
                .allSatisfy(e -> assertThat(e.getActorId()).isEqualTo("drone-7"));

        // the retried upload: same row back, nothing new written
        entityManager.flush();
        entityManager.clear();
        ObservationService.IntakeResult again = service.intake(new IntakeObservationRequest(projectId,
                "drone-7:mission-3:42", ObservationSource.DRONE, "drone-7", "mission-3", "cap-9f", zone, null,
                LocalDateTime.of(2026, 9, 12, 8, 16), "Exposed rebar, retry", null, null, null, null, null,
                null, null));
        assertThat(again.created()).isFalse();
        assertThat(again.observation().id()).isEqualTo(dto.id());
        assertThat(again.observation().title()).isEqualTo("Exposed rebar");
        assertThat(service.findAll(projectId, ObservationReviewStatus.PENDING, null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(events(dto.id(), InspectionEventType.OBSERVATION_CREATED)).hasSize(1);
    }

    @Test
    void anAiFindingNamesTheModelAsActorAndTheHumanSourceIsRefused() {
        ObservationService.IntakeResult ai = service.intake(new IntakeObservationRequest(projectId, "cv:77",
                ObservationSource.AI, "cctv-gate-2", null, null, null, "Gate 2", LocalDateTime.of(2026, 9, 12, 9, 0),
                "No helmet", null, "Safety", null, "ppe-detect", "2.0", new BigDecimal("0.55"), null));
        assertThat(events(ai.observation().id(), InspectionEventType.OBSERVATION_CREATED))
                .allSatisfy(e -> assertThat(e.getActorId()).isEqualTo("ppe-detect"));

        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "h:1",
                ObservationSource.HUMAN, "person", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Seen", null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aNodeOutsideTheProjectIsRefused() {
        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "drone-7:9",
                ObservationSource.DRONE, "drone-7", null, null, UUID.randomUUID(), null,
                LocalDateTime.of(2026, 9, 12, 9, 0), "Crack", null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theReferenceIsUniquePerOrganisationNotGlobally() {
        ObservationDto mine = service.intake(new IntakeObservationRequest(projectId, "shared-ref",
                ObservationSource.ROBOT, "spot-1", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Mine", null, null, null, null, null, null, null)).observation();
        entityManager.flush();
        entityManager.clear();

        disableOrgFilter();
        TenantContext.setCurrentOrgId(orgBId);
        enableOrgFilter(orgBId);
        // the other tenant cannot see it, and its own use of the same reference is a new row
        assertThatThrownBy(() -> service.findById(mine.id())).isInstanceOf(ResourceNotFoundException.class);
        // a project of the other tenant is not theirs to post against
        assertThatThrownBy(() -> service.intake(new IntakeObservationRequest(projectId, "shared-ref",
                ObservationSource.ROBOT, "spot-1", null, null, null, null, LocalDateTime.of(2026, 9, 12, 9, 0),
                "Theirs", null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        ObservationService.IntakeResult theirs = service.intake(new IntakeObservationRequest(otherProjectId,
                "shared-ref", ObservationSource.ROBOT, "spot-1", null, null, null, null,
                LocalDateTime.of(2026, 9, 12, 9, 0), "Theirs", null, null, null, null, null, null, null));
        assertThat(theirs.created()).isTrue();
        assertThat(theirs.observation().id()).isNotEqualTo(mine.id());
        assertThat(repository.existsByIdAndOrganization_Id(theirs.observation().id(), orgBId)).isTrue();
    }

    // ---------------------------------------------------------- helpers

    private Observation pending(UUID inspectionId, String title) {
        Observation o = new Observation();
        o.setOrganization(entityManager.getReference(Organization.class, orgAId));
        o.setProjectId(projectId);
        o.setInspectionId(inspectionId);
        o.setSource(ObservationSource.DRONE);
        o.setSourceDeviceId("drone-7");
        o.setObservedAt(LocalDateTime.of(2026, 9, 12, 8, 30));
        o.setTitle(title);
        o.setDescription(title + " described");
        o.setReviewStatus(ObservationReviewStatus.PENDING);
        o.setOutcomeKind(ObservationOutcomeKind.NONE);
        entityManager.persist(o);
        entityManager.flush();
        return o;
    }

    private List<InspectionEvent> events(UUID subjectId, String type) {
        return entityManager.createQuery(
                        "SELECT e FROM InspectionEvent e WHERE e.subjectId = :id AND e.eventType = :type",
                        InspectionEvent.class)
                .setParameter("id", subjectId)
                .setParameter("type", type)
                .getResultList();
    }

    private UUID node(UUID parent, SpatialLevel level, String code) {
        return spatial.create(projectId, new CreateSpatialNodeRequest(parent, level, code, code,
                null, null, null, null, null)).id();
    }

    private CreateInspectionRequest request(List<InspectionCheckItemRequest> items,
                                            List<InspectionDefectRequest> defects) {
        return new CreateInspectionRequest("Slab check", InspectionType.QUALITY, null, "rcc", null,
                projectId, "Block A, Level 3", "Slab and columns", "STR-03-REV2",
                LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L, "Client Rep",
                List.of("Site Engineer"), "Clear", "32C", items, defects, zone);
    }

    private UpdateInspectionRequest update(List<InspectionCheckItemRequest> items,
                                           List<InspectionDefectRequest> defects) {
        return new UpdateInspectionRequest("Slab check", InspectionType.QUALITY, null, "rcc", null,
                InspectionStatus.IN_PROGRESS, null, projectId, "Block A, Level 3", "Slab and columns",
                "STR-03-REV2", LocalDate.of(2026, 8, 20), "09:30", null, null, 90, 100L, 200L,
                "Client Rep", List.of("Site Engineer"), "Clear", "32C", items, defects, zone);
    }

    private static InspectionCheckItemRequest checkItem(String checkPoint, CheckItemStatus status) {
        return new InspectionCheckItemRequest("Structural", checkPoint, "Within tolerance",
                status, "measured off", false, null, null, null, null, null, null, "high", null);
    }

    private static InspectionDefectRequest defect(String description) {
        return new InspectionDefectRequest("Structural", description, DefectSeverity.MAJOR,
                "Column C4, ground floor", null, "Chip out and re-pour", "ABC Contractors",
                LocalDate.of(2026, 9, 1), DefectStatus.OPEN, null, null);
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
